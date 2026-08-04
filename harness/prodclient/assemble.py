#!/usr/bin/env python3
"""Assemble a real, OBFUSCATED Forge 1.12.2 client instance that can be launched with no
launcher, no Gradle and nobody at the keyboard.

WHY THIS EXISTS, AND WHY `runClient` IS NOT ENOUGH. RetroFuturaGradle's `runClient` gives a
DEOBFUSCATED workspace: FML's remapper rewrites every production mod jar from SRG names to
MCP names as it loads them. That is correct for developing one mod and fatal for booting a
whole pack, because a coremod that string-matches an SRG name finds nothing after the rename
and throws. Measured, not assumed, on the 367 jar boot of 2026-08-03:

    IllegalArgumentException: Target method boolean
      thaumcraft/common/entities/construct/EntityArcaneBore.func_184645_a(EntityPlayer, EnumHand)
      does not exist in the provided class

thrown out of ThaumcraftFix's `GenericStateMachineTransformer`, surfacing as a
`ClassNotFoundException` for a class that is demonstrably present in the jar. The pack has 75
coremods; fixing them one at a time is not a plan. An obfuscated launch is the environment
those coremods were built for, and it is also the environment Jake's own client runs, so
whatever boots here boots there.

Everything is resolved from manifests and verified by sha256 against the manifest's sha1
where one is published. Nothing is taken on the filename.
"""

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
import urllib.error
import urllib.request
import zipfile
from concurrent.futures import ThreadPoolExecutor

MC_VERSION = "1.12.2"
FORGE_VERSION = "14.23.5.2860"
FORGE_FULL = f"{MC_VERSION}-{FORGE_VERSION}"

# The default base for a library whose install_profile entry names no `url`.
MOJANG_LIBS = "https://libraries.minecraft.net/"
# `files.minecraftforge.net/maven/` is what the 2018 install_profile still points at and it
# no longer serves. Every Forge era URL is rewritten to this one. DO NOT drop the rewrite on
# the grounds that the original URL is what the manifest says: the manifest is eight years
# old and the host is gone.
FORGE_MAVEN = "https://maven.minecraftforge.net/"
# Last resort for the handful of third party coordinates neither of the above carries.
CENTRAL = "https://repo1.maven.org/maven2/"

URL_REWRITES = {
    "http://files.minecraftforge.net/maven/": FORGE_MAVEN,
    "https://files.minecraftforge.net/maven/": FORGE_MAVEN,
    "http://repo.maven.apache.org/maven2/": CENTRAL,
}


def log(msg):
    print(f"assemble: {msg}", flush=True)


def sha1_of(path):
    h = hashlib.sha1()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def fetch(url, dest, sha1=None):
    """Download `url` to `dest` unless a file with the right sha1 is already there.

    Returns True if it downloaded, False if the existing file was already correct.
    """
    if os.path.exists(dest) and os.path.getsize(dest) > 0:
        if sha1 is None or sha1_of(dest) == sha1:
            return False
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    tmp = dest + ".part"
    req = urllib.request.Request(url, headers={"User-Agent": "mrg-prodclient/1"})
    with urllib.request.urlopen(req, timeout=120) as resp, open(tmp, "wb") as out:
        shutil.copyfileobj(resp, out)
    if sha1 is not None:
        got = sha1_of(tmp)
        if got != sha1:
            os.unlink(tmp)
            raise RuntimeError(f"sha1 mismatch for {url}: want {sha1}, got {got}")
    os.replace(tmp, dest)
    return True


def maven_path(coord):
    """`group:artifact:version[:classifier]` to its maven repository path."""
    parts = coord.split(":")
    group, artifact, version = parts[0], parts[1], parts[2]
    classifier = parts[3] if len(parts) > 3 else None
    name = f"{artifact}-{version}" + (f"-{classifier}" if classifier else "") + ".jar"
    return "/".join(group.split(".") + [artifact, version, name])


def try_fetch_any(bases, rel, dest, sha1=None):
    """Try each base URL in turn. The FIRST success wins and the rest are not attempted."""
    errors = []
    for base in bases:
        url = base.rstrip("/") + "/" + rel
        try:
            fetch(url, dest, sha1)
            return url
        except (urllib.error.URLError, urllib.error.HTTPError, RuntimeError) as exc:
            errors.append(f"{url}: {exc}")
    raise RuntimeError("no source served " + rel + "\n  " + "\n  ".join(errors))


def rules_allow(lib, osname="linux"):
    """Mojang manifest `rules`, evaluated for one OS. Absent rules mean allowed."""
    rules = lib.get("rules")
    if not rules:
        return True
    allowed = False
    for rule in rules:
        applies = True
        if "os" in rule:
            applies = rule["os"].get("name") == osname
        if applies:
            allowed = rule["action"] == "allow"
    return allowed


def assemble_vanilla(root, manifest):
    """Vanilla client jar, libraries and native jars, straight from the Mojang manifest."""
    libdir = os.path.join(root, "libraries")
    jobs = []

    client_jar = os.path.join(root, "versions", MC_VERSION, f"{MC_VERSION}.jar")
    dl = manifest["downloads"]["client"]
    jobs.append(("client jar", dl["url"], client_jar, dl["sha1"]))

    natives = []
    for lib in manifest["libraries"]:
        if not rules_allow(lib):
            continue
        downloads = lib.get("downloads", {})
        art = downloads.get("artifact")
        if art:
            dest = os.path.join(libdir, art["path"])
            jobs.append((lib["name"], art["url"], dest, art["sha1"]))
        natives_key = (lib.get("natives") or {}).get("linux")
        if natives_key:
            natives_key = natives_key.replace("${arch}", "64")
            cls = (downloads.get("classifiers") or {}).get(natives_key)
            if cls:
                dest = os.path.join(libdir, cls["path"])
                jobs.append((lib["name"] + ":" + natives_key, cls["url"], dest, cls["sha1"]))
                natives.append(dest)

    run_jobs(jobs)
    return client_jar, natives


def assemble_forge(root, installer_jar):
    """Forge's own libraries, read out of the installer's `version.json`.

    NOT `install_profile.json`. Forge backported the 1.13 era installer to the tail of 1.12.2,
    so 14.23.5.2860 has no `versionInfo` key at all; the launcher profile lives in a separate
    `version.json` and `install_profile.json` holds only the installer's own work list. That
    work list is empty here (`processors: []`, `data: {}`), which is what makes reimplementing
    the install a download and not a build: there is nothing to patch or repackage.

    One library, the forge jar itself, has an EMPTY download url and is carried inside the
    installer under `maven/`. Reading the url and fetching it would produce a confusing
    failure against the empty string rather than a missing file, so the embedded copy is
    checked first for every coordinate.
    """
    libdir = os.path.join(root, "libraries")
    with zipfile.ZipFile(installer_jar) as zf:
        version_info = json.loads(zf.read("version.json"))
        embedded = {n for n in zf.namelist() if n.startswith("maven/") and n.endswith(".jar")}

        jobs = []
        for lib in version_info["libraries"]:
            art = lib["downloads"]["artifact"]
            rel = art["path"]
            dest = os.path.join(libdir, rel)
            emb = "maven/" + rel
            if emb in embedded:
                if not os.path.exists(dest) or sha1_of(dest) != art["sha1"]:
                    os.makedirs(os.path.dirname(dest), exist_ok=True)
                    with zf.open(emb) as src, open(dest, "wb") as out:
                        shutil.copyfileobj(src, out)
                    got = sha1_of(dest)
                    if got != art["sha1"]:
                        raise RuntimeError(
                            f"embedded {lib['name']} sha1 {got}, manifest says {art['sha1']}"
                        )
                    log(f"embedded {lib['name']}")
                continue
            bases = []
            if art.get("url"):
                base = art["url"][: -len(rel)]
                bases.append(URL_REWRITES.get(base, base))
            bases += [FORGE_MAVEN, MOJANG_LIBS, CENTRAL]
            jobs.append((lib["name"], bases, rel, dest, art["sha1"]))

    def one(job):
        name, bases, rel, dest, sha1 = job
        if os.path.exists(dest) and sha1_of(dest) == sha1:
            return f"have {name}"
        url = try_fetch_any(bases, rel, dest, sha1)
        return f"got  {name}  <- {url}"

    if jobs:
        with ThreadPoolExecutor(max_workers=8) as pool:
            for line in pool.map(one, jobs):
                log(line)

    return version_info


def run_jobs(jobs):
    def one(job):
        name, url, dest, sha1 = job
        if fetch(url, dest, sha1):
            return f"got  {name}"
        return f"have {name}"

    with ThreadPoolExecutor(max_workers=8) as pool:
        for line in pool.map(one, jobs):
            log(line)


def extract_natives(root, native_jars):
    """LWJGL and friends ship their .so files inside jars. Unpack them into one directory and
    hand it to the JVM as `java.library.path`, exactly as a launcher does. Signature files
    and directory entries are skipped; a jar's META-INF confuses nothing here but adds noise.
    """
    out = os.path.join(root, "natives")
    os.makedirs(out, exist_ok=True)
    count = 0
    for jar in native_jars:
        with zipfile.ZipFile(jar) as zf:
            for entry in zf.namelist():
                if entry.endswith("/") or entry.startswith("META-INF/"):
                    continue
                target = os.path.join(out, os.path.basename(entry))
                with zf.open(entry) as src, open(target, "wb") as dst:
                    shutil.copyfileobj(src, dst)
                count += 1
    log(f"natives: {count} files in {out}")
    return out


def prepare_assets(root, rfg_assets):
    """Reuse the 128 MB of assets RetroFuturaGradle already downloaded rather than pulling
    them again, WITHOUT a symlink.

    A symlink here would point at an absolute host path that does not exist inside the
    container, and the client's response to unreadable assets is not an error: it is a run
    with no sounds and no language files, which looks like a different bug entirely. So
    `objects/` is left as an empty real directory for the driver to bind mount over, and the
    source it should mount is recorded in launch.json rather than being knowledge the driver
    has to carry separately.

    The index is copied rather than mounted because of its FILENAME: RFG stores it as
    `<mc version>.json` and the client asks for `<assetIndex.id>.json`, which for 1.12.2 is
    `1.12.json`. Same file, two names.
    """
    dest = os.path.join(root, "assets")
    objects = os.path.join(dest, "objects")
    if os.path.islink(objects):
        os.unlink(objects)
    os.makedirs(objects, exist_ok=True)
    idx = os.path.join(dest, "indexes")
    os.makedirs(idx, exist_ok=True)
    src_idx = os.path.join(rfg_assets, "indexes", f"{MC_VERSION}.json")
    shutil.copy2(src_idx, os.path.join(idx, "1.12.json"))
    src_objects = os.path.join(rfg_assets, "objects")
    log(f"assets: index copied as 1.12.json; mount {src_objects} over {objects}")
    return src_objects


def build_classpath(root, version_info, client_jar):
    """Forge's `libraries` order is load bearing: launchwrapper and the ASM it patches with
    must precede the vanilla copies. Emit Forge's list first, in its own order, then
    vanilla's, then the client jar last, and skip any coordinate already emitted.

    PATHS ARE RELATIVE TO `root` AND MUST STAY THAT WAY. This tree is assembled on one
    filesystem and read inside a container that mounts it somewhere else, so an absolute path
    written here resolves to nothing there, and the JVM's answer to a classpath entry that
    does not exist is silence: it does not warn, it just fails later on the first missing
    class. Measured, with absolute paths: `Could not find or load main class
    net.minecraft.launchwrapper.Launch`, which names the class and not the reason.
    """
    libdir = os.path.join(root, "libraries")
    seen = set()
    entries = []

    def add(rel):
        if rel in seen:
            return
        seen.add(rel)
        path = os.path.join(libdir, rel)
        if not os.path.exists(path):
            raise RuntimeError(f"classpath entry missing on disk: {path}")
        entries.append(os.path.relpath(path, root))

    for lib in version_info["libraries"]:
        add(lib["downloads"]["artifact"]["path"])

    with open(os.path.join(root, "vanilla-manifest.json")) as fh:
        manifest = json.load(fh)
    for lib in manifest["libraries"]:
        if not rules_allow(lib):
            continue
        art = (lib.get("downloads") or {}).get("artifact")
        if art:
            add(art["path"])

    entries.append(os.path.relpath(client_jar, root))
    return entries


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--root", default="/coding/.recipegraph-build/prodclient")
    ap.add_argument(
        "--rfg-cache",
        default="/coding/.recipegraph-build/gradle-cache-pack/caches/retro_futura_gradle",
        help="where to find the vanilla manifest and the already downloaded assets",
    )
    args = ap.parse_args()

    root = args.root
    os.makedirs(root, exist_ok=True)

    src_manifest = os.path.join(args.rfg_cache, "mc-vanilla", f"manifest_{MC_VERSION}.json")
    dst_manifest = os.path.join(root, "vanilla-manifest.json")
    if os.path.exists(src_manifest):
        shutil.copy2(src_manifest, dst_manifest)
        log(f"vanilla manifest from RFG cache: {src_manifest}")
    elif not os.path.exists(dst_manifest):
        raise SystemExit(
            f"no vanilla manifest at {src_manifest} and none cached at {dst_manifest}"
        )
    with open(dst_manifest) as fh:
        manifest = json.load(fh)

    installer = os.path.join(root, f"forge-{FORGE_FULL}-installer.jar")
    try_fetch_any(
        [FORGE_MAVEN],
        f"net/minecraftforge/forge/{FORGE_FULL}/forge-{FORGE_FULL}-installer.jar",
        installer,
    )
    log(f"forge installer: {installer}")

    client_jar, native_jars = assemble_vanilla(root, manifest)
    version_info = assemble_forge(root, installer)
    extract_natives(root, native_jars)
    objects_src = prepare_assets(root, os.path.join(args.rfg_cache, "assets"))

    cp = build_classpath(root, version_info, client_jar)
    cp_file = os.path.join(root, "classpath.txt")
    with open(cp_file, "w") as fh:
        fh.write(":".join(cp))
    log(f"classpath: {len(cp)} entries -> {cp_file}")

    launch = {
        "id": version_info["id"],
        "mainClass": version_info["mainClass"],
        "minecraftArguments": version_info["minecraftArguments"],
        "assetIndex": manifest["assetIndex"]["id"],
        "mcVersion": MC_VERSION,
        "forgeVersion": FORGE_VERSION,
        "assetObjectsSource": objects_src,
    }
    with open(os.path.join(root, "launch.json"), "w") as fh:
        json.dump(launch, fh, indent=2)
    log(f"mainClass {launch['mainClass']}")
    log("ready")


if __name__ == "__main__":
    sys.exit(main())
