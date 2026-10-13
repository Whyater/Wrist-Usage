"""Claude credential loading and usage mapping."""

import json
import os
import re
import shutil
import subprocess
import sys
import time

from .http import _http_json
from .snapshot import (
    clamp_percent,
    error_message,
    label,
    number,
    provider_dict,
    unique_id,
    utc_iso,
)


def _human_kind(kind):
    return label(re.sub(r"[_-]+", " ", str(kind)).title())


def _claude_plan(oauth):
    tier = str(oauth.get("rateLimitTier") or "").lower()
    if "max_20x" in tier:
        return "Max 20x"
    if "max_5x" in tier:
        return "Max 5x"
    subscription = oauth.get("subscriptionType")
    if subscription:
        return str(subscription).title()
    return None


def collect_claude(credentials=None, http_json=_http_json):
    """Collect Claude usage, or return ``None`` when it is not installed."""
    if credentials is None:
        credentials = read_claude_credentials()
    if not credentials:
        return None
    if credentials.get("_error") == "Keychain locked":
        return provider_dict("claude", "Claude", status="error", message="Keychain locked")
    oauth = credentials.get("claudeAiOauth", credentials)
    if not isinstance(oauth, dict):
        return None
    plan = _claude_plan(oauth)
    expiry = oauth.get("expiresAt")
    if number(expiry) and expiry / 1000 <= time.time():
        return provider_dict(
            "claude", "Claude", plan, "auth_expired", "Open Claude Code to sign in"
        )
    token = oauth.get("accessToken")
    if not token:
        return provider_dict(
            "claude", "Claude", plan, "auth_expired", "Open Claude Code to sign in"
        )
    data, error = http_json(
        "https://api.anthropic.com/api/oauth/usage",
        {
            "Authorization": "Bearer " + token,
            "anthropic-beta": "oauth-2025-04-20",
            "User-Agent": "wrist-usage/0.1",
        },
    )
    if error is not None:
        if error in (401, 403):
            return provider_dict(
                "claude", "Claude", plan, "auth_expired", "Open Claude Code to sign in"
            )
        message = error_message(error)
        return provider_dict("claude", "Claude", plan, "error", message)

    limits = _claude_limits(data)
    return provider_dict("claude", "Claude", plan, limits=limits)


def _claude_limits(data):
    limits = []
    used_ids = set()
    raw = data.get("limits") if isinstance(data, dict) else None
    if isinstance(raw, list) and raw:
        for item in raw:
            mapped = _map_claude_limit(item, used_ids)
            if mapped is not None:
                limits.append(mapped)
    else:
        fallback = (
            ("five_hour", "session", "5-hour"),
            ("seven_day", "weekly", "Weekly"),
            ("seven_day_opus", "weekly_opus", "Opus weekly"),
            ("seven_day_sonnet", "weekly_sonnet", "Sonnet weekly"),
        )
        for key, ident, label_text in fallback:
            item = data.get(key) if isinstance(data, dict) else None
            if isinstance(item, dict) and number(item.get("utilization")):
                limits.append(
                    {
                        "id": ident,
                        "label": label(label_text),
                        "percent": clamp_percent(item["utilization"]),
                        "resets_at": utc_iso(item.get("resets_at")),
                    }
                )
    extra = data.get("extra_usage") if isinstance(data, dict) else None
    if isinstance(extra, dict) and extra.get("is_enabled"):
        if number(extra.get("utilization")):
            limits.append(
                {
                    "id": "extra",
                    "label": "Extra usage",
                    "percent": clamp_percent(extra["utilization"]),
                    "resets_at": None,
                }
            )
    return limits


def _map_claude_limit(item, used_ids=None):
    if not isinstance(item, dict) or not number(item.get("percent")):
        return None
    kind = item.get("kind") or "limit"
    scope = item.get("scope") or {}
    model = scope.get("model") if isinstance(scope, dict) else {}
    display = model.get("display_name") if isinstance(model, dict) else None
    if kind == "session":
        ident, label_text = "session", "5-hour"
    elif kind == "weekly_all":
        ident, label_text = "weekly", "Weekly"
    elif kind == "weekly_scoped" and display:
        slug = re.sub(r"[^a-z0-9]+", "_", str(display).lower()).strip("_")
        ident = "weekly_" + (slug or "scoped")
        label_text = (str(display)[: max(0, 14 - len(" weekly"))] + " weekly")[:14]
    else:
        ident, label_text = str(kind), _human_kind(kind)
    if used_ids is not None:
        ident = unique_id(ident, used_ids)
    return {
        "id": ident,
        "label": label_text,
        "percent": clamp_percent(item["percent"]),
        "resets_at": utc_iso(item.get("resets_at")),
    }


def read_claude_credentials():
    """Read Claude credentials from keychain or the fallback file."""
    security = shutil.which("security") or "/usr/bin/security"
    if sys.platform == "darwin" and os.path.exists(security):
        try:
            result = subprocess.run(
                [security, "find-generic-password", "-s", "Claude Code-credentials", "-w"],
                stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL,
                timeout=5,
                check=False,
            )
        except subprocess.TimeoutExpired:
            return {"_error": "Keychain locked"}
        if result.returncode == 0:
            try:
                return json.loads(result.stdout.decode())
            except (ValueError, UnicodeDecodeError):
                return {"_error": "Unreadable credentials"}
        if result.returncode != 44:
            return {"_error": "Keychain locked"}
    path = os.path.expanduser("~/.claude/.credentials.json")
    try:
        with open(path) as handle:
            return json.load(handle)
    except (OSError, ValueError):
        return None
