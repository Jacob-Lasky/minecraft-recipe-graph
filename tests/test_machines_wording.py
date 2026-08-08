"""The machine-state words have to be the same in the browser and in game.

The mirror of `test_ranking.CrossLanguageWordingTest`, which pins `NodeRowText.meta` against
`present.INTERCHANGEABLE_NOTE`, and it exists for the reason that one records: #181 shipped a
mark in Java only, so the browser and the client spent a release saying two different things
about one node -- one of them nothing at all. A comment asking for parity is a rule with
nothing enforcing it, which is this repository's recurring defect.

WHY IT MATTERS MORE HERE THAN FOR MOST STRINGS. Two of the four display names are deliberately
NOT the state names: `unknown` is shown as "unidentified" and `unavailable` as "no route",
because the wire names are claims the tool cannot make. `MachineInfo`'s own class note is that
folding `unknown` into `unavailable` mispriced 40% of the graph, and showing them under names
that suggest the same fold is the display half of that mistake. A rename on one side only would
undo it silently on the other.

THIS IS THE #19 PHASE 6 TRIPWIRE TOO. The in-game machines table replaces `machines_page`, and
until that page is deleted the two are live at once. This is what stops them drifting in the
interval.
"""

import os
import re
import unittest

from recipegraph import machines, present


class MachineLabelParityTest(unittest.TestCase):

    JAVA = os.path.join(
        os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
        "mod", "src", "main", "java", "io", "github", "jacoblasky",
        "recipedump", "client", "machines", "MachineLabels.java")

    def java_source(self):
        # FAIL RATHER THAN SKIP when the file is absent, which is the half that matters: a
        # skip in a parity test reads exactly like a pass, and the whole subject of this
        # module is rules with nothing enforcing them.
        self.assertTrue(os.path.exists(self.JAVA),
                        "the Java copy of these labels lives here: %s" % self.JAVA)
        with open(self.JAVA, encoding="utf-8") as fh:
            return fh.read()

    def java_labels(self):
        source = self.java_source()
        found = re.search(r"String\[\]\s+LABELS\s*=\s*\{([^}]*)\}", source)
        self.assertTrue(found, "MachineLabels.java declares no LABELS array")
        return re.findall(r'"((?:[^"\\]|\\.)*)"', found.group(1))

    def test_the_four_labels_are_the_same_words_in_the_same_order(self):
        """`present.STATE_LABEL` read out of Java, in `machines.STATES` order.

        ORDER IS ASSERTED AND NOT JUST MEMBERSHIP. The Java array is indexed by the
        `MachineInfo` state constants, and those constants also index `Cost.MACHINE_COST` --
        `MachineInfo` calls that "load-bearing twice". A reordering there is already a
        repricing, and this makes it a failure here as well rather than a silent relabel.
        """
        expected = [present.STATE_LABEL[state] for state in machines.STATES]
        self.assertEqual(expected, self.java_labels())

    def test_two_of_the_labels_deliberately_differ_from_the_state_names(self):
        """The guard on the guard.

        Without this, someone "simplifying" both sides to use the raw state names would leave
        the test above green while undoing the distinction it exists to protect. These two
        differences are the point rather than an inconsistency to be tidied away.
        """
        self.assertEqual("unidentified", present.STATE_LABEL[machines.UNKNOWN])
        self.assertEqual("no route", present.STATE_LABEL[machines.UNAVAILABLE])
        self.assertNotEqual(machines.UNKNOWN, present.STATE_LABEL[machines.UNKNOWN])
        self.assertNotEqual(machines.UNAVAILABLE, present.STATE_LABEL[machines.UNAVAILABLE])

    def test_the_java_side_declares_one_colour_per_label(self):
        """A state with a label and no colour draws in whatever the previous one used.

        `MachineLabels.COLOURS` is parallel to `LABELS` and indexed by the same constant, so a
        fifth label added without a fifth colour is an AIOOBE inside a panel build -- which
        `WidgetTree.resizeInternal` swallows, leaving the screen at 0x0 with no message.
        """
        source = self.java_source()
        found = re.search(r"int\[\]\s+COLOURS\s*=\s*\{([^}]*)\}", source)
        self.assertTrue(found, "MachineLabels.java declares no COLOURS array")
        colours = [c for c in found.group(1).split(",") if c.strip()]
        self.assertEqual(len(self.java_labels()), len(colours))


class MachineTablePortParityTest(unittest.TestCase):
    """The orderings the in-game table ports, checked against the Python they came from.

    NOT A REIMPLEMENTATION TEST. These assert that the PYTHON rules the Java port was written
    against still say what the port assumes -- so if someone changes `mod_order`'s key or
    `STATE_RANK`'s derivation, this fails and names the Java file that has to move with it,
    instead of the two quietly disagreeing until somebody compares two screens by eye.
    """

    def test_the_state_rank_is_still_the_state_order_itself(self):
        """`MachineTable.ROW_ORDER` sorts on the state constant and nothing else.

        It can do that only because `present.STATE_RANK` is derived by enumerating
        `machines.STATES`, so the row rank and the cost-band index are one number on both
        sides. A hand-written rank map in `present.py` would break that assumption in a way no
        Java test could see.
        """
        self.assertEqual({state: i for i, state in enumerate(machines.STATES)},
                         present.STATE_RANK)

    def test_mod_order_still_sorts_by_count_then_by_a_case_folded_name(self):
        """The tie-break the Java port copies, exercised rather than read.

        `aether_legacy` belongs next to `Advent of Ascension`, not after `Woot`. Java has no
        `casefold`, so `MachineTable.modOrder` uses `Locale.ROOT` lowercase and says in a
        comment where the two differ; this is the assertion that the Python side still behaves
        the way that comment claims.
        """
        counts = {
            "Woot": {machines.HAVE: 1},
            "aether_legacy": {machines.HAVE: 1},
            "Advent of Ascension": {machines.HAVE: 1},
            "Modular Machinery": {machines.HAVE: 5},
        }
        self.assertEqual(
            ["Modular Machinery", "Advent of Ascension", "aether_legacy", "Woot"],
            machines.mod_order(counts))


if __name__ == "__main__":
    unittest.main()
