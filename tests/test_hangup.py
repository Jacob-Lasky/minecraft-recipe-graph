"""A client that hangs up mid-response must not look like a server fault.

WHY THIS EXISTS. Inspecting a page from the terminal means `curl ... | head`, and `head`
closes the pipe as soon as it has enough. The server was still writing, so the write raised
`BrokenPipeError`, nothing caught it, and `socketserver` printed a twenty line traceback
ending in `_send_bytes`. Every one of those reads as a crash inside a page renderer.

That is worse than untidy, and the reason is the container's own health check: "does the log
contain a traceback" is how this deployment is checked after a redeploy. One truncated curl
poisons that signal, so the next REAL renderer exception arrives in a log that already has
tracebacks in it and nobody looks twice.

THE CATCH IS DELIBERATELY NARROW. Only `BrokenPipeError` and `ConnectionResetError`, which
are the two ways a peer goes away. A bare `except OSError` would also swallow a full disk or
a socket misconfiguration, and those are faults that SHOULD be loud.
"""

import os
import socket
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from recipegraph import server  # noqa: E402

from test_server import LiveServerCase  # noqa: E402


class _Hangup(object):
    """A `wfile` that refuses to be written to, the way a closed socket does."""

    def __init__(self, error):
        self.error = error
        self.writes = 0

    def write(self, _raw):
        self.writes += 1
        raise self.error


class _Recorder(object):
    """Enough of a handler to drive `_send_bytes` without a socket underneath it."""

    def __init__(self, wfile):
        self.wfile = wfile
        self.headers_ended = False

    send_response = staticmethod(lambda *a, **k: None)
    send_header = staticmethod(lambda *a, **k: None)

    def end_headers(self):
        self.headers_ended = True


def _send(wfile):
    rec = _Recorder(wfile)
    server.Handler._send_bytes(rec, b"body bytes")
    return rec


class APeerGoingAwayIsNotAnError(unittest.TestCase):

    def test_a_broken_pipe_while_writing_the_body_is_swallowed(self):
        wfile = _Hangup(BrokenPipeError(32, "Broken pipe"))
        rec = _send(wfile)
        self.assertEqual(wfile.writes, 1, "the write must actually have been attempted")
        self.assertTrue(rec.headers_ended, "headers still go out before the body")

    def test_a_reset_connection_is_swallowed_too(self):
        # A peer that RSTs rather than FINs. Same situation, different errno, and catching
        # only one of the two leaves half the cases printing tracebacks.
        _send(_Hangup(ConnectionResetError(104, "Connection reset by peer")))

    def test_an_unrelated_oserror_still_propagates(self):
        # Guards against the catch being widened to `except OSError`, which would hide a full
        # disk behind the same silence. If this ever stops raising, the catch got too broad.
        with self.assertRaises(OSError):
            _send(_Hangup(OSError(28, "No space left on device")))

    def test_a_programming_error_still_propagates(self):
        with self.assertRaises(ValueError):
            _send(_Hangup(ValueError("renderer bug")))


class TheServerSurvivesARealTruncatedRead(LiveServerCase):
    """The end-to-end version, over a real socket, because the unit tests above stub `wfile`.

    Reproduces what `curl | head` does: ask for a page big enough to need more than one
    write, read a few bytes, then close without draining.
    """

    def test_closing_mid_response_leaves_the_server_answering(self):
        sock = socket.create_connection(("127.0.0.1", self.port), timeout=5)
        try:
            sock.sendall(b"GET /machines HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
            self.assertTrue(sock.recv(64), "expected at least the status line")
        finally:
            sock.close()

        # The real assertion: the NEXT request is served normally. A handler thread that died
        # on the hangup, or a server left in a bad state, shows up here.
        status, _ctype, body = self.get("/machines")
        self.assertEqual(status, 200)
        self.assertIn("categories", body)


if __name__ == "__main__":
    unittest.main()
