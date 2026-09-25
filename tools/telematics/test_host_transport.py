"""Loopback-only checks for the alternate route. No ADB or cloud access."""
import socket
import unittest
from unittest.mock import patch

from tls_identity_probe import HostTransport


class HostTransportTest(unittest.TestCase):
    def setUp(self):
        self.listener = socket.socket()
        self.listener.bind(("127.0.0.1", 0))
        self.listener.listen(1)
        self.listener.settimeout(2)

    def tearDown(self):
        self.listener.close()

    def connect(self):
        transport = HostTransport(*self.listener.getsockname())
        self.addCleanup(transport.close)
        peer, _ = self.listener.accept()
        peer.settimeout(2)
        transport.local.settimeout(2)
        self.addCleanup(peer.close)
        return transport, peer

    def test_duplex_and_cleanup(self):
        transport, peer = self.connect()
        transport.local.sendall(b"request")
        self.assertEqual(peer.recv(7), b"request")
        peer.sendall(b"response")
        self.assertEqual(transport.local.recv(8), b"response")
        transport.close()
        self.assertFalse(transport.thread.is_alive())
        self.assertEqual(transport.stats["to_server_bytes"], 7)
        self.assertEqual(transport.stats["from_server_bytes"], 8)

    def test_outgoing_limit_stops_before_forwarding(self):
        with patch.object(HostTransport, "MAX_BYTES", 4):
            transport, peer = self.connect()
            transport.local.sendall(b"12345")
            self.assertEqual(peer.recv(1), b"")
            transport.thread.join(2)
            self.assertEqual(transport.stats.get("error_type"), "ValueError")

    def test_incoming_limit_stops_before_forwarding(self):
        with patch.object(HostTransport, "MAX_BYTES", 4):
            transport, peer = self.connect()
            peer.sendall(b"12345")
            self.assertEqual(transport.local.recv(1), b"")
            transport.thread.join(2)
            self.assertEqual(transport.stats.get("error_type"), "ValueError")

    def test_idle_deadline_closes_both_directions(self):
        with patch.object(HostTransport, "MAX_SECONDS", 0.05):
            transport, peer = self.connect()
            self.assertEqual(transport.local.recv(1), b"")
            self.assertEqual(peer.recv(1), b"")
            transport.thread.join(2)
            self.assertTrue(transport.stats.get("deadline_reached"))
            self.assertFalse(transport.thread.is_alive())


if __name__ == "__main__":
    unittest.main()
