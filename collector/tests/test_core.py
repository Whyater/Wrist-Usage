import contextlib
import io
import json
import os
import subprocess
import tempfile
import unittest
from unittest import mock

from wrist_usage import claude, codex, snapshot
from wrist_usage import publish as publish_module
from wrist_usage.server import make_handler
from wrist_usage.http import _NoRedirect



def read_if_exists(path):
    """Return a file's text, or None when it does not exist."""
    if not os.path.exists(path):
        return None
    with open(path) as handle:
        return handle.read()

class ProviderTests(unittest.TestCase):
    def test_claude_limits_array_mapping_and_timestamp(self):
        creds = {"claudeAiOauth": {"accessToken": "TOKEN", "expiresAt": 4102444800000,
                                   "rateLimitTier": "default_claude_max_20x"}}
        payload = {"limits": [
            {"kind": "session", "percent": 14,
             "resets_at": "2026-10-13T04:39:59.793601+00:00"},
            {"kind": "weekly_all", "percent": 41,
             "resets_at": "2026-10-14T13:59:59+00:00"},
            {"kind": "weekly_scoped", "percent": 3,
             "resets_at": "2026-10-14T14:00:00Z",
             "scope": {"model": {"display_name": "Fable"}}},
        ]}
        result = claude.collect_claude(creds, lambda *_: (payload, None))
        self.assertEqual(result["plan"], "Max 20x")
        self.assertEqual([item["id"] for item in result["limits"]],
                         ["session", "weekly", "weekly_fable"])
        self.assertEqual(result["limits"][0]["resets_at"], "2026-10-13T04:39:59Z")

    def test_claude_fallback_fields_and_extra_usage(self):
        creds = {"claudeAiOauth": {"accessToken": "TOKEN", "expiresAt": 4102444800000,
                                   "subscriptionType": "pro"}}
        payload = {"five_hour": {"utilization": 1.5,
                                  "resets_at": "2026-01-01T00:00:00-05:00"},
                   "seven_day": {"utilization": 22, "resets_at": None},
                   "seven_day_opus": None,
                   "extra_usage": {"is_enabled": True, "utilization": 4}}
        result = claude.collect_claude(creds, lambda *_: (payload, None))
        self.assertEqual(result["plan"], "Pro")
        self.assertEqual([item["id"] for item in result["limits"]],
                         ["session", "weekly", "extra"])
        self.assertEqual(result["limits"][0]["resets_at"], "2026-01-01T05:00:00Z")

    def test_claude_scoped_label_truncated(self):
        creds = {"accessToken": "TOKEN", "expiresAt": 4102444800000}
        payload = {"limits": [{"kind": "weekly_scoped", "percent": 1,
                                "scope": {"model": {"display_name": "VeryLongModelName"}}}]}
        result = claude.collect_claude(creds, lambda *_: (payload, None))
        self.assertLessEqual(len(result["limits"][0]["label"]), 14)
        self.assertEqual(result["limits"][0]["id"], "weekly_verylongmodelname")

    def test_expired_claude_token_makes_no_http_call(self):
        creds = {"claudeAiOauth": {"accessToken": "TOKEN", "expiresAt": 1}}
        http = mock.Mock()
        result = claude.collect_claude(creds, http)
        self.assertEqual(result["status"], "auth_expired")
        http.assert_not_called()

    def test_http_401_is_auth_expired(self):
        result = claude.collect_claude({"accessToken": "TOKEN", "expiresAt": 4102444800000},
                                       lambda *_: (None, 401))
        self.assertEqual(result["status"], "auth_expired")
        result = codex.collect_codex({"tokens": {"access_token": "TOKEN",
                                                  "account_id": "ACCOUNT"}},
                                     lambda *_: (None, 401))
        self.assertEqual(result["status"], "auth_expired")

    def test_codex_windows_and_plan(self):
        auth = {"tokens": {"access_token": "TOKEN", "account_id": "ACCOUNT"}}
        payload = {"plan_type": "pro", "rate_limit": {
            "primary_window": {"used_percent": 10, "limit_window_seconds": 18000,
                                "reset_at": 1792302659},
            "secondary_window": {"used_percent": 20, "limit_window_seconds": 604800,
                                  "reset_at": 1792302659}},
                   "code_review_rate_limit": {"primary_window": {
                       "used_percent": 2, "limit_window_seconds": 3600,
                       "reset_at": 1792302659}}}
        result = codex.collect_codex(auth, lambda *_: (payload, None))
        self.assertEqual(result["plan"], "Pro")
        self.assertEqual([item["id"] for item in result["limits"]],
                         ["session", "weekly", "code_review"])

    def test_codex_missing_tokens_is_error(self):
        result = codex.collect_codex({"tokens": {}}, lambda *_: (None, None))
        self.assertEqual(result["status"], "error")
        self.assertEqual(result["message"], "Codex is not signed in with ChatGPT")
        self.assertEqual(codex._codex_plan("enterprise"), "Enterprise")
        self.assertIsNone(codex._codex_plan(None))

    def test_fixture_identifiers_never_appear_in_snapshot(self):
        email, account = "person@example.invalid", "account-secret-123"
        auth = {"tokens": {"access_token": "TOKEN", "account_id": account}}
        payload = {"email": email, "account_id": account, "user_id": "user-secret",
                   "plan_type": "free", "rate_limit": {"primary_window": {
                       "used_percent": 0, "limit_window_seconds": 604800,
                       "reset_at": 1792302659}}}
        result = codex.collect_codex(auth, lambda *_: (payload, None))
        serialized = json.dumps(result)
        self.assertNotIn(email, serialized)
        self.assertNotIn(account, serialized)
        self.assertNotIn("user-secret", serialized)

    def test_claude_token_and_fixture_email_never_appear_in_snapshot(self):
        token = "claude-token-secret"
        email = "claude-person@example.invalid"
        creds = {"accessToken": token, "expiresAt": 4102444800000}
        payload = {"email": email, "limits": [{"kind": "session", "percent": 10}]}
        result = claude.collect_claude(creds, lambda *_: (payload, None))
        serialized = json.dumps(result)
        self.assertNotIn(token, serialized)
        self.assertNotIn(email, serialized)

    def test_timestamp_fraction_lengths_and_percent_clamp(self):
        self.assertEqual(snapshot.utc_iso("2026-01-01T00:00:00.12Z"),
                         "2026-01-01T00:00:00Z")
        self.assertEqual(snapshot.utc_iso("2026-01-01T00:00:00.1234567Z"),
                         "2026-01-01T00:00:00Z")
        payload = {"rate_limit": {"primary_window": {
            "used_percent": 120, "limit_window_seconds": 604800}}}
        result = codex.collect_codex(
            {"tokens": {"access_token": "TOKEN", "account_id": "ACCOUNT"}},
            lambda *_: (payload, None),
        )
        self.assertEqual(result["limits"][0]["percent"], 100)
        claude_result = claude.collect_claude(
            {"accessToken": "TOKEN", "expiresAt": 4102444800000},
            lambda *_: ({"limits": [{"kind": "session", "percent": -5}]}, None),
        )
        self.assertEqual(claude_result["limits"][0]["percent"], 0)

    def test_redirect_handler_refuses_redirect(self):
        handler = _NoRedirect()
        self.assertIsNone(handler.redirect_request(None, None, 302, "Found", {}, "https://x"))

    def test_claude_keychain_fallback_and_locked(self):
        result = subprocess.CompletedProcess([], 44, stdout=b"", stderr=b"")
        with tempfile.TemporaryDirectory() as td:
            path = os.path.join(td, "credentials.json")
            expected = {"claudeAiOauth": {"accessToken": "TOKEN"}}
            with open(path, "w") as handle:
                json.dump(expected, handle)
            with mock.patch.object(claude.sys, "platform", "darwin"), \
                 mock.patch.object(claude.os.path, "exists", return_value=True), \
                 mock.patch.object(claude.subprocess, "run", return_value=result), \
                 mock.patch.object(claude.os.path, "expanduser", return_value=path):
                self.assertEqual(claude.read_claude_credentials(), expected)
        result = subprocess.CompletedProcess([], 1, stdout=b"", stderr=b"")
        with mock.patch.object(claude.sys, "platform", "darwin"), \
             mock.patch.object(claude.os.path, "exists", return_value=True), \
             mock.patch.object(claude.subprocess, "run", return_value=result):
            locked = claude.collect_claude(claude.read_claude_credentials())
        self.assertEqual(locked["message"], "Keychain locked")

    def test_collect_snapshot_isolates_provider_errors(self):
        good = {"id": "codex", "name": "Codex", "status": "ok", "limits": []}
        with mock.patch.object(claude, "collect_claude", side_effect=RuntimeError("boom")), \
             mock.patch.object(codex, "collect_codex", return_value=good):
            stderr = io.StringIO()
            with contextlib.redirect_stderr(stderr):
                result = snapshot.collect_snapshot()
        self.assertEqual(result["providers"][0]["status"], "error")
        self.assertEqual(result["providers"][0]["message"], "Collector error")
        self.assertEqual(result["providers"][1], good)
        self.assertIn("RuntimeError", stderr.getvalue())


class SetupAndServerTests(unittest.TestCase):
    def test_local_properties_update_preserves_sdk_dir(self):
        with tempfile.TemporaryDirectory() as td:
            path = os.path.join(td, "local.properties")
            with open(path, "w") as handle:
                handle.write("sdk.dir=/Applications/Android SDK\nother=value\n")
            publish_module._set_property(path, "https://gist.invalid/o/g/raw/usage.json")
            with open(path) as handle:
                text = handle.read()
            self.assertIn("sdk.dir=/Applications/Android SDK", text)
            self.assertIn("other=value", text)
            self.assertIn("snapshotUrl=https://gist.invalid/o/g/raw/usage.json", text)

    def test_setup_dry_run_lists_plist_and_writes_nothing(self):
        root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
        env = os.environ.copy()
        local_properties = os.path.join(os.path.dirname(root), "android", "local.properties")
        before_properties = read_if_exists(local_properties)
        with tempfile.TemporaryDirectory() as td:
            env["HOME"] = td
            env["PYTHONPATH"] = root
            proc = subprocess.run([os.sys.executable, "-m", "wrist_usage", "setup", "--dry-run"],
                                  cwd=root, env=env, text=True, capture_output=True)
            self.assertEqual(proc.returncode, 0, proc.stderr)
            plist = os.path.join(td, "Library/LaunchAgents/io.github.whyater.wristusage.plist")
            self.assertIn("plist: " + plist, proc.stdout)
            self.assertFalse(os.path.exists(os.path.join(td, ".config")))
            self.assertFalse(os.path.exists(plist))
        self.assertEqual(read_if_exists(local_properties), before_properties)

    def test_serve_handler_returns_404_for_other_paths(self):
        handler_class = make_handler(lambda: {"schema": 1})
        handler = handler_class.__new__(handler_class)
        handler.path = "/other?x=1"
        handler.send_error = mock.Mock()
        handler.do_GET()
        handler.send_error.assert_called_once_with(404)

    def test_serve_handler_accepts_usage_query_string(self):
        handler_class = make_handler(lambda: {"schema": 1})
        for path in ("/usage.json?t=1792302659", "/usage.json"):
            handler = handler_class.__new__(handler_class)
            handler.path = path
            handler.send_response = mock.Mock()
            handler.send_header = mock.Mock()
            handler.end_headers = mock.Mock()
            handler.wfile = io.BytesIO()
            handler.do_GET()
            handler.send_response.assert_called_once_with(200)
            self.assertEqual(handler.wfile.getvalue(), b'{"schema":1}')

    def test_publish_gh_failure_returns_one_without_traceback(self):
        with tempfile.TemporaryDirectory() as td:
            config = os.path.join(td, "config.json")
            with open(config, "w") as handle:
                json.dump({"gist_id": "gist"}, handle)
            error = subprocess.CalledProcessError(1, ["gh"], stderr=b"bad gh\n")
            with mock.patch.object(publish_module, "config_path", return_value=config), \
                 mock.patch.object(publish_module, "_run_gh", side_effect=error), \
                 mock.patch.object(publish_module, "collect_snapshot", return_value={"schema": 1}):
                stderr = io.StringIO()
                with contextlib.redirect_stderr(stderr):
                    code = publish_module.publish()
            self.assertEqual(code, 1)
            self.assertIn("bad gh", stderr.getvalue())
            self.assertNotIn("Traceback", stderr.getvalue())

    def test_setup_uses_owner_from_post_response(self):
        with tempfile.TemporaryDirectory() as td:
            config = os.path.join(td, "config.json")
            props = os.path.join(td, "local.properties")
            root = os.path.join(td, "collector")
            args = type("Args", (), {"dry_run": False, "no_launchd": True})()
            response = {"id": "gist-id", "owner": {"login": "post-owner"}}
            with mock.patch.object(publish_module, "config_path", return_value=config), \
                 mock.patch.object(
                     publish_module, "_repo_paths", return_value=(root, props, "plist")
                 ), \
                 mock.patch.object(
                     publish_module, "_run_gh", return_value=json.dumps(response).encode()
                 ) as run_gh, \
                 mock.patch.object(publish_module, "collect_snapshot", return_value={"schema": 1}):
                self.assertEqual(publish_module.setup(args), 0)
            run_gh.assert_called_once()
            with open(config) as handle:
                saved = json.load(handle)
            self.assertEqual(saved["owner"], "post-owner")
            self.assertIn("post-owner/gist-id", saved["raw_url"])

    def test_setup_reports_launchctl_bootstrap_failure(self):
        with tempfile.TemporaryDirectory() as td:
            config = os.path.join(td, "config.json")
            props = os.path.join(td, "local.properties")
            args = type("Args", (), {"dry_run": False, "no_launchd": False})()
            response = {"id": "gist-id", "owner": {"login": "post-owner"}}
            runs = [
                subprocess.CompletedProcess([], 0),
                subprocess.CompletedProcess([], 7),
            ]
            with mock.patch.object(publish_module, "config_path", return_value=config), \
                 mock.patch.object(
                     publish_module,
                     "_repo_paths",
                     return_value=(td, props, os.path.join(td, "job.plist")),
                 ), \
                 mock.patch.object(
                     publish_module, "_run_gh", return_value=json.dumps(response).encode()
                 ), \
                 mock.patch.object(
                     publish_module, "collect_snapshot", return_value={"schema": 1}
                 ), \
                 mock.patch.object(publish_module, "_write_plist"), \
                 mock.patch.object(publish_module, "_set_property"), \
                 mock.patch.object(publish_module.os.path, "exists", return_value=True), \
                 mock.patch.object(publish_module.subprocess, "run", side_effect=runs):
                stderr = io.StringIO()
                with contextlib.redirect_stderr(stderr):
                    code = publish_module.setup(args)
            self.assertEqual(code, 1)
            self.assertIn("launchctl bootstrap failed (exit 7)", stderr.getvalue())

    def test_uninstall_keeps_config_when_gist_delete_fails(self):
        with tempfile.TemporaryDirectory() as td:
            config = os.path.join(td, "config.json")
            with open(config, "w") as handle:
                json.dump({"gist_id": "gist"}, handle)
            error = subprocess.CalledProcessError(1, ["gh"], stderr=b"delete failed\n")
            args = type("Args", (), {"delete_gist": True})()
            plist_path = os.path.join(td, "missing.plist")
            with mock.patch.object(publish_module, "config_path", return_value=config), \
                 mock.patch.object(publish_module, "_run_gh", side_effect=error), \
                 mock.patch.object(publish_module.os.path, "expanduser", return_value=plist_path):
                stderr = io.StringIO()
                with contextlib.redirect_stderr(stderr):
                    code = publish_module.uninstall(args)
            self.assertEqual(code, 1)
            self.assertTrue(os.path.exists(config))
            self.assertIn("delete failed", stderr.getvalue())

    def test_url_prints_saved_raw_url_or_asks_for_setup(self):
        with tempfile.TemporaryDirectory() as td:
            config = os.path.join(td, "config.json")
            with mock.patch.object(publish_module, "config_path", return_value=config):
                stderr = io.StringIO()
                with contextlib.redirect_stderr(stderr):
                    self.assertEqual(publish_module.show_url(), 1)
                self.assertIn("Run setup first", stderr.getvalue())
                with open(config, "w") as handle:
                    json.dump({"raw_url": "https://gist.invalid/o/g/raw/usage.json"}, handle)
                stdout = io.StringIO()
                with contextlib.redirect_stdout(stdout):
                    self.assertEqual(publish_module.show_url(), 0)
                expected = "https://gist.invalid/o/g/raw/usage.json"
                self.assertEqual(stdout.getvalue().strip(), expected)


if __name__ == "__main__":
    unittest.main()
