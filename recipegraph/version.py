"""What code is running, and whether the checkout has moved on since it started.

WHY THIS EXISTS. Four defects were reported in one hour that turned out not to exist: the
plan had no nav, the machine filters did not narrow, bees were still unidentified, and the
Sources page had no add form. All four had been fixed and shipped. The `serve` process had
been running since before the merge, so it held the NEW graph -- reloaded through the
button, because the tool already watches its data files -- and the OLD code. An hour went
into re-diagnosing four fixed bugs because nothing on the page said which build drew it.
See #38.

TWO CLAIMS, and they are not the same claim:

  `describe()`     what this process IS. A version string, always shown.
  `code_stale()`   the sources on disk have changed since this process read them.

The second is the one that costs an hour, and it is exactly the shape of the existing
stale-DATA banner in server.py -- with one difference that has to survive into the wording.
Stale data is fixable in place: the file is re-read and the page offers a button. Stale
CODE is not. Python has the old modules in memory and the only fix is a restart, so the
banner must say "restart" rather than offering a button that cannot work.

MTIME STAMPS, not process start time. Same reason `server._stamp` uses them: it is the
comparison that stays honest if the checkout is updated while the process is still booting.
A start timestamp says "the file is newer than me", which is true and useless for a file
that was already newer when the modules were imported.

DO NOT hash the sources. `git describe` is the identity, and content hashing 8,700 lines on
every request buys nothing the mtime pair does not already say.

Outside a checkout -- the Docker image, a released archive -- git cannot answer, so the
identity comes from the environment the build stamped in. See `VERSION_ENV`.
"""

import os
import subprocess

# Last resort, when neither git nor the image build said anything. Bump it on release.
# DO NOT read a version out of a file the build has to remember to write: a stale one is
# indistinguishable from a correct one, which is the whole failure this module exists for.
FALLBACK_VERSION = "0.5.0"

# Baked into the Docker image by `--build-arg RECIPEGRAPH_VERSION=$(git describe ...)`.
# The image ships no .git, so without this every container reports FALLBACK_VERSION and
# the footer says the same words whichever build you are looking at -- which is the exact
# failure #38 is about, moved from the process to the image. Computed by the build rather
# than typed by a human, so it cannot be forgotten out of date; if the arg is omitted the
# reader gets FALLBACK_VERSION and no date, which reads as "unknown" rather than as a
# confident wrong answer. See the Dockerfile.
VERSION_ENV = "RECIPEGRAPH_VERSION"
DATE_ENV = "RECIPEGRAPH_BUILD_DATE"

_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
_PKG = os.path.dirname(os.path.abspath(__file__))


def _git(*args):
    """`git <args>` in the checkout, or None. Never raises and never blocks for long.

    A timeout because this runs at import: a git binary waiting on a lock, or a working
    tree on a network mount, must not be able to stop the server from starting.
    """
    try:
        out = subprocess.run(("git", "-C", _ROOT) + args, capture_output=True,
                             timeout=5, check=True)
    except (OSError, subprocess.SubprocessError):
        return None
    text = out.stdout.decode("utf-8", "replace").strip()
    return text or None


def _describe_git():
    """`(version, date)` from git, or `(None, None)` outside a checkout."""
    version = _git("describe", "--tags", "--always", "--dirty")
    if not version:
        return None, None
    return version, _git("log", "-1", "--format=%cd", "--date=short")


def source_files():
    """Every .py the running server was built from, sorted.

    Scoped to the package, not the checkout: a change to `tests/` or a README is not a
    reason to tell someone to restart their server, and treating it as one would train
    them to ignore the banner.
    """
    found = []
    for dirpath, dirnames, filenames in os.walk(_PKG):
        dirnames[:] = [d for d in dirnames if d != "__pycache__"]
        found.extend(os.path.join(dirpath, f)
                     for f in filenames if f.endswith(".py"))
    return sorted(found)


def source_stamp():
    """(newest mtime, total size, file count) over the package's sources.

    A triple rather than the mtime alone so an edit that lands inside the same mtime tick,
    and a file added or deleted, both still register.
    """
    newest, total, count = 0.0, 0, 0
    for path in source_files():
        try:
            st = os.stat(path)
        except OSError:
            continue
        newest = max(newest, st.st_mtime)
        total += st.st_size
        count += 1
    return (newest, total, count)


class Build:
    """The identity of one running process, sampled once at startup.

    Three sources, most authoritative first. A live checkout beats a baked-in string
    because it can report `-dirty`, which is the state anyone editing the code is actually
    in; the environment beats the constant because the build computed it and nobody has to
    remember to bump it.
    """

    def __init__(self):
        self.version, self.date = _describe_git()
        self.from_git = self.version is not None
        if not self.from_git:
            self.version = os.environ.get(VERSION_ENV, "").strip() or FALLBACK_VERSION
            self.date = os.environ.get(DATE_ENV, "").strip() or None
        self.stamp = source_stamp()

    def describe(self):
        """One line naming this build, for the footer of every page."""
        if self.date:
            return "recipegraph %s (%s)" % (self.version, self.date)
        # No date means neither git nor a stamped image, which the reader should know: it
        # is the difference between "this is commit abc1234" and "this is whatever the
        # image happened to contain".
        return "recipegraph %s (no build metadata)" % self.version

    def code_stale(self):
        """True when the package's sources have changed since this process read them."""
        return source_stamp() != self.stamp


def restart_note(build):
    """The warning sentence, or "" when the running code is current.

    A SENTENCE and not a button, unlike the stale-data banner it sits beside. Re-reading
    graph.json fixes stale data; nothing a request handler can do replaces the modules
    already imported into this interpreter. Offering a control that cannot work would be
    worse than saying nothing, because the reader would click it and conclude the page is
    current.
    """
    if not build.code_stale():
        return ""
    return ("The code in %s has changed since this server started, so this page may be "
            "drawn by an older build than the one on disk. Restart the server to pick it "
            "up; the reload button only re-reads data." % os.path.basename(_PKG))


BUILD = Build()
