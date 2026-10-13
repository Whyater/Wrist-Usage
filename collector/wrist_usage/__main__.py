"""Command line argument parsing for wrist-usage."""

import argparse
import json
import sys

from .publish import publish, setup, uninstall
from .server import serve
from .snapshot import collect_snapshot


def main(argv=None):
    """Parse arguments and dispatch one command."""
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command", required=True)
    subparsers.add_parser("collect")
    serve_parser = subparsers.add_parser("serve")
    serve_parser.add_argument("--host", default="127.0.0.1")
    serve_parser.add_argument("--port", type=int, default=8765)
    setup_parser = subparsers.add_parser("setup")
    setup_parser.add_argument("--dry-run", action="store_true")
    setup_parser.add_argument("--no-launchd", action="store_true")
    subparsers.add_parser("publish")
    uninstall_parser = subparsers.add_parser("uninstall")
    uninstall_parser.add_argument("--delete-gist", action="store_true")
    args = parser.parse_args(argv)
    if args.command == "collect":
        print(json.dumps(collect_snapshot(), indent=2, separators=(",", ": ")))
        return 0
    if args.command == "serve":
        serve(args.host, args.port)
        return 0
    if args.command == "setup":
        return setup(args)
    if args.command == "publish":
        return publish()
    return uninstall(args)


if __name__ == "__main__":
    sys.exit(main())
