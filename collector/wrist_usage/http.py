"""Small JSON HTTP helper used by provider clients."""

import json
import urllib.error
import urllib.request


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def _http_json(url, headers):
    """Fetch JSON and return ``(payload, error_code)``."""
    request = urllib.request.Request(url, headers=headers)
    try:
        opener = urllib.request.build_opener(_NoRedirect)
        with opener.open(request, timeout=15) as response:
            status = getattr(response, "status", 200)
            body = response.read()
    except urllib.error.HTTPError as exc:
        return None, exc.code
    except (urllib.error.URLError, TimeoutError, OSError):
        return None, "network"
    if status in (401, 403):
        return None, status
    if status < 200 or status >= 300:
        return None, status
    try:
        return json.loads(body.decode("utf-8")), None
    except (ValueError, UnicodeDecodeError):
        return None, "json"
