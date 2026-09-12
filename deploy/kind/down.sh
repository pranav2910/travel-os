#!/usr/bin/env bash
set -euo pipefail
kind delete cluster --name travelos || true
docker rm -f kind-registry >/dev/null 2>&1 || true
