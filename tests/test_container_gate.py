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
#
# THE PRODCLIENT PAIR WAS MISSING FROM THIS LIST UNTIL #265, which meant the two heaviest
# containers in the repository -- a 7g whole-pack client and the root container that copies the
# pack out of the AMP instance -- were covered by the compliance sweep but not by the control
# that proves the sweep can see anything. A list that omits the runners it most needs to find is
# the same shape as the defect it guards.
KNOWN_RUNNERS = ("tools/check.sh", "mod/tools/build-jar.sh", "harness/shot.sh",
                 "harness/prodclient/prodshot.sh", "harness/prodclient/stage-instance.sh")

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


def _blank_comments(text):
    """Comment lines blanked, keeping the line count so indexes still line up.

    Whole lines only, as `test_prodclient_harness.strip_comments` does: a `#` mid-line is
    `"$#"` more often than it is a comment in this repository, and a stripper that gets that
    wrong deletes code.
    """
    return "\n".join("" if line.lstrip().startswith("#") else line
                     for line in text.splitlines())


def _docker_run_lines(text):
    """Lines that start a container, with their `gated ` prefix if they have one.

    Matched at the start of a command rather than anywhere in the line, so the sentence "wrap
    the docker run" inside a comment is not read as an invocation. Continuation lines and
    `$(...)` substitutions are not commands here and there are none in this repository; if that
    changes, this returning nothing for a real invocation is a test bug and must not be
    "fixed" by loosening it until it passes.

    The `gated ` prefix alone is no longer the whole compliance question -- see
    `_ungated_docker_runs` -- but this stays the SEARCH, and `KNOWN_RUNNERS` is its control.
    """
    found = []
    for line in _blank_comments(text).splitlines():
        match = re.match(r"^\s*(gated\s+)?(docker\s+run)\b", line)
        if match:
            found.append((match.group(1) is not None, line.strip()))
    return found


FUNCTION_DEF = re.compile(r"^\s*([A-Za-z_][A-Za-z0-9_]*)\s*\(\)\s*\{\s*$")

# Words that are followed by another command rather than being one. `!` and `then` are here
# because the first draft of this file did not have them, and its own control caught it: a
# `boot` in `if [ -n "$FAST" ]; then boot; fi` was invisible, so a function with one gated call
# site and one bare one was reported COMPLIANT. That is the fail-OPEN direction, in the checker
# whose whole job is to notice an ungated container, found only because the control existed.
COMMAND_PREFIXES = frozenset((
    "if", "then", "else", "elif", "do", "while", "until", "!", "time", "nohup", "setsid",
    "exec", "eval", "command",
))
SEPARATORS = frozenset((";", "(", ")", "&", "|", "&&", "||"))
ASSIGNMENT = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*=")


def _command_words(line):
    """[(word, was it preceded by `gated`)] for every word in COMMAND position on this line.

    Quoted spans are dropped first, so the sentence `GATE_LOCK= to opt out` inside gate.sh's
    own help text is not read as an opt-out and `echo "gated foo"` is not read as a gated call.
    """
    unquoted = re.sub(r'"[^"]*"', " ", line)
    unquoted = re.sub(r"'[^']*'", " ", unquoted)
    spaced = re.sub(r"([;()&|])", r" \1 ", unquoted)
    words, expect, gated = [], True, False
    for word in spaced.split():
        if word in SEPARATORS:
            expect, gated = True, False
            continue
        if not expect:
            continue
        if word in COMMAND_PREFIXES or ASSIGNMENT.match(word):
            continue
        if word == "gated":
            gated = True
            continue
        words.append((word, gated))
        expect, gated = False, False
    return words


def _functions(text):
    """{name: (first line index, closing-brace line index)} for `name() {` ... `}` at column 0.

    Deliberately narrow. Every function in this repository is written that way, and a parser
    that quietly fails to find one reports the `docker run` inside it as belonging to no
    function at all -- which this file treats as UNGATED. It fails closed, which is the only
    acceptable direction for a search whose silence is read as compliance.
    """
    lines = _blank_comments(text).splitlines()
    found = {}
    for start, line in enumerate(lines):
        match = FUNCTION_DEF.match(line)
        if not match:
            continue
        for end in range(start + 1, len(lines)):
            if lines[end].rstrip() == "}":
                found[match.group(1)] = (start, end)
                break
    return found


def _enclosing_function(functions, line_index):
    for name, (start, end) in functions.items():
        if start < line_index < end:
            return name
    return None


def _call_sites(text, name):
    """[(line index, is gated)] for every place `name` is run as a command.

    The definition line `name() {` is not one: `_command_words` splits on the parenthesis, so
    the word it sees there is `name` followed by an empty command, and the definition would
    count as a bare call site if it were not skipped explicitly here.
    """
    functions = _functions(text)
    definition = functions.get(name, (None, None))[0]
    sites = []
    for index, line in enumerate(_blank_comments(text).splitlines()):
        if index == definition:
            continue
        for word, gated in _command_words(line):
            if word == name:
                sites.append((index, gated))
    return sites


def _gate_covered_functions(text):
    """The functions that CANNOT run without the gate held, by their call sites.

    WHY THIS EXISTS RATHER THAN A `gated ` PREFIX ON EVERY LINE. #265 needed one acquisition to
    span a restage and the boot that follows it, and `gated` takes a command, so the two steps
    have to become one function. The `docker run` inside it then carries no prefix, and the
    honest question stops being "does this line say gated" and becomes "can this line be
    reached without the lock". That is a property of the call sites, and it is transitive:
    `stage_from_amp` is called only from `stage_instance`, which is called only as
    `gated stage_instance`.

    THIS IS A STRENGTHENING AND NOT AN EXEMPTION, and the difference is worth being explicit
    about because the loose version of this check is how a gate quietly stops being one. A
    prefix proves one line took the lock. This proves every path into the line holds it, and a
    function with even one bare call site is not covered no matter how many gated ones it has.
    The controls in `TheGateSearchCanRejectTest` feed it the cases it must reject.
    """
    functions = _functions(text)
    covered = set()
    changed = True
    while changed:
        changed = False
        for name in functions:
            if name in covered:
                continue
            sites = _call_sites(text, name)
            if not sites:
                continue
            reachable_ungated = False
            for index, is_gated in sites:
                if is_gated:
                    continue
                enclosing = _enclosing_function(functions, index)
                if enclosing is None or enclosing not in covered:
                    reachable_ungated = True
                    break
            if not reachable_ungated:
                covered.add(name)
                changed = True
    return functions, covered


def _ungated_docker_runs(text):
    """[(line, why)] for every container this text can start without the gate held."""
    functions, covered = _gate_covered_functions(text)
    bad = []
    for index, line in enumerate(_blank_comments(text).splitlines()):
        if not re.match(r"^\s*(gated\s+)?docker\s+run\b", line):
            continue
        if re.match(r"^\s*gated\s+docker\s+run\b", line):
            continue
        enclosing = _enclosing_function(functions, index)
        if enclosing is None:
            bad.append((line.strip(), "no `gated` prefix and not inside a function"))
        elif enclosing not in covered:
            bad.append((line.strip(),
                        "inside %s(), which is reachable without the gate: it is either never "
                        "called, or at least one of its call sites is not `gated %s`"
                        % (enclosing, enclosing)))
    return bad


def _uses_gated(text):
    """True when the script runs `gated` as a command, whatever it wraps."""
    return any(gated
               for line in _blank_comments(text).splitlines()
               for _word, gated in _command_words(line))


def _gate_lock_optouts(text):
    """Line indexes where `GATE_LOCK=` is used as gate.sh's empty-value opt-out."""
    found = []
    for index, line in enumerate(_blank_comments(text).splitlines()):
        unquoted = re.sub(r'"[^"]*"', " ", line)
        unquoted = re.sub(r"'[^']*'", " ", unquoted)
        if re.search(r"(?:^|\s)GATE_LOCK=(?:\s|$)", unquoted):
            found.append(index)
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
            for line, why in _ungated_docker_runs(_read(script)):
                ungated.append("%s: %s\n      (%s)" % (script, line, why))
        self.assertEqual(ungated, [],
                         "these start a container without taking the gate, so they can run "
                         "alongside every other container on the host:\n  %s\n"
                         "Source `%s` and either prefix the invocation with `gated`, or put it "
                         "in a function whose every call site is `gated <function>`."
                         % ("\n  ".join(ungated), GATE))

    def test_every_gated_script_actually_sources_the_gate(self):
        # `gated` is a shell function, so an unsourced caller dies with "gated: not found" --
        # loudly, but only when someone runs it, which for `harness/shot.sh` is rarely and
        # expensively. Static is better here than eventual.
        #
        # Keyed on USING `gated` at all rather than on gating a `docker run` line, because
        # since #265 the gate can be taken around a function instead, and a check that only
        # looked at `gated docker run` lines stopped seeing `harness/prodclient/prodshot.sh`
        # entirely -- a script whose single acquisition wraps everything it does.
        missing = []
        for script in _shell_scripts():
            text = _read(script)
            if _uses_gated(text) and "gate.sh" not in text:
                missing.append(script)
        self.assertEqual(missing, [],
                         "these call `gated` without sourcing %s: %s" % (GATE, missing))

    def test_the_scripts_that_hold_the_gate_across_a_call_say_so(self):
        """`GATE_LOCK=` is correct in exactly one situation and catastrophic in every other.

        It is `gate.sh`'s opt-out: the caller has already taken the lock and the callee must
        not try to take it again, because `flock` is not recursive and the second attempt
        deadlocks against the first. #265 introduced the first legitimate use in this tree.
        Anywhere else the same three characters are an ungated container, and nothing else in
        this file would notice: the `docker run` it leads to is in a different script.
        """
        for script in _shell_scripts():
            text = _read(script)
            optouts = _gate_lock_optouts(text)
            if not optouts:
                continue
            functions = _functions(text)
            _, covered = _gate_covered_functions(text)
            lines = _blank_comments(text).splitlines()
            for index in optouts:
                enclosing = _enclosing_function(functions, index)
                self.assertTrue(enclosing is not None and enclosing in covered,
                                "%s:%d takes gate.sh's opt-out outside a held gate, which is "
                                "not an opt-out, it is an ungated run:\n  %s"
                                % (script, index + 1, lines[index].strip()))

    def test_the_image_build_is_inside_the_same_acquisition_as_the_run(self):
        """`docker build` is not `docker run`, and it is still shared mutable state. #265.

        Both harness scripts rebuild their image on EVERY run, deliberately, because a branch
        may change the Dockerfile and a stale image is invisible in the output -- prodshot's
        image supplies the JVM the game runs on. But the tag lives on a daemon every worktree
        on this host shares, so a build outside the gate lets another agent replace the tag
        between our build and our run, and we boot their image. That is the same defect as the
        jar, and `test_every_docker_run_is_gated` cannot see it, because the line it would have
        to look at is not a `docker run`.

        A cold build is also not free -- it pulls a base image and runs a package manager --
        and racing that against another agent's 7g container is the memory contention the gate
        exists for on a host that also runs a household.
        """
        for script in ("harness/prodclient/prodshot.sh", "harness/shot.sh"):
            text = _read(script)
            functions = _functions(text)
            _, covered = _gate_covered_functions(text)
            lines = _blank_comments(text).splitlines()
            builds = [i for i, line in enumerate(lines)
                      if re.search(r"\bdocker\s+build\b", line)]
            runs = [i for i, line in enumerate(lines)
                    if re.search(r"\bdocker\s+run\b", line)]
            self.assertEqual(1, len(builds), "%s: expected one build, got %s" % (script, builds))
            self.assertEqual(1, len(runs), "%s: expected one run, got %s" % (script, runs))
            holder = _enclosing_function(functions, runs[0])
            self.assertIn(holder, covered,
                          "%s: the run is not under a held gate at all" % script)
            self.assertEqual(holder, _enclosing_function(functions, builds[0]),
                             "%s builds its image outside the acquisition that runs it, so "
                             "another agent's build can replace the tag in between and this "
                             "run boots their image" % script)
            self.assertLess(builds[0], runs[0],
                            "%s runs the image before building it" % script)

    def test_the_optout_search_can_recognise_one(self):
        """The control for the test above, which is another "nothing bad was found".

        It also pins the one legitimate use in the tree, so deleting `prodshot.sh`'s single
        acquisition by deleting the opt-out cannot pass silently.
        """
        self.assertEqual([0], _gate_lock_optouts("GATE_LOCK= inner.sh --mod-only\n"))
        self.assertEqual([], _gate_lock_optouts('echo "GATE_LOCK= to opt out"\n'),
                         "gate.sh's own help text is not an opt-out")
        self.assertEqual([], _gate_lock_optouts("GATE_LOCK=${GATE_LOCK-/tmp/x.lock}\n"),
                         "a default is not an opt-out")
        self.assertTrue(_gate_lock_optouts(_read("harness/prodclient/prodshot.sh")),
                        "prodshot.sh no longer holds the gate across its restage; the two "
                        "acquisitions #265 merged are back")


class TheGateSearchCanRejectTest(unittest.TestCase):
    """The control for `_ungated_docker_runs`, and it is the reason that search is allowed to
    accept a `docker run` with no `gated` in front of it at all.

    `test_every_docker_run_is_gated` is of the form "nothing bad was found", which is what a
    broken search says too -- and this one got MORE clever in #265, which is exactly when that
    stops being a theoretical worry. Every case below is fed to the same function the real
    scripts go through. The rejections matter more than the acceptances: a checker that
    approves everything passes the whole repository.
    """

    def _reject(self, script, because):
        self.assertTrue(_ungated_docker_runs(script),
                        "this must be rejected and was not (%s):\n%s" % (because, script))

    def _accept(self, script):
        self.assertEqual(_ungated_docker_runs(script), [],
                         "this is gated and was rejected:\n%s" % script)

    def test_a_bare_docker_run_is_rejected(self):
        self._reject("docker run --rm alpine true\n", "no gate anywhere")

    def test_a_gated_docker_run_is_accepted(self):
        self._accept("gated docker run --rm alpine true\n")

    def test_a_function_called_only_under_the_gate_is_accepted(self):
        self._accept("boot() {\n    docker run --rm alpine true\n}\ngated boot\n")

    def test_a_function_called_bare_is_rejected(self):
        self._reject("boot() {\n    docker run --rm alpine true\n}\nboot\n",
                     "the function's only call site does not hold the gate")

    def test_a_function_called_both_ways_is_rejected(self):
        # The one that matters most. A single unguarded path is the whole defect, and a
        # checker that answered "yes, it is called gated somewhere" would wave it through.
        self._reject("boot() {\n    docker run --rm alpine true\n}\n"
                     "gated boot\nif [ -n \"$FAST\" ]; then boot; fi\n",
                     "one of two call sites is bare")

    def test_a_function_that_is_never_called_is_rejected(self):
        self._reject("boot() {\n    docker run --rm alpine true\n}\n",
                     "an uncalled function proves nothing about who holds the lock")

    def test_a_transitively_gated_function_is_accepted(self):
        self._accept("inner() {\n    docker run --rm alpine true\n}\n"
                     "outer() {\n    inner\n}\ngated outer\n")

    def test_a_transitively_UNgated_function_is_rejected(self):
        # The control on the transitive step itself. Same two-level shape as the case above,
        # with the gate missing at the top, so a closure that simply marked everything
        # reachable would accept it.
        self._reject("inner() {\n    docker run --rm alpine true\n}\n"
                     "outer() {\n    inner\n}\nouter\n",
                     "the outer function is itself called without the gate")

    def test_a_call_site_the_matcher_cannot_see_is_rejected(self):
        # `FOO=bar boot` is a real call and this matcher does not recognise it. The point is
        # the DIRECTION of that ignorance: an unseen call site leaves the function with no
        # gated call sites at all, so it is rejected rather than assumed safe.
        self._reject("boot() {\n    docker run --rm alpine true\n}\nFOO=bar boot\n",
                     "an unrecognised call site must fail closed")

    def test_the_real_prodshot_is_accepted_through_a_function(self):
        # Not a control: the case the machinery was built for, asserted end to end so that a
        # rewrite of prodshot.sh that reintroduces two acquisitions has to come past here.
        runs = _docker_run_lines(_read("harness/prodclient/prodshot.sh"))
        self.assertTrue(runs, "the search no longer sees prodshot.sh's container at all")
        self.assertFalse(any(is_gated for is_gated, _ in runs),
                         "prodshot.sh's `docker run` carries a `gated` prefix again, which "
                         "means it is a SECOND acquisition and the restage before it is in a "
                         "window another agent can restage inside. #265.")
        self._accept(_read("harness/prodclient/prodshot.sh"))


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


class TheGiveUpMessageTellsBusyFromWedgedTest(unittest.TestCase):
    """`GATE_WAIT` firing on a MOVING queue must not read as a wedged host. #214.

    The old message printed "Something is holding it: check `docker ps` before deleting it"
    however long the queue was, and the reader who follows that advice on a moving queue
    destroys a run that is doing the right thing -- which is how one queue that merely LOOKS
    stuck becomes several concurrent 8 GB JVMs on a host that also runs a household's Home
    Assistant. That is the outcome the whole file exists to prevent, reached by following its
    own error message.

    DRIVEN RATHER THAN GREPPED. An assertion that the script CONTAINS the word "CHANGED" would
    pass against a branch that never reaches the line. These run the real `gated` against a real
    `flock` on a real lock, with a real holder, and read what it actually printed.
    """

    def _run_waiter(self, script):
        return subprocess.run(["sh", "-c", script], cwd=ROOT, capture_output=True, text=True,
                              timeout=60)

    def test_a_holder_that_changed_is_reported_as_a_moving_queue(self):
        # The flock is on the DESCRIPTOR and the identity line is the file's CONTENT, so a
        # holder can be simulated by taking the lock and rewriting the text under it. That is
        # exactly what a real handover does: the next `gated` writes its own line in.
        script = r'''
        L=$(mktemp /tmp/gatetest-moving-XXXXXX)
        printf 'pid 111, first holder\n' > "$L"
        # Hold the lock for longer than the waiter's cap, and change the identity mid-wait.
        ( flock 9; sleep 2; printf 'pid 222, second holder\n' > "$L"; sleep 4 ) 9< "$L" &
        sleep 0.5
        . tools/gate.sh
        GATE_LOCK="$L" GATE_WAIT=3 gated true
        echo "EXIT=$?"
        rm -f "$L"
        '''
        out = self._run_waiter(script)
        combined = out.stdout + out.stderr
        self.assertIn("EXIT=75", combined,
                      "the cap must still FAIL rather than wait forever:\n" + combined)
        self.assertIn("holder CHANGED", combined,
                      "a queue that moved while waiting must be reported as moving:\n"
                      + combined)
        self.assertIn("Nothing is wedged", combined, combined)
        self.assertNotIn("before deleting the lock", combined,
                         "a MOVING queue must not be given the delete-the-lock advice, which "
                         "would kill a run that is working correctly:\n" + combined)

    def test_a_holder_that_never_changed_is_reported_as_probably_stuck(self):
        """The control. Without it the test above passes on a script that always says CHANGED."""
        script = r'''
        L=$(mktemp /tmp/gatetest-stuck-XXXXXX)
        printf 'pid 333, the only holder\n' > "$L"
        ( flock 9; sleep 6 ) 9< "$L" &
        sleep 0.5
        . tools/gate.sh
        GATE_LOCK="$L" GATE_WAIT=3 gated true
        echo "EXIT=$?"
        rm -f "$L"
        '''
        out = self._run_waiter(script)
        combined = out.stdout + out.stderr
        self.assertIn("EXIT=75", combined, combined)
        self.assertIn("SAME run held it", combined,
                      "one holder throughout is the case where something really is stuck:\n"
                      + combined)
        self.assertIn("before deleting the lock", combined,
                      "the stuck case is the one where checking and deleting IS the advice:\n"
                      + combined)

    def test_the_default_cap_is_sized_for_a_queue_and_not_for_one_container(self):
        """The number itself, because the old one was derived from the wrong quantity.

        An hour is longer than any single run here, which is what the original comment argued.
        It is NOT longer than a queue of them: measured 2026-08-04 with ten agents sharing this
        host, `check.sh --java` took 16 minutes rather than its documented 3, and four waiters
        were queued at once. Ten of those serialised is over two hours, so an hour fires on a
        healthy host. The floor here is deliberately well above that and below "forever".
        """
        with open(os.path.join(ROOT, GATE), encoding="utf-8") as handle:
            body = handle.read()
        match = re.search(r'GATE_WAIT=\$\{GATE_WAIT:-(\d+)\}', body)
        self.assertIsNotNone(match, "tools/gate.sh no longer declares a GATE_WAIT default")
        seconds = int(match.group(1))
        self.assertGreaterEqual(seconds, 4 * 3600,
                                "a %ds cap fires on a healthy queue of the size this repo "
                                "actually runs; see #214" % seconds)


if __name__ == "__main__":
    unittest.main()
