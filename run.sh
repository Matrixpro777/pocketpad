#!/usr/bin/env bash
# Lanza el servidor Phonepad (Linux).
set -euo pipefail
cd "$(dirname "$0")"
exec python3 -m phonepad.server "$@"
