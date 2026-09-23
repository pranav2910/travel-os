#!/usr/bin/env bash
# The after-phase suites, in order, each logged under ../evidence/logs/. Stops nothing on a failing
# suite (every row is in results.csv either way); exits non-zero if any suite crashed.
set -uo pipefail
cd "$(dirname "$0")"
mkdir -p ../evidence/logs
WEB="$(cd ../../../web && pwd)"
rc=0
run() { # name command...
  local name="$1"; shift
  echo "== $(date -u +%H:%M:%S) $name"
  if "$@" > "../evidence/logs/$name.log" 2>&1; then echo "   ok"; else echo "   exit $? (see evidence/logs/$name.log)"; rc=1; fi
}
run repair-after   python3 repair_regressions.py --phase after
run p01-followup   python3 p01_followup.py
run p01-journey    env NODE_PATH="$WEB/node_modules" node p01_journey.js
run p01-failures   python3 p01_failures.py
run p03-trips      python3 p03_trips.py
run p04-routes     python3 p04_routes.py
run p05-policy     python3 p05_policy_money.py
run p05-followup   python3 p05_followup.py
run p06-styles     python3 p06_booking_styles.py
run p09-workflow   python3 p09_workflow.py
run p02-session    env NODE_PATH="$WEB/node_modules" node p02_session.js
run p10-frontend   env NODE_PATH="$WEB/node_modules" node p10_frontend.js
run p10b-keyboard  env NODE_PATH="$WEB/node_modules" node p10b_keyboard.js
run p11-security   python3 p11_security.py
run p12-outages    python3 p12_outages.py
run report         python3 report.py
echo "== done rc=$rc"
exit $rc
