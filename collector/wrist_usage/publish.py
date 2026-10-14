"""GitHub gist publishing and launchd setup commands."""

import json
import os
import plistlib
import shutil
import subprocess
import sys
import time

from .snapshot import collect_snapshot

LABEL = "io.github.whyater.wristusage"


def config_path():
    """Return the user's wrist-usage config path."""
    return os.path.expanduser("~/.config/wrist-usage/config.json")


def _gh():
    path = shutil.which("gh")
    if path:
        return path
    for candidate in ("/opt/homebrew/bin/gh", "/usr/local/bin/gh"):
        if os.path.exists(candidate):
            return candidate
    return None


def _run_gh(args, payload=None):
    """Run gh and return stdout bytes."""
    path = _gh()
    if not path:
        raise FileNotFoundError("gh CLI not found")
    result = subprocess.run(
        [path] + args,
        input=payload,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=True,
    )
    return result.stdout


def _gh_error(exc):
    if isinstance(exc, subprocess.CalledProcessError):
        detail = exc.stderr
        if isinstance(detail, bytes):
            detail = detail.decode("utf-8", errors="replace")
        detail = str(detail or "").strip()
        return detail or "gh command failed"
    return str(exc).strip() or "gh command failed"


def _repo_paths():
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    props = os.path.join(os.path.dirname(root), "android", "local.properties")
    plist = os.path.expanduser("~/Library/LaunchAgents/%s.plist" % LABEL)
    return root, props, plist


def _set_property(path, value):
    lines = []
    if os.path.exists(path):
        with open(path) as handle:
            lines = handle.read().splitlines()
    found = False
    output = []
    for line in lines:
        if line.startswith("snapshotUrl="):
            if not found:
                output.append("snapshotUrl=" + value)
                found = True
        else:
            output.append(line)
    if not found:
        output.append("snapshotUrl=" + value)
    with open(path, "w") as handle:
        handle.write("\n".join(output) + "\n")


def _load_config():
    try:
        with open(config_path()) as handle:
            return json.load(handle)
    except (OSError, ValueError):
        return None


def _launchctl_commands(plist):
    uid = str(os.getuid())
    return [
        "/bin/launchctl bootout gui/%s %s" % (uid, plist),
        "/bin/launchctl bootstrap gui/%s %s" % (uid, plist),
    ]


def _write_plist(root, plist):
    interpreter = _interpreter()
    data = {
        "Label": LABEL,
        "ProgramArguments": [interpreter, "-m", "wrist_usage", "publish"],
        "WorkingDirectory": root,
        "StartInterval": 300,
        "RunAtLoad": True,
        "StandardOutPath": os.path.expanduser("~/Library/Logs/wrist-usage.log"),
        "StandardErrorPath": os.path.expanduser("~/Library/Logs/wrist-usage.log"),
    }
    os.makedirs(os.path.dirname(plist), exist_ok=True)
    with open(plist, "wb") as handle:
        plistlib.dump(data, handle)


def _interpreter():
    """Choose a stable Python interpreter for launchd."""
    candidate = "/usr/bin/python3"
    if os.path.exists(candidate):
        try:
            result = subprocess.run(
                [candidate, "-c", "import sys\nprint(sys.version_info[0], sys.version_info[1])"],
                stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL,
                check=False,
                text=True,
                timeout=5,
            )
            parts = result.stdout.strip().split()
            version = tuple(int(part) for part in parts[:2])
            if result.returncode == 0 and version >= (3, 9):
                return candidate
        except (SyntaxError, TypeError, ValueError, OSError, subprocess.TimeoutExpired):
            pass
    return sys.executable


def _print_setup_actions(existing, args, config, props, plist):
    if existing and existing.get("gist_id"):
        action = "reuse existing gist"
    else:
        action = "create secret gist (anyone with the URL can read it)"
    print("gist: %s" % action)
    print("config: %s" % config)
    print("local.properties: %s" % props)
    print("local.properties key: snapshotUrl=<raw_url>")
    if not args.no_launchd:
        print("plist: %s" % plist)
        print("interpreter: %s" % _interpreter())
        print("WorkingDirectory: %s" % _repo_paths()[0])
        for command in _launchctl_commands(plist):
            print("launchctl: %s" % command)


def setup(args):
    """Create publishing configuration and optionally a launchd job."""
    config = config_path()
    existing = _load_config()
    root, props, plist = _repo_paths()
    if args.dry_run:
        _print_setup_actions(existing, args, config, props, plist)
        return 0
    try:
        if existing and existing.get("gist_id") and existing.get("owner"):
            gid = existing["gist_id"]
            owner = existing["owner"]
            raw = existing.get("raw_url") or (
                "https://gist.githubusercontent.com/%s/%s/raw/usage.json" % (owner, gid)
            )
        else:
            payload = json.dumps(
                {
                    "description": "Wrist Usage snapshot",
                    "public": False,
                    "files": {
                        "usage.json": {
                            "content": json.dumps(collect_snapshot(), separators=(",", ":"))
                        }
                    },
                }
            ).encode()
            gist = json.loads(_run_gh(["api", "-X", "POST", "/gists", "--input", "-"], payload))
            owner = gist["owner"]["login"]
            gid = gist["id"]
            raw = "https://gist.githubusercontent.com/%s/%s/raw/usage.json" % (owner, gid)
    except (subprocess.CalledProcessError, FileNotFoundError, OSError, ValueError) as exc:
        print("gh error: %s" % _gh_error(exc), file=sys.stderr)
        return 1
    os.makedirs(os.path.dirname(config), exist_ok=True)
    with open(config, "w") as handle:
        json.dump({"gist_id": gid, "owner": owner, "raw_url": raw}, handle)
    os.chmod(config, 0o600)
    os.makedirs(os.path.dirname(props), exist_ok=True)
    _set_property(props, raw)
    if not args.no_launchd:
        _write_plist(root, plist)
        launchctl = "/bin/launchctl"
        if os.path.exists(launchctl):
            uid = str(os.getuid())
            subprocess.run(
                [launchctl, "bootout", "gui/" + uid, plist],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
            result = subprocess.run([launchctl, "bootstrap", "gui/" + uid, plist], check=False)
            if result.returncode != 0:
                print("launchctl bootstrap failed (exit %s)" % result.returncode, file=sys.stderr)
                return 1
    print(raw)
    print("Next steps:")
    print("Paste the URL above into the Wrist Usage phone app (Setup > Save).")
    print("The phone sends it to the watch. Run 'wrist-usage url' to see it again.")
    print("Logs: ~/Library/Logs/wrist-usage.log")
    print("Re-run setup if you move this folder.")
    return 0


def show_url():
    """Print the snapshot URL that the phone app needs."""
    config = _load_config()
    if not config or not config.get("raw_url"):
        print("Run setup first", file=sys.stderr)
        return 1
    print(config["raw_url"])
    return 0


def publish():
    """Publish the current snapshot to the configured gist."""
    config = _load_config()
    if not config or not config.get("gist_id"):
        print("Run setup first", file=sys.stderr)
        return 1
    payload = json.dumps(
        {"files": {"usage.json": {
            "content": json.dumps(collect_snapshot(), separators=(",", ":"))
        }}}
    ).encode()
    try:
        _run_gh(["api", "-X", "PATCH", "/gists/" + config["gist_id"], "--input", "-"], payload)
    except (subprocess.CalledProcessError, FileNotFoundError, OSError) as exc:
        print("gh error: %s" % _gh_error(exc), file=sys.stderr)
        return 1
    return 0


def uninstall(args):
    """Remove the launchd job and local config, optionally deleting the gist."""
    plist = os.path.expanduser("~/Library/LaunchAgents/%s.plist" % LABEL)
    config_file = config_path()
    config = _load_config()
    removed = []
    if os.path.exists(plist):
        launchctl = "/bin/launchctl"
        if os.path.exists(launchctl):
            subprocess.run(
                [launchctl, "bootout", "gui/" + str(os.getuid()), plist],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
        os.remove(plist)
        removed.append(plist)
    if args.delete_gist and config and config.get("gist_id"):
        try:
            _run_gh(["api", "-X", "DELETE", "/gists/" + config["gist_id"]])
            print("removed gist %s" % config["gist_id"])
        except (subprocess.CalledProcessError, FileNotFoundError, OSError) as exc:
            print("gh error: %s" % _gh_error(exc), file=sys.stderr)
            return 1
    if os.path.exists(config_file):
        os.remove(config_file)
        removed.append(config_file)
    if not args.delete_gist and config and config.get("gist_id"):
        print("gist still exists; delete it with gh gist delete %s" % config["gist_id"])
    for path in removed:
        print("removed %s" % path)
    if not removed and not (args.delete_gist and config and config.get("gist_id")):
        print("removed nothing")
    return 0
