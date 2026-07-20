import base64
import json
import logging
import subprocess
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace

from relay.cag_web import (
    AdminClient,
    AdminCommandError,
    CagWebApp,
    DashboardService,
    ListenerProbe,
    WebError,
    create_server,
    decode_admin_result,
    local_access_policy,
    parse_listener_ports,
)


def encoded_result(payload: dict) -> bytes:
    encoded = base64.urlsafe_b64encode(
        json.dumps(payload, separators=(",", ":")).encode()
    ).decode().rstrip("=")
    return f"OK {encoded}\n".encode()


DEVICE_ID = "0123456789abcdef"


def list_payload(label: str = "Ivan <N9>") -> dict:
    return {
        "devices": [
            {
                "device_id": DEVICE_ID,
                "label": label,
                "enabled": True,
                "relay_port": 20_000,
                "lease_expires_at": 1_800_000_000,
                "client_label": "dmitry-mac",
            }
        ]
    }


def doctor_payload() -> dict:
    return {
        "ok": True,
        "version": 3,
        "devices": 1,
        "clients": 1,
        "grants": 1,
        "pending": 0,
        "codes": 0,
        "orphaned_clients": 0,
        "expired_devices": 0,
        "next_port": 20_001,
    }


def status_payload(label: str = "Ivan <N9>") -> dict:
    return {
        "device_id": DEVICE_ID,
        "device_label": label,
        "relay_host": "adbgw.ru",
        "relay_ssh_port": 443,
        "relay_device_port": 20_000,
        "inner_host_key": "ssh-ed25519 SECRET-INNER-KEY",
        "endpoint_mode": "smart",
        "endpoint_host": "127.0.0.1",
        "enabled": True,
        "lease_expires_at": 1_800_000_000,
        "active_client_fingerprint": "SHA256:active-fingerprint-value",
        "active_client_label": "dmitry-mac",
        "pending_client_fingerprint": None,
        "pending_client_label": None,
        "pending_expires_at": None,
    }


class FakeAdmin:
    def __init__(self, label: str = "Ivan <N9>") -> None:
        self.label = label
        self.calls = []

    def call(self, action: str, *arguments: str) -> dict:
        self.calls.append((action, arguments))
        if action == "list":
            return list_payload(self.label)
        if action == "doctor":
            return doctor_payload()
        if action == "status":
            return status_payload(self.label)
        if action == "invite":
            return {"code": "ABCD-EFGH", "expires_at": 1_700_003_600}
        if action == "rename":
            self.label = arguments[1]
            return {"device_id": arguments[0], "label": arguments[1]}
        if action == "remove":
            return {"device_id": arguments[0], "removed": True}
        raise AssertionError(f"unexpected action {action}")


class FakeProbe:
    def __init__(self, ports):
        self.value = ports

    def ports(self):
        return self.value


class AdminClientTest(unittest.TestCase):
    def test_decode_admin_result_returns_json_object(self):
        self.assertEqual(
            {"devices": []},
            decode_admin_result(encoded_result({"devices": []})),
        )

    def test_decode_admin_result_rejects_malformed_or_oversized_output(self):
        for output in (b"not-ok\n", b"OK !!!\n", b"x" * 65_537):
            with self.subTest(output=output[:16]):
                with self.assertRaises(AdminCommandError):
                    decode_admin_result(output)

    def test_admin_client_uses_fixed_sudo_command_without_shell(self):
        calls = []

        def run(argv, **kwargs):
            calls.append((argv, kwargs))
            return SimpleNamespace(
                returncode=0,
                stdout=encoded_result({"devices": []}),
                stderr=b"",
            )

        result = AdminClient(run=run).call("list")

        self.assertEqual({"devices": []}, result)
        argv, kwargs = calls[0]
        self.assertEqual(
            ["/usr/bin/sudo", "-n", "/usr/local/sbin/cag-admin", "list"],
            argv,
        )
        self.assertNotIn("shell", kwargs)
        self.assertEqual(5, kwargs["timeout"])
        self.assertEqual(
            {"PATH": "/usr/sbin:/usr/bin:/sbin:/bin", "LANG": "C.UTF-8"},
            kwargs["env"],
        )

    def test_admin_client_maps_timeout_and_state_error(self):
        def timeout(*_args, **_kwargs):
            raise subprocess.TimeoutExpired("cag-admin", 5)

        with self.assertRaisesRegex(AdminCommandError, "timed out"):
            AdminClient(run=timeout).call("doctor")

        def state_error(*_args, **_kwargs):
            return SimpleNamespace(
                returncode=2,
                stdout=b"",
                stderr=b"ERROR unknown device\n",
            )

        with self.assertRaisesRegex(AdminCommandError, "unknown device") as raised:
            AdminClient(run=state_error).call("status", "0123456789abcdef")
        self.assertTrue(raised.exception.expected)


class ListenerProbeTest(unittest.TestCase):
    def test_parser_returns_only_ipv4_loopback_listeners(self):
        table = """\
  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt
   0: 0100007F:4E20 00000000:0000 0A 00000000:00000000 00:00000000 00000000
   1: 00000000:2243 00000000:0000 0A 00000000:00000000 00:00000000 00000000
   2: 0100007F:4E21 00000000:0000 01 00000000:00000000 00:00000000 00000000
"""

        self.assertEqual({20_000}, parse_listener_ports(table))

    def test_probe_returns_unknown_when_proc_table_is_unavailable(self):
        with tempfile.TemporaryDirectory() as directory:
            missing = Path(directory) / "missing"
            self.assertIsNone(ListenerProbe(missing).ports())

    def test_probe_reads_proc_table(self):
        with tempfile.TemporaryDirectory() as directory:
            table = Path(directory) / "tcp"
            table.write_text(
                "  sl  local_address rem_address st\n"
                "   0: 0100007F:4E20 00000000:0000 0A\n",
                encoding="ascii",
            )
            self.assertEqual({20_000}, ListenerProbe(table).ports())


class DashboardServiceTest(unittest.TestCase):
    def test_dashboard_sorts_and_marks_current_listener_online(self):
        admin = FakeAdmin()
        service = DashboardService(admin, FakeProbe({20_000}), clock=lambda: 1_700_000_000)

        result = service.dashboard()

        self.assertEqual(doctor_payload(), result["doctor"])
        self.assertEqual("online", result["devices"][0]["online_status"])
        self.assertEqual(1_700_000_000, result["updated_at"])

    def test_listener_inspection_failure_is_unknown_not_offline(self):
        result = DashboardService(FakeAdmin(), FakeProbe(None)).dashboard()
        self.assertEqual("unknown", result["devices"][0]["online_status"])

    def test_device_details_are_allow_listed(self):
        service = DashboardService(FakeAdmin(), FakeProbe({20_000}))

        result = service.device(DEVICE_ID)

        self.assertEqual("Ivan <N9>", result["label"])
        self.assertEqual(20_000, result["relay_port"])
        self.assertEqual("online", result["online_status"])
        self.assertNotIn("inner_host_key", result)
        self.assertNotIn("relay_host", result)
        self.assertNotIn("SECRET-INNER-KEY", json.dumps(result))

    def test_invite_rename_and_remove_use_only_admin_commands(self):
        admin = FakeAdmin(label="Ivan - N9")
        service = DashboardService(admin, FakeProbe(set()))

        invite = service.invite("Ivan - N9", 3_600)
        renamed = service.rename(DEVICE_ID, "Ivan - black N9")
        removed = service.remove(DEVICE_ID, "Ivan - black N9")

        self.assertEqual("ABCD-EFGH", invite["code"])
        self.assertEqual("Ivan - black N9", renamed["label"])
        self.assertTrue(removed["removed"])
        self.assertEqual(
            [
                ("invite", ("--label=Ivan - N9", "--ttl", "3600")),
                ("rename", (DEVICE_ID, "Ivan - black N9")),
                ("status", (DEVICE_ID,)),
                ("remove", (DEVICE_ID,)),
            ],
            admin.calls,
        )

    def test_invite_treats_a_leading_dash_label_as_data(self):
        admin = FakeAdmin()
        service = DashboardService(admin, FakeProbe(set()))

        result = service.invite("-служебная машина", 3_600)

        self.assertEqual("-служебная машина", result["label"])
        self.assertEqual(
            [("invite", ("--label=-служебная машина", "--ttl", "3600"))],
            admin.calls,
        )

    def test_remove_requires_exact_current_label(self):
        admin = FakeAdmin(label="Ivan - N9")
        service = DashboardService(admin, FakeProbe(set()))

        with self.assertRaisesRegex(WebError, "название") as raised:
            service.remove(DEVICE_ID, "another car")

        self.assertEqual(409, raised.exception.status)
        self.assertEqual([("status", (DEVICE_ID,))], admin.calls)

    def test_web_mutations_reject_invalid_label_ttl_and_device_id(self):
        service = DashboardService(FakeAdmin(), FakeProbe(set()))
        invalid_calls = (
            lambda: service.invite("   ", 3_600),
            lambda: service.invite("Ivan", 120),
            lambda: service.rename("not-a-device", "Ivan"),
        )
        for call in invalid_calls:
            with self.subTest(call=call):
                with self.assertRaises(WebError):
                    call()


class CagWebAppTest(unittest.TestCase):
    def setUp(self) -> None:
        self.admin = FakeAdmin()
        self.service = DashboardService(
            self.admin,
            FakeProbe({20_000}),
            clock=lambda: 1_700_000_000,
        )
        self.app = CagWebApp(
            self.service,
            csrf_token="test-csrf-token",
            hosts={"127.0.0.1:8787", "localhost:8787"},
            origins={"http://127.0.0.1:8787", "http://localhost:8787"},
        )

    def request(self, method: str, path: str, body=None, **headers):
        encoded = b"" if body is None else json.dumps(body).encode()
        base = {"host": "127.0.0.1:8787"}
        base.update(headers)
        return self.app.handle(method, path, base, encoded)

    def mutation(self, path: str, body: dict, **headers):
        headers.setdefault("origin", "http://127.0.0.1:8787")
        headers.setdefault("x-cag-csrf", "test-csrf-token")
        headers.setdefault("content-type", "application/json")
        return self.request("POST", path, body, **headers)

    def test_root_renders_escaped_server_side_dashboard_and_security_headers(self):
        response = self.request("GET", "/")
        html = response.body.decode()

        self.assertEqual(200, response.status)
        self.assertIn("Ivan &lt;N9&gt;", html)
        self.assertNotIn("Ivan <N9>", html)
        self.assertIn("Добавить машину", html)
        self.assertIn("test-csrf-token", html)
        self.assertIn("script-src 'nonce-", response.headers["Content-Security-Policy"])
        self.assertEqual("DENY", response.headers["X-Frame-Options"])
        self.assertEqual("no-store", response.headers["Cache-Control"])

    def test_invite_dialog_uses_native_form_semantics_and_neutral_copy(self):
        html = self.request("GET", "/").body.decode()

        self.assertNotIn("Доступ только через SSH-туннель", html)
        self.assertIn('placeholder="Например, основная машина"', html)
        self.assertNotIn('placeholder="Иван — Denza N9"', html)
        self.assertIn('<form id="invite-form" method="dialog"', html)
        self.assertIn('id="invite-error"', html)
        self.assertIn(
            '<button id="cancel-invite" type="button" class="secondary">Отмена</button>',
            html,
        )
        self.assertIn(
            '<button id="create-invite" type="submit" class="primary">Создать код</button>',
            html,
        )
        self.assertIn("inviteForm.onsubmit=async event=>", html)
        self.assertNotIn("getElementById('create-invite').onclick", html)

    def test_invite_dialog_guards_repeats_and_reports_local_errors(self):
        html = self.request("GET", "/").body.decode()

        self.assertIn("let invitePending=false", html)
        self.assertIn("if(invitePending)return", html)
        self.assertIn("setInvitePending(true)", html)
        self.assertIn("setInvitePending(false)", html)
        self.assertIn("inviteError.textContent=error.message", html)
        self.assertIn("inviteError.style.display='block'", html)
        self.assertIn("finally{setInvitePending(false);}", html)

    def test_async_actions_handle_copy_failures_and_disable_the_clicked_button(self):
        html = self.request("GET", "/").body.decode()

        self.assertIn("async function copyInviteCode()", html)
        self.assertIn("Код не удалось скопировать", html)
        self.assertIn("button.disabled=true", html)
        self.assertIn("finally{button.disabled=false;}", html)

    def test_health_and_api_dashboard_are_json(self):
        health = self.request("GET", "/healthz")
        dashboard = self.request("GET", "/api/dashboard")

        self.assertEqual({"ok": True}, json.loads(health.body))
        self.assertEqual("online", json.loads(dashboard.body)["devices"][0]["online_status"])
        self.assertEqual("application/json; charset=utf-8", dashboard.headers["Content-Type"])

    def test_device_api_does_not_expose_public_keys_or_raw_status(self):
        response = self.request("GET", f"/api/devices/{DEVICE_ID}")
        payload = json.loads(response.body)

        self.assertEqual(200, response.status)
        self.assertEqual("Ivan <N9>", payload["label"])
        self.assertNotIn("inner_host_key", payload)
        self.assertNotIn("SECRET-INNER-KEY", response.body.decode())

    def test_invite_rename_and_remove_http_contract(self):
        with self.assertLogs("cag-web", logging.INFO) as captured:
            invite = self.mutation(
                "/api/invites",
                {"label": "Ivan; rm -rf /", "ttl_seconds": 3_600},
            )
            renamed = self.mutation(
                f"/api/devices/{DEVICE_ID}/rename",
                {"label": "Ivan - black N9"},
            )
            removed = self.mutation(
                f"/api/devices/{DEVICE_ID}/remove",
                {"confirm_label": "Ivan - black N9"},
            )

        self.assertEqual(201, invite.status)
        self.assertEqual("ABCD-EFGH", json.loads(invite.body)["code"])
        self.assertEqual(200, renamed.status)
        self.assertEqual(200, removed.status)
        logs = "\n".join(captured.output)
        self.assertNotIn("ABCD-EFGH", logs)
        self.assertNotIn("test-csrf-token", logs)
        self.assertNotIn("rm -rf", logs)

    def test_rejects_bad_host_origin_csrf_get_mutation_and_large_body(self):
        bad_host = self.request("GET", "/", host="evil.example")
        bad_origin = self.mutation(
            "/api/invites",
            {"label": "Ivan", "ttl_seconds": 3_600},
            origin="http://evil.example",
        )
        bad_csrf = self.mutation(
            "/api/invites",
            {"label": "Ivan", "ttl_seconds": 3_600},
            **{"x-cag-csrf": "wrong"},
        )
        get_mutation = self.request("GET", "/api/invites")
        too_large = self.app.handle(
            "POST",
            "/api/invites",
            {
                "host": "127.0.0.1:8787",
                "origin": "http://127.0.0.1:8787",
                "x-cag-csrf": "test-csrf-token",
                "content-type": "application/json",
            },
            b"x" * 4_097,
        )

        self.assertEqual(403, bad_host.status)
        self.assertEqual(403, bad_origin.status)
        self.assertEqual(403, bad_csrf.status)
        self.assertEqual(405, get_mutation.status)
        self.assertEqual(413, too_large.status)

    def test_backend_failures_map_to_safe_http_errors(self):
        class FailingAdmin(FakeAdmin):
            def call(self, action, *arguments):
                if action == "status":
                    raise AdminCommandError("unknown device", expected=True)
                raise AdminCommandError("secret backend detail", unavailable=True)

        app = CagWebApp(
            DashboardService(FailingAdmin(), FakeProbe(set())),
            csrf_token="test-csrf-token",
        )
        missing = app.handle(
            "GET",
            f"/api/devices/{DEVICE_ID}",
            {"host": "127.0.0.1:8787"},
            b"",
        )
        unavailable = app.handle(
            "GET",
            "/api/dashboard",
            {"host": "127.0.0.1:8787"},
            b"",
        )

        self.assertEqual(404, missing.status)
        self.assertEqual(503, unavailable.status)
        self.assertNotIn("secret backend detail", unavailable.body.decode())


class ServerTest(unittest.TestCase):
    def test_local_access_policy_tracks_configured_port(self):
        hosts, origins = local_access_policy(8899)
        self.assertEqual({"127.0.0.1:8899", "localhost:8899"}, hosts)
        self.assertEqual(
            {"http://127.0.0.1:8899", "http://localhost:8899"},
            origins,
        )

    def test_server_refuses_non_loopback_bind(self):
        app = CagWebApp(DashboardService(FakeAdmin(), FakeProbe(set())))
        with self.assertRaisesRegex(ValueError, "loopback"):
            create_server(app, "0.0.0.0", 8787)

    def test_server_binds_ipv4_loopback(self):
        app = CagWebApp(DashboardService(FakeAdmin(), FakeProbe(set())))
        server = create_server(app, "127.0.0.1", 0)
        try:
            self.assertEqual("127.0.0.1", server.server_address[0])
        finally:
            server.server_close()


if __name__ == "__main__":
    unittest.main()
