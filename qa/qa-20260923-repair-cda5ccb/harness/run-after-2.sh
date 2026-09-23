#!/usr/bin/env bash
# Second pass after the edge fix (CSP frame-src, connect timeout): the browser suites, the security
# probe, the two route/style rows the first pass could not judge, the two standalone rows, the
# re-evaluations, then the summary. Logged like run-after.sh.
set -uo pipefail
cd "$(dirname "$0")"
mkdir -p ../evidence/logs
WEB="$(cd ../../../web && pwd)"
rc=0
run() { local name="$1"; shift; echo "== $(date -u +%H:%M:%S) $name"; if "$@" > "../evidence/logs/$name.log" 2>&1; then echo "   ok"; else echo "   exit $? (see evidence/logs/$name.log)"; rc=1; fi; }
run p02-session-2   env NODE_PATH="$WEB/node_modules" node p02_session.js
run p10-frontend-2  env NODE_PATH="$WEB/node_modules" node p10_frontend.js
run p10b-keyboard-2 env NODE_PATH="$WEB/node_modules" node p10b_keyboard.js
run p11-security-2  python3 p11_security.py
run p04-R04         env P4_ONLY=R04 python3 p04_routes.py
run p06-ML04        env P6_ONLY=P6-ML-04 python3 p06_booking_styles.py
run repair-extra    python3 repair_extra.py
run reeval          python3 reeval.py
run report-2        python3 report.py
echo "== done rc=$rc"
exit $rc
