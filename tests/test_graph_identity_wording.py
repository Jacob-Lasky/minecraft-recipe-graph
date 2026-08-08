"""The graph's identity fields have to be spelled the same in both languages.

`model.Graph.save` writes `dump_mod_digest` and `dump_mod_count`; `GraphJsonReader` reads
them so the in-game Graph screen can answer "is this graph for the pack I am running" (#255).
Nothing but this joins the two spellings.

WHY THIS ONE IS WORSE THAN AN ORDINARY FIELD RENAME. `GraphJsonReader`'s unknown-field branch
SKIPS silently -- deliberately, so the dump format can grow without a reader refusing to load a
graph carrying a field it does not use. That is the right default and it is exactly what makes
a rename here invisible: the Java side would simply never see a digest, `GraphFacts.checkAgainst`
would answer CANNOT_TELL, and the screen would draw "pack: UNCHECKED". A reader glancing at it
sees a tool that looked and found nothing wrong. The failure is silent, and it is silent in the
direction that reads as reassurance.

The mirror of `test_machines_wording.py`, and it FAILS rather than skips for the same reason: a
skip in a parity test reads exactly like a pass.
"""

import os
import re
import unittest

from recipegraph import model


class GraphIdentityFieldParityTest(unittest.TestCase):

    ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    READER = os.path.join(ROOT, "mod", "src", "main", "java", "io", "github", "jacoblasky",
                          "recipedump", "graph", "GraphJsonReader.java")
    MODEL = os.path.join(ROOT, "recipegraph", "model.py")

    #: The fields the in-game pack check is built from. Both halves must agree on both names.
    IDENTITY_FIELDS = ("dump_mod_digest", "dump_mod_count")

    def java_source(self):
        self.assertTrue(os.path.exists(self.READER),
                        "the Java reader lives here: %s" % self.READER)
        with open(self.READER, encoding="utf-8") as fh:
            return fh.read()

    def python_source(self):
        with open(self.MODEL, encoding="utf-8") as fh:
            return fh.read()

    def test_python_writes_both_identity_fields(self):
        """`Graph.save` emits them, so there is something for the mod to read."""
        source = self.python_source()
        for field in self.IDENTITY_FIELDS:
            self.assertTrue(re.search(r'"%s":' % re.escape(field), source),
                            "model.py no longer writes %r" % field)

    def test_the_java_reader_reads_both_identity_fields(self):
        """And the reader names them with exactly those strings.

        MATCHED AGAINST `field.equals("...")` RATHER THAN A BARE SUBSTRING, so a mention in a
        comment cannot satisfy it. The whole risk here is a field that is talked about and not
        read.
        """
        source = self.java_source()
        for field in self.IDENTITY_FIELDS:
            self.assertTrue(re.search(r'field\.equals\("%s"\)' % re.escape(field), source),
                            "GraphJsonReader does not read %r; the in-game pack check would "
                            "silently report UNCHECKED" % field)

    def test_a_graph_round_trips_its_own_stamp(self):
        """The Python half end to end, so the field names are exercised and not just grepped.

        A grep proves the strings are present; this proves they are the strings a real graph
        actually carries.
        """
        graph = model.Graph()
        graph.dump_mod_digest = "deadbeef"
        graph.dump_mod_count = 410
        # NO `hasattr` GUARD AND NO SKIP. `Graph.to_json` exists; a guard here would be dead
        # code that turns a removed serialiser into a green run, which is precisely the
        # failure this module's docstring is about.
        document = graph.to_json()
        self.assertEqual("deadbeef", document.get("dump_mod_digest"))
        self.assertEqual(410, document.get("dump_mod_count"))


if __name__ == "__main__":
    unittest.main()
