"""The production-client harness is four shell scripts that agree with each other by hand.

WHY THIS FILE EXISTS. `harness/prodclient/` boots the whole pack in a real obfuscated Forge
client, and every run of it costs about twenty-two minutes. Nothing in it can be unit tested in
the ordinary sense, so the failures worth catching are the ones that make a run silently do the
wrong thing rather than fail: a setting whose two declarations have drifted apart, a system
property that nothing reads, a memory cap raised past what the host can survive. Each of those
produces a run that finishes and lies.

This is the same trick `ShotHarnessTest` plays on `mod/build.gradle`: read the script as text and
assert the contract it is supposed to hold up. It is not a substitute for running the harness. It
is a guard on the parts of the harness that a run would not tell you about.
"""

import os
import re
import unittest

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PRODCLIENT = os.path.join(REPO, "harness", "prodclient")
MOD_SOURCE = os.path.join(REPO, "mod", "src", "main", "java")


def read(name):
    with open(os.path.join(PRODCLIENT, name), encoding="utf-8") as handle:
        return handle.read()


def strip_comments(text):
    """Shell comments, removed, because a script that EXPLAINS a setting mentions it too.

    Not cosmetic. The first version of `test_every_property_it_passes...` scanned the raw text and
    failed on `-Dmcrecipedump.shotWidth` appearing inside the comment that exists to say the
    property is deliberately NOT passed. A test that cannot tell code from the prose about the
    code reports the opposite of the truth, and the fix it demands is deleting the explanation.
    """
    return re.sub(r"(?m)^\s*#.*$", "", text)


def shell_default(text, var):
    """The default in `${VAR:-value}`, wherever it appears.

    Matched on the EXPANSION and not on the assignment's left-hand side, because the two are
    allowed to differ and do: `launch.sh` says `HEAP="${CLIENT_HEAP:-5G}"`, reading the same knob
    `prodshot.sh` exports under its own name. Keying off the left-hand side found nothing there
    and silently skipped the comparison, which is how the 4G-against-5G drift this file now
    catches survived being written.
    """
    match = re.search(r'\$\{' + re.escape(var) + r':-([^}]*)\}', strip_comments(text))
    return match.group(1) if match else None


class ProdClientHarnessTest(unittest.TestCase):

    def test_the_two_scripts_agree_on_every_shared_default(self):
        """`launch.sh` and `prodshot.sh` both declare these, and disagreement is invisible.

        `launch.sh` needs its own defaults because it can be run by hand against a container
        started some other way, so the duplication cannot simply be deleted. What it can be is
        checked. If they drift, the value that takes effect depends on which script started the
        run, and NOTHING in the output says which one won: the screenshot is just the wrong size,
        or the heap is quietly smaller than the number in the log line.
        """
        launch, prodshot = read("launch.sh"), read("prodshot.sh")
        for var in ("SHOT_WIDTH", "SHOT_HEIGHT", "CLIENT_HEAP"):
            in_launch = shell_default(launch, var)
            in_prodshot = shell_default(prodshot, var)
            self.assertIsNotNone(in_launch, f"launch.sh no longer declares {var}")
            self.assertIsNotNone(in_prodshot, f"prodshot.sh no longer declares {var}")
            self.assertEqual(in_launch, in_prodshot,
                             f"{var} differs: launch.sh says {in_launch!r}, "
                             f"prodshot.sh says {in_prodshot!r}")

    def test_the_container_cap_stays_under_the_host_ceiling(self):
        """8g is not a style preference. Tower runs the household's Home Assistant and its
        doorbell in sibling containers, and an OOM in a client this size takes them with it."""
        cap = shell_default(read("prodshot.sh"), "MEMORY")
        self.assertIsNotNone(cap, "prodshot.sh no longer declares MEMORY")
        match = re.fullmatch(r"(\d+)g", cap)
        self.assertIsNotNone(match, f"MEMORY default {cap!r} is not in whole gigabytes")
        self.assertLessEqual(int(match.group(1)), 8,
                             "the container cap must stay at or under 8g; see the comment above "
                             "MEMORY in prodshot.sh for what an OOM here costs")

    def test_the_heap_fits_inside_the_container(self):
        """A heap ceiling at or above the cgroup limit is an OOM kill waiting for the peak, and a
        cgroup kill looks exactly like a `docker kill` from outside: exit 137, no crash report."""
        prodshot = read("prodshot.sh")
        cap = int(re.fullmatch(r"(\d+)g", shell_default(prodshot, "MEMORY")).group(1))
        heap = int(re.fullmatch(r"(\d+)G", shell_default(prodshot, "CLIENT_HEAP")).group(1))
        self.assertLess(heap, cap,
                        f"heap {heap}G leaves no room for the JVM's non-heap memory inside a "
                        f"{cap}g container; a measured boot needed about 0.7 GiB beyond the heap")

    def test_every_property_it_passes_is_one_the_mod_actually_reads(self):
        """A `-D` nobody reads is worse than a missing one: it looks configured and does nothing.

        This caught a real instance. `prodshot.sh` was originally copied from `shot.sh` and so
        passed `mcrecipedump.shotWidth` and `mcrecipedump.shotHeight`, which no Java code reads at
        all: `mod/build.gradle` consumes that pair and turns it into Minecraft's `--width` and
        `--height` game arguments. Under Gradle they work; in a launch with no Gradle they are two
        properties into the void, and the window silently keeps its default size.
        """
        passed = set(re.findall(r"-Dmcrecipedump\.([A-Za-z]+)",
                                strip_comments(read("prodshot.sh"))))
        self.assertTrue(passed, "prodshot.sh passes no mcrecipedump properties at all")

        declared = set()
        for root, _dirs, files in os.walk(MOD_SOURCE):
            for name in files:
                if not name.endswith(".java"):
                    continue
                with open(os.path.join(root, name), encoding="utf-8") as handle:
                    declared.update(re.findall(r'"mcrecipedump\.([A-Za-z]+)"', handle.read()))

        unread = passed - declared
        self.assertFalse(unread,
                         f"prodshot.sh passes -Dmcrecipedump.{{{','.join(sorted(unread))}}} "
                         "but no Java source names them; see the shotWidth/shotHeight note above")

    def test_the_window_size_reaches_the_game_as_game_arguments(self):
        """The counterpart to the test above: having established that the properties are inert,
        something still has to size the window, and it is `launch.sh`'s `--width`/`--height`."""
        launch = read("launch.sh")
        self.assertIn('--width "$SHOT_WIDTH"', launch)
        self.assertIn('--height "$SHOT_HEIGHT"', launch)

    def test_it_rebuilds_the_image_rather_than_trusting_a_cached_one(self):
        """`harness/shot.sh` builds unconditionally on every run and says why: an edit to the
        Dockerfile otherwise keeps rendering against the old libraries with no sign that it has,
        and the screenshot still appears and still looks plausible. The same trap applies here and
        the stakes are higher, because a stale image here can be the wrong JVM."""
        # `assertTrue(re.search(...))` and NOT `assertRegex`, which prints the whole haystack on
        # failure. These files are 170 lines of load-bearing comments, so a failure message
        # carrying the entire script buries the one sentence saying what is wrong.
        found = re.search(r"docker build[^\n]*-t \"?\$IMAGE", read("prodshot.sh"))
        self.assertTrue(found,
                        "prodshot.sh must build its image before running, as shot.sh does")

    def test_it_reinstalls_the_mod_jar_rather_than_trusting_a_staged_one(self):
        """The same trap as the image, one layer in, and worse.

        `prodshot.sh` rebuilt its image every run and left the JAR UNDER TEST alone, so a shot
        measured whatever `stage-instance.sh` last happened to install. On a branch that changes
        Java that is a 22-minute run photographing the OLD behaviour, with nothing in the log to
        say which jar it was. It has to be `--mod-only`: re-staging the whole instance copies 377
        jars out of the AMP server and takes the container gate, and a gated script calling
        another one is the deadlock `tools/gate.sh` warns about in its header."""
        prodshot = strip_comments(read("prodshot.sh"))
        # Loose about what sits between, because the path is `$(dirname "$0")/...` and the
        # nested quotes inside it defeat any character class written to exclude them.
        found = re.search(r"gated .*stage-instance\.sh.* --mod-only", prodshot)
        self.assertTrue(found,
                        "prodshot.sh must reinstall the mod jar before running, and take the"
                        " gate to do it: build-jar.sh holds the gate while gradle rewrites the"
                        " jar being copied")
        self.assertLess(prodshot.index("--mod-only"), prodshot.index("gated docker run"),
                        "the reinstall must finish before the run's own gate is taken;"
                        " nesting flock deadlocks")
        staging = strip_comments(read("stage-instance.sh"))
        self.assertIn("--mod-only", staging,
                      "stage-instance.sh must accept the flag prodshot.sh passes it")
        body = re.search(r"install_mod\(\) \{(.*?)\n\}", staging, re.S)
        self.assertTrue(body, "stage-instance.sh must define install_mod")
        self.assertNotIn("gated", body.group(1),
                         "install_mod must take no container, or prodshot.sh deadlocks on it")

    def test_the_watchdog_waits_for_the_container_before_watching_it(self):
        """The watchdog is started before `docker run`, and `docker run` blocks on the container
        gate. Without a wait-to-appear loop the first poll finds nothing and the watchdog exits,
        so every gated run, which is to say every run that had to queue, goes unwatched.

        MEASURED rather than reasoned: a real dump run had zero `stallwatch.sh` processes alive
        while its client was booting. Same shape as the `GATE_WAIT` defect in `tools/gate.sh`,
        a guard correct in the case it was tried in and inert in the case it exists for.
        """
        self.assertIn("until docker ps", read("stallwatch.sh"),
                      "stallwatch.sh must wait for the container to appear before watching it")

    def test_the_assembled_classpath_is_relative(self):
        """The tree is assembled on one filesystem and mounted somewhere else in the container. A
        JVM given a classpath entry that does not exist says nothing at all; it fails later as
        `Could not find or load main class net.minecraft.launchwrapper.Launch`, which names the
        class and not the reason. Cost one full run to find."""
        with open(os.path.join(PRODCLIENT, "assemble.py"), encoding="utf-8") as handle:
            assemble = handle.read()
        self.assertIn("os.path.relpath", assemble,
                      "assemble.py must write classpath entries relative to the tree root")

    def test_the_staging_script_disables_the_dialog_that_blocks_a_headless_boot(self):
        """Modpack Config Checker opens a Swing `JOptionPane` from preInit when the heap is under
        the pack's recommended 7000 MB, and the client thread then waits forever for a click that
        cannot come. It raises no error and trips no timeout: the log simply stops mid-sentence
        with one core busy, which is indistinguishable from a slow boot.

        Re-applying this is not optional bookkeeping, because every `cp -a` of the pack's config
        puts the setting back.
        """
        stage = read("stage-instance.sh")
        self.assertIn("concheckrmd.cfg", stage)
        self.assertIn("Check RAM meets recommendation", stage)


if __name__ == "__main__":
    unittest.main()
