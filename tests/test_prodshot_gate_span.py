"""Two agents, one shared instance, and the question of whose jar gets booted. #265.

WHY THIS IS DRIVEN AND NOT GREPPED. `test_prodclient_harness` asserts the SHAPE of the fix --
one acquisition, both steps inside it -- and a shape assertion passes against any refactor that
keeps the shape and loses the property. The property is about time: that no other process can
restage the instance between this run's restage and its boot. Nothing static can see that.

So this runs the real `prodshot.sh` and the real `stage-instance.sh` against a fake `docker`,
with a second process hammering the same instance from the side, and asks the only question
that matters: WHICH JAR DID THE CONTAINER SEE. A whole-pack boot is 28 minutes and this needs
none of it -- the property under test is a locking property, and holding the fleet's gate for
half an hour to observe it would be its own kind of wrong.

`harness/shot.sh` has the same shape and the same hazard -- it builds the same shared tag on the
same daemon -- so its test lives here too rather than in a file of its own. The mechanism, the
fake `docker` and the competing agent are identical; only the script under test differs.

THE CONTROL IS THE OLD SHAPE, AND IT IS THE POINT OF THE FILE. `test_the_old_two_acquisition
_shape_boots_the_other_agents_jar` runs the same sandbox against `gated stage; gated boot` --
the code as it was before #265 -- and requires it to FAIL. Without that, "the new code never
booted the wrong jar" is a claim about a harness nobody has ever seen catch anything, which is
the same unearned zero this repository keeps rediscovering.
"""

import os
import shutil
import subprocess
import tempfile
import time
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PRODCLIENT = os.path.join(ROOT, "harness", "prodclient")

# Trials per arm. The new shape is deterministic -- it cannot lose -- so one would do; three
# costs about six seconds and makes a flaky pass visible as a partial one.
TRIALS = 3

# The fake client. `run` is the only interesting verb: it reads the jar that is staged AT BOOT
# TIME, which is exactly what a real client does when Forge scans mods/, and records it.
FAKE_DOCKER = r"""#!/bin/sh
case "$1" in
  # THE IMAGE IS SHARED MUTABLE STATE TOO, and modelling it as a file is the whole point. Every
  # worktree on this host builds the same tag on the same daemon, so "which image did this run
  # boot" is the same question as "which jar did it boot" and has the same answer when the gate
  # is released between the build and the run.
  build) printf '%s' "${FAKE_IMAGE_TAG:-A}" > "$FAKE_IMAGE" ; exit 0 ;;
  # `docker rm -f` is what prodshot.sh's signal handler reaches for, and stopping the container
  # is how a killed run gives the gate back instead of parking the fleet behind a boot nobody
  # is reading. Faked as a stop file the run below is watching for, which is what stopping a
  # real container amounts to from `docker run`'s point of view.
  rm) : > "$FAKE_STOP" ; exit 0 ;;
  run)
    printf 'BOOT %s%s\n' "$(cat "$FAKE_INSTANCE/mods/mc-recipe-dump-0.0.0.jar" 2>/dev/null \
        || echo '-')" "$(cat "$FAKE_IMAGE" 2>/dev/null || echo '?')" >> "$FAKE_EVENTS"
    # `shot.sh` refuses to report success without a PNG, and it is right to: a run that leaves
    # the previous screenshot on disk is the stale-picture failure its own comments describe.
    [ -n "${FAKE_SHOT_PNG:-}" ] && printf 'PNG' > "$FAKE_SHOT_PNG"
    # Long enough that a competitor hammering the gate gets several attempts DURING the boot,
    # so "the new shape never lost" is a statement about a contended run and not a quiet one.
    waited=0
    while [ "$waited" -lt "${FAKE_BOOT_TICKS:-8}" ]; do
        [ -f "$FAKE_STOP" ] && exit 0
        waited=$(( waited + 1 ))
        sleep 0.05
    done
    exit "${FAKE_RUN_EXIT:-0}" ;;
esac
exit 0
"""

# Another agent, restaging the shared instance as fast as the gate lets it. It runs the REAL
# staging script, so it takes the gate the way a real one does; the only thing faked is which
# jar it installs.
COMPETITOR = r"""#!/bin/sh
. "$ROOT/tools/gate.sh"
# Shaped like a real `prodshot.sh` run rather than like a bare restage: it builds the image and
# reinstalls the jar under ONE acquisition, which is what the other agents on this host are
# doing while our run is in flight.
competitor_step() {
    FAKE_IMAGE_TAG=B docker build -q -t fake . >/dev/null
    MOD_JAR="$JAR_B" GATE_LOCK= "$STAGE" --mod-only >/dev/null 2>&1
}
while [ ! -f "$STOP" ]; do
    gated competitor_step && printf 'RESTAGE\n' >> "$FAKE_EVENTS"
done
"""

# The code as it was before #265: the gate taken around the restage, RELEASED, then taken again
# around the boot. `env GATE_LOCK=` reproduces the old `stage-instance.sh`, which took no gate
# of its own because its caller had just taken one for it.
OLD_SHAPE = r"""#!/bin/sh
set -e
. "$ROOT/tools/gate.sh"
docker build -q -t fake . >/dev/null
gated env GATE_LOCK= "$STAGE" --mod-only >/dev/null
gated docker run --rm fake
"""


# `gated` says out loud that it is NOT serialising when flock is missing, and both arms below
# would then boot the wrong jar -- a red that means "this host cannot run this test", not "the
# fix is broken". `tools/gate.sh` treats the same absence as a loud warning rather than a
# failure, for the same reason.
NEEDS_FLOCK = unittest.skipUnless(shutil.which("flock"),
                                  "no flock on this host, so nothing here is serialised")


@NEEDS_FLOCK
class ProdshotHoldsTheGateAcrossTheRestageTest(unittest.TestCase):

    def _sandbox(self):
        """A build tree shaped like `/coding/.recipegraph-build`, and two jars to fight over."""
        tmp = tempfile.mkdtemp(prefix="mrg-i265-")
        self.addCleanup(shutil.rmtree, tmp, ignore_errors=True)

        build = os.path.join(tmp, "build")
        instance = os.path.join(build, "prodinstance")
        os.makedirs(os.path.join(instance, "mods"))
        os.makedirs(os.path.join(build, "prodclient"))
        with open(os.path.join(build, "prodclient", "classpath.txt"), "w") as handle:
            handle.write("stub\n")
        with open(os.path.join(build, "prodclient", "launch.json"), "w") as handle:
            handle.write('{"assetObjectsSource": "/coding/objects"}\n')

        # Same basename, different contents: `install_mod` copies the source's basename in, so
        # the instance always holds one file and the CONTENT is the only tell -- which is the
        # real situation, where two agents' jars differ by a few hundred bytes and the filename
        # is identical.
        jars = {}
        for who in ("A", "B"):
            directory = os.path.join(tmp, "jar" + who)
            os.makedirs(directory)
            path = os.path.join(directory, "mc-recipe-dump-0.0.0.jar")
            with open(path, "w") as handle:
                handle.write(who)
            jars[who] = path

        binaries = os.path.join(tmp, "bin")
        os.makedirs(binaries)
        self._script(os.path.join(binaries, "docker"), FAKE_DOCKER)

        return {
            "tmp": tmp, "build": build, "instance": instance, "bin": binaries,
            "jar_a": jars["A"], "jar_b": jars["B"],
            "events": os.path.join(tmp, "events"),
            "stop": os.path.join(tmp, "stop"),
            "fake_stop": os.path.join(tmp, "container-stopped"),
            "image": os.path.join(tmp, "image-tag"),
            # A LOCK OF OUR OWN, in /tmp. Never the default: this test would otherwise queue
            # behind whatever 28-minute client the fleet is running, and worse, hold the real
            # gate against it. /tmp because `flock -w` cannot time out on a share -- the
            # measurement `tools/gate.sh` opens with.
            "lock": os.path.join(tmp, "gate.lock"),
        }

    def _script(self, path, body):
        with open(path, "w") as handle:
            handle.write(body)
        os.chmod(path, 0o755)

    def _environment(self, box):
        env = dict(os.environ)
        env.update({
            "PATH": box["bin"] + os.pathsep + os.environ["PATH"],
            "PRODSHOT_STALLWATCH": "0",
            "LOCAL_BUILD": box["build"],
            "HOST_BUILD": box["build"],
            "MOD_JAR": box["jar_a"],
            "GATE_LOCK": box["lock"],
            # Seconds, not the six-hour default: a wedge here has to fail the test rather than
            # hang the suite until somebody notices.
            "GATE_WAIT": "30",
            "FAKE_INSTANCE": box["instance"],
            "FAKE_EVENTS": box["events"],
            "FAKE_STOP": box["fake_stop"],
            "FAKE_IMAGE": box["image"],
            "ROOT": ROOT,
            "STAGE": os.path.join(PRODCLIENT, "stage-instance.sh"),
            "STOP": box["stop"],
            "JAR_B": box["jar_b"],
        })
        return env

    def _trial(self, command):
        """One contended run. Returns (jar the boot saw, number of competitor restages)."""
        box = self._sandbox()
        env = self._environment(box)
        open(box["events"], "w").close()

        self._script(os.path.join(box["tmp"], "competitor.sh"), COMPETITOR)
        competitor = subprocess.Popen(["sh", os.path.join(box["tmp"], "competitor.sh")],
                                      env=env, stdout=subprocess.DEVNULL,
                                      stderr=subprocess.DEVNULL)
        try:
            result = subprocess.run(command, env=env, cwd=box["build"], timeout=120,
                                    capture_output=True, text=True)
        finally:
            open(box["stop"], "w").close()
            competitor.wait(timeout=60)

        self.assertEqual(0, result.returncode,
                         "the run under test failed outright:\n%s\n%s"
                         % (result.stdout, result.stderr))

        with open(box["events"]) as handle:
            events = handle.read().split()
        boots = [events[i + 1] for i, word in enumerate(events) if word == "BOOT"]
        self.assertEqual(1, len(boots), "expected exactly one boot, got %r" % (events,))
        return boots[0], events.count("RESTAGE")

    def _assert_own_work(self, booted, trial):
        """`booted` is the jar and then the image the container actually saw."""
        self.assertEqual("AA", booted,
                         "trial %d booted %s: %s. The gate was released somewhere between this "
                         "run's own build, its restage, and its boot. #265."
                         % (trial, booted,
                            {"BA": "the other agent's JAR",
                             "AB": "the other agent's IMAGE",
                             "BB": "the other agent's jar AND image"}.get(booted, "something "
                                                                          "unexpected")))

    def test_the_boot_sees_the_jar_this_run_staged(self):
        """The property, under contention: nobody can restage between our restage and our boot.

        The second number is the control on the CONTENDER, and it is not decoration. If the
        competitor never managed to restage at all -- a wrong path, a jar that does not exist,
        a script that exits immediately -- this test would pass without a second agent ever
        having existed, which is the shape of every unearned zero in this repository.
        """
        for trial in range(TRIALS):
            booted, restages = self._trial(
                ["sh", os.path.join(PRODCLIENT, "prodshot.sh")])
            self._assert_own_work(booted, trial)
            self.assertGreater(restages, 0,
                               "trial %d saw no competing restage at all, so it proves "
                               "nothing about concurrency" % trial)

    def test_the_old_two_acquisition_shape_boots_the_other_agents_jar(self):
        """The witness. Revert the BEHAVIOUR -- release the gate between the two steps -- and
        the harness above must go red, or it was never measuring anything.

        Not asserted as "every trial fails", because losing the race is a race: the competitor
        has to be queued at the instant the first acquisition is released. It is enough, and it
        is the honest claim, that the old shape CAN lose where the new one CANNOT.
        """
        box_script = None
        losses = 0
        for _trial in range(TRIALS):
            tmp = tempfile.mkdtemp(prefix="mrg-i265-old-")
            self.addCleanup(shutil.rmtree, tmp, ignore_errors=True)
            box_script = os.path.join(tmp, "old-prodshot.sh")
            self._script(box_script, OLD_SHAPE)
            booted, restages = self._trial(["sh", box_script])
            self.assertGreater(restages, 0, "the competitor never ran")
            if booted != "AA":
                losses += 1
        self.assertGreater(losses, 0,
                           "the old two-acquisition shape never booted the wrong jar in %d "
                           "trials, so this file cannot show that the new one prevents it. "
                           "That makes every other assertion here an unearned zero -- fix the "
                           "harness, do not delete the control." % TRIALS)


@NEEDS_FLOCK
class ShotHoldsTheGateAcrossItsImageBuildTest(unittest.TestCase):
    """`harness/shot.sh` has no instance to lose, and it can still render the wrong picture.

    It builds `mcrecipedump-shot:latest` on the daemon every worktree on this host shares, and
    a branch is allowed to change `harness/Dockerfile` -- that is the stated reason it rebuilds
    every run. With the build outside the gate, another agent's build replaces the tag between
    ours and our `docker run`, and the screenshot comes out of THEIR image. The file's own
    comment calls a stale image "the worst way to lose an afternoon, because the screenshot
    still appears and still looks plausible", which is the same sentence with a different
    subject.

    Driven, because this is the script the fleet runs every few minutes for GUI iteration and
    #265 restructured its centre. It also serves as the smoke test that it still works at all.
    """

    def setUp(self):
        self.helper = ProdshotHoldsTheGateAcrossTheRestageTest("_trial")
        self.box = self.helper._sandbox()
        self.addCleanup(self.helper.doCleanups)
        self.env = self.helper._environment(self.box)
        # What `shot.sh` needs and `prodshot.sh` does not: a dev-mod directory, and a `docker
        # run` that leaves a PNG behind, since it refuses to report OK without one.
        os.makedirs(os.path.join(self.box["build"], "deps"))
        self.env["FAKE_SHOT_PNG"] = os.path.join(self.box["build"], "shots", "fixture.png")
        self.env["HOST_REPO"] = ROOT
        open(self.box["events"], "w").close()

    def test_the_shot_is_rendered_by_the_image_this_run_built(self):
        competitor = os.path.join(self.box["tmp"], "competitor.sh")
        self.helper._script(competitor, COMPETITOR)
        racing = subprocess.Popen(["sh", competitor], env=self.env,
                                  stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        try:
            result = subprocess.run(["sh", os.path.join(ROOT, "harness", "shot.sh"), "fixture"],
                                    env=self.env, cwd=self.box["build"], timeout=120,
                                    capture_output=True, text=True)
        finally:
            open(self.box["stop"], "w").close()
            racing.wait(timeout=60)

        self.assertEqual(0, result.returncode,
                         "shot.sh failed outright:\n%s\n%s" % (result.stdout, result.stderr))
        self.assertIn("shot.sh: OK in", result.stdout,
                      "shot.sh no longer reports its own success:\n%s" % result.stdout)
        with open(self.box["events"]) as handle:
            events = handle.read().split()
        booted = [events[i + 1] for i, word in enumerate(events) if word == "BOOT"]
        self.assertEqual(1, len(booted), "expected exactly one run, got %r" % (events,))
        self.assertTrue(booted[0].endswith("A"),
                        "the shot was rendered by the OTHER agent's image: the gate was "
                        "released between this run's build and its container. #265.")
        self.assertGreater(events.count("RESTAGE"), 0,
                           "no competing agent ran at all, so this proves nothing")


@NEEDS_FLOCK
class TheGateIsGivenBackOnEveryExitPathTest(unittest.TestCase):
    """A gate that leaks is worse than one released too early: it wedges every agent on this
    host for GATE_WAIT, which is six hours.

    #265 lengthens the critical section from "the boot" to "the restage and the boot", so the
    number of ways to leave it went up, and each one is walked here rather than argued about.
    The structural answer is that `flock` is held by an open file descriptor and the kernel
    drops it when the process holding it dies, however it dies -- but "the lock is released"
    and "the fleet gets moving again" are different claims when the dead process is the READER
    and the container it started is still running. That is the one the signal handler answers.
    """

    def setUp(self):
        self.helper = ProdshotHoldsTheGateAcrossTheRestageTest("_trial")
        self.box = self.helper._sandbox()
        self.addCleanup(self.helper.doCleanups)
        self.env = self.helper._environment(self.box)
        open(self.box["events"], "w").close()

    def _gate_is_free(self):
        held = subprocess.run(["flock", "-n", self.box["lock"], "-c", "true"])
        return held.returncode == 0

    def _wait_for_free_gate(self, limit):
        deadline = time.time() + limit
        while time.time() < deadline:
            if self._gate_is_free():
                return time.time() - (deadline - limit)
            time.sleep(0.05)
        return None

    def test_the_gate_is_free_after_the_client_fails(self):
        self.env["FAKE_RUN_EXIT"] = "1"
        result = subprocess.run(["sh", os.path.join(PRODCLIENT, "prodshot.sh")],
                                env=self.env, cwd=self.box["build"], timeout=60,
                                capture_output=True, text=True)
        self.assertEqual(1, result.returncode, result.stderr)
        self.assertIn("FAILED after", result.stderr)
        self.assertTrue(self._gate_is_free(), "a failed boot did not release the gate")

    def test_the_gate_is_free_after_the_restage_fails(self):
        """The new path, and the one worth walking: staging now runs INSIDE the acquisition,
        so a jar that is not there returns from the middle of the critical section."""
        self.env["MOD_JAR"] = os.path.join(self.box["tmp"], "no-such-jar.jar")
        result = subprocess.run(["sh", os.path.join(PRODCLIENT, "prodshot.sh")],
                                env=self.env, cwd=self.box["build"], timeout=60,
                                capture_output=True, text=True)
        self.assertEqual(90, result.returncode, result.stderr)
        self.assertIn("STAGING FAILED", result.stderr,
                      "a staging failure must not read as a client failure")
        self.assertTrue(self._gate_is_free(), "a failed restage did not release the gate")
        with open(self.box["events"]) as handle:
            self.assertNotIn("BOOT", handle.read(),
                             "the client was launched against a jar that was never installed")

    def test_a_killed_run_gives_the_gate_back_instead_of_parking_the_fleet(self):
        """The #228 shape: the wrapper dies and the reader is gone, but the container is not.

        Without the handler the gate stays held for the rest of the boot -- 28 minutes of every
        other agent queueing behind a run whose output nobody will ever read, and no line
        anywhere saying that is what is happening. The assertion is that the gate comes back in
        a fraction of the remaining boot, not merely that it comes back eventually.
        """
        self.env["FAKE_BOOT_TICKS"] = "200"          # 10s of "boot" left to give back
        run = subprocess.Popen(["sh", os.path.join(PRODCLIENT, "prodshot.sh")],
                               env=self.env, cwd=self.box["build"],
                               stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        deadline = time.time() + 30
        while time.time() < deadline:
            with open(self.box["events"]) as handle:
                if "BOOT" in handle.read():
                    break
            time.sleep(0.05)
        else:
            run.kill()
            self.fail("the fake client never booted")

        self.assertFalse(self._gate_is_free(),
                         "the control: while the boot is running the gate must be HELD, or "
                         "this test would pass without the handler existing")
        killed_at = time.time()
        run.terminate()
        _out, err = run.communicate(timeout=30)
        self.assertIn("KILLED BY SIGTERM", err,
                      "a killed run must say so; #228 spent a boot reading that silence as a "
                      "pack failure because nothing crashed and nothing wrote a report")
        freed = self._wait_for_free_gate(5)
        self.assertIsNotNone(freed,
                             "a killed run left the gate held with 10s of boot still to go")
        # THE NUMBER, not just the eventual freeing. Ten seconds of fake boot were left; if the
        # gate only comes back when that finishes, the handler did nothing and this test would
        # still pass on eventual-freeing alone. It did pass that way once, before the handler
        # was reachable at all -- a foreground `gated` defers the trap until the run is over.
        self.assertLess(time.time() - killed_at, 5,
                        "the gate came back only when the boot ended on its own, so the "
                        "handler is inert")
        # AND THE CONTAINER WAS ASKED TO STOP, asserted separately because in this sandbox the
        # gate would come back without it: the fake client is a CHILD of the run, so the tree
        # kill takes it down too. A real container is owned by the docker daemon and survives
        # its CLI being killed, so `docker rm -f` is the only thing standing between a killed
        # run and a 7g JVM left running on the host that also runs the household. Nothing else
        # here can see that difference, which is exactly why it is asserted directly.
        self.assertTrue(os.path.exists(self.box["fake_stop"]),
                        "the handler released the gate but never told docker to remove the "
                        "container")

    def test_a_run_killed_while_it_is_still_QUEUED_never_boots(self):
        """The other half of the same problem, and the one `docker rm -f` cannot answer.

        A run that is waiting on the gate has no container to remove. Left alone, its gated
        subshell wakes up when the queue reaches it -- minutes or hours after the reader died
        -- and boots a 7g client nobody will ever look at, holding the gate for the whole boot.
        """
        holder = subprocess.Popen(
            ["flock", self.box["lock"], "-c", "sleep 20"])
        self.addCleanup(holder.kill)
        time.sleep(0.5)

        run = subprocess.Popen(["sh", os.path.join(PRODCLIENT, "prodshot.sh")],
                               env=self.env, cwd=self.box["build"],
                               stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        time.sleep(1.0)
        with open(self.box["events"]) as handle:
            self.assertNotIn("BOOT", handle.read(),
                             "the control: this run must still be QUEUED at this point, or the "
                             "test below is not testing the queued case at all")
        run.terminate()
        _out, err = run.communicate(timeout=30)
        self.assertIn("KILLED BY SIGTERM", err)

        holder.kill()
        holder.wait(timeout=10)
        # Well past the point where the queued subshell would have been granted the gate.
        time.sleep(2)
        with open(self.box["events"]) as handle:
            self.assertNotIn("BOOT", handle.read(),
                             "a run whose reader was killed while queued still booted the pack "
                             "when its turn came, and held the gate for all of it")


if __name__ == "__main__":
    unittest.main()
