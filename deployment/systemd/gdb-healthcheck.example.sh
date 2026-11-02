#!/usr/bin/env bash
set -euo pipefail

# Read-only health check. Review the URL and alerting integration before use.
curl --fail --silent --show-error --max-time 10 http://127.0.0.1:8092/actuator/health >/dev/null
echo "GDB health check passed"
