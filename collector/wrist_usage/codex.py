"""Codex credential loading and usage mapping."""

import json
import os
import re

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


def _codex_plan(value):
    """Map a Codex plan identifier to its display name."""
    if not value:
        return None
    plans = {
        "free": "Free",
        "plus": "Plus",
        "pro": "Pro",
        "prolite": "Pro Lite",
        "team": "Team",
        "business": "Business",
        "enterprise": "Enterprise",
        "edu": "Edu",
    }
    return plans.get(str(value).lower(), str(value).title())


def _window(item, used_ids, ident=None, label_text=None):
    if not isinstance(item, dict) or not number(item.get("used_percent")):
        return None
    seconds = item.get("limit_window_seconds")
    if not number(seconds):
        return None
    if ident is None:
        if seconds == 18000:
            ident, label_text = "session", "5-hour"
        elif seconds == 604800:
            ident, label_text = "weekly", "Weekly"
        elif seconds < 172800:
            hours = max(1, round(seconds / 3600))
            ident, label_text = "window_%s" % int(seconds), "%s-hour" % hours
        else:
            days = max(1, round(seconds / 86400))
            ident, label_text = "window_%s" % int(seconds), "%s-day" % days
    ident = unique_id(ident, used_ids)
    return {
        "id": ident,
        "label": label(label_text or ident),
        "percent": clamp_percent(item["used_percent"]),
        "resets_at": utc_iso(item.get("reset_at")),
    }


def collect_codex(auth=None, http_json=_http_json):
    """Collect Codex usage, or return ``None`` when it is not installed."""
    if auth is None:
        auth = read_codex_auth()
    if not auth:
        return None
    tokens = auth.get("tokens") if isinstance(auth, dict) else None
    token = tokens.get("access_token") if isinstance(tokens, dict) else None
    account = tokens.get("account_id") if isinstance(tokens, dict) else None
    if not token or not account:
        return provider_dict(
            "codex",
            "Codex",
            status="error",
            message="Codex is not signed in with ChatGPT",
        )
    data, error = http_json(
        "https://chatgpt.com/backend-api/wham/usage",
        {
            "Authorization": "Bearer " + token,
            "ChatGPT-Account-Id": account,
            "User-Agent": "wrist-usage/0.1",
        },
    )
    if error is not None:
        if error in (401, 403):
            return provider_dict(
                "codex", "Codex", status="auth_expired", message="Run codex login on your Mac"
            )
        message = error_message(error)
        return provider_dict("codex", "Codex", status="error", message=message)

    plan = _codex_plan(data.get("plan_type")) if isinstance(data, dict) else None
    limits = _codex_limits(data)
    return provider_dict("codex", "Codex", plan, limits=limits)


def _codex_limits(data):
    used_ids = set()
    limits = []
    rate = data.get("rate_limit") if isinstance(data, dict) else None
    if isinstance(rate, dict):
        for key in ("primary_window", "secondary_window"):
            mapped = _window(rate.get(key), used_ids)
            if mapped is not None:
                limits.append(mapped)
    review = data.get("code_review_rate_limit") if isinstance(data, dict) else None
    if isinstance(review, dict):
        mapped = _window(review.get("primary_window"), used_ids, "code_review", "Code review")
        if mapped is not None:
            limits.append(mapped)
    additional = data.get("additional_rate_limits") if isinstance(data, dict) else None
    if isinstance(additional, list):
        for entry in additional:
            if not isinstance(entry, dict):
                continue
            name = entry.get("name") or entry.get("label")
            window = entry.get("window") or entry.get("primary_window")
            if name and isinstance(window, dict):
                ident = re.sub(r"[^a-z0-9]+", "_", str(name).lower()).strip("_")
                mapped = _window(window, used_ids, ident or "additional", str(name))
                if mapped is not None:
                    limits.append(mapped)
    return limits


def read_codex_auth():
    """Read Codex auth.json without exposing credential contents."""
    home = os.environ.get("CODEX_HOME", os.path.expanduser("~/.codex"))
    try:
        with open(os.path.join(home, "auth.json")) as handle:
            return json.load(handle)
    except (OSError, ValueError):
        return None
