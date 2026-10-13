"""Snapshot assembly and shared formatting helpers."""

import datetime as _dt
import sys
import time


def utc_iso(value):
    """Return a UTC timestamp with second precision, or ``None``."""
    if value is None:
        return None
    try:
        if isinstance(value, (int, float)):
            dt = _dt.datetime.fromtimestamp(value, _dt.timezone.utc)
        else:
            text = str(value).strip()
            if text.endswith("Z"):
                text = text[:-1] + "+00:00"
            if "." in text:
                prefix, suffix = text.split(".", 1)
                zone = ""
                for marker in ("+", "-"):
                    position = suffix.find(marker)
                    if position > 0:
                        zone = suffix[position:]
                        suffix = suffix[:position]
                        break
                suffix = (suffix + "000000")[:6]
                text = prefix + "." + suffix + zone
            dt = _dt.datetime.fromisoformat(text)
            if dt.tzinfo is None:
                dt = dt.replace(tzinfo=_dt.timezone.utc)
            dt = dt.astimezone(_dt.timezone.utc)
    except (ValueError, OverflowError, OSError):
        return None
    return dt.replace(microsecond=0).isoformat().replace("+00:00", "Z")


def label(text, max_len=14):
    """Limit a display label to the snapshot maximum length."""
    return str(text)[:max_len]


def number(value):
    """Return whether a value is a numeric percentage candidate."""
    return isinstance(value, (int, float)) and not isinstance(value, bool)


def error_message(error):
    """Map an HTTP helper error to the snapshot message."""
    if error == "network":
        return "Network error"
    if error == "json":
        return "Unexpected response"
    return "HTTP %s" % error


def clamp_percent(value):
    """Clamp a numeric usage percentage to the schema range."""
    return max(0, min(100, value))


def unique_id(base, used_ids):
    """Return a unique identifier and record it in ``used_ids``."""
    ident = str(base)
    suffix = 2
    while ident in used_ids:
        ident = "%s_%d" % (base, suffix)
        suffix += 1
    used_ids.add(ident)
    return ident


def provider_dict(pid, name, plan=None, status="ok", message=None, limits=None):
    """Build one sanitized provider entry."""
    return {
        "id": pid,
        "name": name,
        "plan": plan,
        "status": status,
        "message": message,
        "limits": limits or [],
    }


def collect_snapshot():
    """Collect available providers while isolating provider failures."""
    from .claude import collect_claude
    from .codex import collect_codex

    providers = []
    provider_specs = [
        ("claude", "Claude", collect_claude),
        ("codex", "Codex", collect_codex),
    ]
    for pid, name, collector in provider_specs:
        try:
            value = collector()
        except Exception as exc:
            print(type(exc).__name__, file=sys.stderr)
            value = provider_dict(
                pid,
                name,
                status="error",
                message="Collector error",
            )
        if value is not None:
            providers.append(value)
    return {
        "schema": 1,
        "generated_at": utc_iso(time.time()),
        "providers": providers,
    }
