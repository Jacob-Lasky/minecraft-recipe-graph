"""Every script that starts a container has to take the container gate.

WHY A TEST AND NOT A CONVENTION. `tools/gate.sh` exists because "run one container at a time"
was an instruction several people were asked to remember, and every one of them can honour it
perfectly while eight containers run: the rule was per-person and the constraint is per-host.
Writing the lock fixed that and left the same hole one level up -- nothing makes the NEXT
`docker run` take it, and the fourth person to add one gets no signal at all. A lock nothing
enforces is the thing the lock was written to replace.

This is the cheapest place to enforce it: python is the job that already runs on every pull
request. `tools/ci-java.sh` cannot see any of this -- it compiles `graph/` and `plan/` and knows
nothing about shell.

BOTH ASSERTIONS CARRY A CONTROL, and that is the part that matters rather than a detail of
style. A search that has quietly stopped matching reports perfect compliance, which is exactly
the failure mode the thing being guarded has; so each test first proves its own search can find
what it is looking for.
"""

import os
import re
import subprocess
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# The scripts known to launch a container today. NAMED, so that the discovery below has
# something to be checked against: "no ungated `docker run` found" means nothing if the walk
# found no scripts at all.
KNOWN_RUNNERS = ("tools/check.sh", "mod/tools/build-jar.sh", "harness/shot.sh")

GATE = "tools/gate.sh"
# A share cannot hold this lock: `flock -w`'s timeout is a signal, and a waiter blocked on FUSE
# sits in uninterruptible D state where it cannot be delivered, so the cap silently never fires.
# Measured on this host at 3s against a 12s holder: /tmp gave up on time, /coding waited 11s and
# then acquired.
FORBIDDEN_FS_PREFIXES = ("fuse", "nfs", "cifs", "smb", "9p", "afs", "sshfs")


def _shell_scripts():
    """Every tracked shell script, from git rather than from a walk.

    `git ls-files` and not `os.walk`, so nothing under an untracked scratch directory, a stale
    worktree or `.ci-java/` can either be demanded to take the gate or hide a real one.
    """
    out = subprocess.check_output(["git", "-C", ROOT, "ls-files", "*.sh"])
    return sorted(out.decode("utf-8").split())


def _read(rel):
    with open(os.path.join(ROOT, rel), "r") as handle:
        return handle.read()


def _docker_run_lines(text):
    """Lines that start a container, with their `gated ` prefix if they have one.

    Matched at the start of a command rather than anywhere in the line, so the sentence "wrap
    the docker run" inside a comment is not read as an invocation. Continuation lines and
    `$(...)` substitutions are not commands here and there are none in this repository; if that
    changes, this returning nothing for a real invocation is a test bug and must not be
    "fixed" by loosening it until it passes.
    """
    found = []
    for line in text.splitlines():
        match = re.match(r"^\s*(gated\s+)?(docker\s+run)\b", line)
        if match:
            found.append((match.group(1) is not None, line.strip()))
    return found


def _fstype_of(path):
    """The filesystem type backing `path`, by longest matching mount point in /proc/mounts."""
    best, kind = "", None
    with open("/proc/mounts", "r") as handle:
        for line in handle:
            parts = line.split()
            if len(parts) < 3:
                continue
            point, fstype = parts[1], parts[2]
            if (path == point or path.startswith(point.rstrip("/") + "/")) \
                    and len(point) >= len(best):
                best, kind = point, fstype
    return kind


class EveryContainerLauncherTakesTheGateTest(unittest.TestCase):

    def test_the_search_finds_the_launchers_we_already_know_about(self):
        # THE CONTROL, and it runs first on purpose. Every assertion below is of the form "no
        # bad case was found", which is what a broken search says too. This is the one that
        # fails if the search itself has stopped working.
        scripts = _shell_scripts()
        for known in KNOWN_RUNNERS:
            self.assertIn(known, scripts, "%s is not in `git ls-files '*.sh'`" % known)
            self.assertTrue(_docker_run_lines(_read(known)),
                            "%s runs a container and the search did not see it, so the "
                            "compliance check below is inspecting nothing" % known)

    def test_every_docker_run_is_gated(self):
        ungated = []
        for script in _shell_scripts():
            for is_gated, line in _docker_run_lines(_read(script)):
                if not is_gated:
                    ungated.append("%s: %s" % (script, line))
        self.assertEqual(ungated, [],
                         "these start a container without taking the gate, so they can run "
                         "alongside every other container on the host:\n  %s\n"
                         "Source `%s` and prefix the invocation with `gated`."
                         % ("\n  ".join(ungated), GATE))

    def test_every_gated_script_actually_sources_the_gate(self):
        # `gated` is a shell function, so an unsourced caller dies with "gated: not found" --
        # loudly, but only when someone runs it, which for `harness/shot.sh` is rarely and
        # expensively. Static is better here than eventual.
        missing = []
        for script in _shell_scripts():
            text = _read(script)
            if any(is_gated for is_gated, _ in _docker_run_lines(text)) \
                    and "gate.sh" not in text:
                missing.append(script)
        self.assertEqual(missing, [],
                         "these call `gated` without sourcing %s: %s" % (GATE, missing))


class TheGateLockLivesOnALocalFilesystemTest(unittest.TestCase):
    """The default lock path decides whether `GATE_WAIT` does anything at all.

    This is not a portability nicety. On `fuse.shfs` the timeout never fires, so the option
    documented three lines above it in `gate.sh` is inert for every caller who does not
    override it -- correct code, defeated by its own default, which is this project's most
    frequently rediscovered defect.
    """

    def _default_lock(self):
        text = _read(GATE)
        match = re.search(r"^GATE_LOCK=\$\{GATE_LOCK-([^}]+)\}", text, re.M)
        self.assertIsNotNone(match, "no default GATE_LOCK in %s" % GATE)
        return match.group(1)

    def test_the_classifier_can_recognise_a_share(self):
        # The control again. `_fstype_of` returning something harmless for every path -- because
        # /proc/mounts moved, or the longest-prefix match broke -- would make the assertion
        # below pass on exactly the path it exists to reject.
        shares = []
        with open("/proc/mounts", "r") as handle:
            for line in handle:
                parts = line.split()
                if len(parts) >= 3 and parts[2].startswith(FORBIDDEN_FS_PREFIXES):
                    shares.append(parts[1])
        if not shares:
            self.skipTest("no fuse/network mount on this host to test the classifier against")
        for point in shares:
            self.assertTrue(_fstype_of(point).startswith(FORBIDDEN_FS_PREFIXES),
                            "%s is a share and the classifier called it %s"
                            % (point, _fstype_of(point)))

    def test_the_default_lock_is_not_on_a_share(self):
        lock = self._default_lock()
        directory = os.path.dirname(lock) or "/"
        if not os.path.isdir(directory):
            self.skipTest("%s does not exist on this host" % directory)
        fstype = _fstype_of(directory)
        self.assertIsNotNone(fstype, "no mount found for %s" % directory)
        self.assertFalse(fstype.startswith(FORBIDDEN_FS_PREFIXES),
                         "the default gate lock %s is on %s. `flock -w` cannot time out there: "
                         "the timeout is a signal and a waiter on that filesystem is parked in "
                         "uninterruptible D state, so GATE_WAIT silently does nothing and the "
                         "waiter cannot be interrupted either. Keep the default on a local "
                         "filesystem." % (lock, fstype))


if __name__ == "__main__":
    unittest.main()
