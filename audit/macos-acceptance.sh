#!/bin/sh
set -eu

PACKAGE_ROOT=${1:-}
if [ -z "$PACKAGE_ROOT" ]; then
  printf '%s\n' 'Usage: ./macos-acceptance.sh <extracted-package-root>' >&2
  exit 2
fi
ROOT=$(CDPATH= cd -- "$PACKAGE_ROOT" && pwd)
RESULTS="$ROOT/data/diagnostics/macos-acceptance"
mkdir -p "$RESULTS"
LOG="$RESULTS/server.log"
REPORT="$RESULTS/result.txt"
PORT=$((18000 + ($$ % 20000)))
PID=''

cleanup() {
  if [ -n "$PID" ] && kill -0 "$PID" 2>/dev/null; then
    kill "$PID" 2>/dev/null || true
    wait "$PID" 2>/dev/null || true
  fi
}
trap cleanup EXIT HUP INT TERM

pass() { printf 'PASS %s\n' "$1" | tee -a "$REPORT"; }
fail() { printf 'FAIL %s\n' "$1" | tee -a "$REPORT" >&2; exit 1; }
note() { printf 'INFO %s\n' "$1" | tee -a "$REPORT"; }

: > "$REPORT"
note "startedAt=$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
note "os=$(sw_vers -productVersion) arch=$(uname -m)"

"$ROOT/preflight-macos.sh" "$ROOT" >> "$REPORT" 2>&1 || fail 'environment preflight'
pass 'environment preflight and component hashes'

"$ROOT/start-macos.command" --no-open --port "$PORT" > "$LOG" 2>&1 &
PID=$!
ready=false
attempt=0
while [ "$attempt" -lt 90 ]; do
  if curl -fsS --max-time 2 -H "Host: 127.0.0.1:$PORT" "http://127.0.0.1:$PORT/api/health" > "$RESULTS/health.json" 2>/dev/null; then
    ready=true
    break
  fi
  kill -0 "$PID" 2>/dev/null || { tail -n 80 "$LOG" >&2; fail 'service exited during startup'; }
  attempt=$((attempt + 1))
  sleep 1
done
[ "$ready" = true ] || fail 'service did not become ready in 90 seconds'
pass 'first launch and health endpoint'

listeners=$(lsof -Pan -p "$PID" -iTCP -sTCP:LISTEN 2>/dev/null || true)
printf '%s\n' "$listeners" > "$RESULTS/listeners.txt"
printf '%s\n' "$listeners" | grep -q '127.0.0.1' || fail 'service is not listening on IPv4 loopback'
if printf '%s\n' "$listeners" | grep -Eq ' (\*|0\.0\.0\.0|\[::\]):'; then fail 'service exposed a non-loopback listener'; fi
pass 'loopback-only listener'

host_status=$(curl -sS -o /dev/null -w '%{http_code}' --max-time 3 -H 'Host: attacker.invalid' "http://127.0.0.1:$PORT/api/jobs" || true)
[ "$host_status" = 421 ] || fail "Host header rejection returned HTTP $host_status"
pass 'Host header rejection'

if "$ROOT/runtime/bin/java" -jar "$ROOT/app/doc-redaction-poc.jar" --data "$ROOT/data" --no-open --port $((PORT + 1)) > "$RESULTS/second-instance.log" 2>&1; then
  fail 'second service instance was not blocked'
fi
grep -q 'still running' "$RESULTS/second-instance.log" || fail 'second-instance failure was not attributable to the runtime lock'
pass 'single-instance/update lock'

connections=$(lsof -Pan -p "$PID" -iTCP -sTCP:ESTABLISHED 2>/dev/null || true)
printf '%s\n' "$connections" > "$RESULTS/established-connections.txt"
if printf '%s\n' "$connections" | grep -Ev '(^COMMAND|127\.0\.0\.1|\[::1\])' | grep -q .; then
  fail 'unexpected non-loopback established TCP connection'
fi
pass 'no observed non-loopback established connection'

UNICODE_ROOT="$RESULTS/路径兼容"
NFC="$UNICODE_ROOT/简体中文-é"
NFD="$UNICODE_ROOT/简体中文-é"
mkdir -p "$NFC" "$NFD"
"$ROOT/runtime/bin/java" -jar "$ROOT/app/doc-redaction-poc.jar" --generate-samples "$NFC" >> "$REPORT" 2>&1
cp "$NFC/sample.docx" "$NFD/身份证11010519491231002X.docx"
"$ROOT/runtime/bin/java" -jar "$ROOT/app/doc-redaction-poc.jar" --process \
  "$NFD/身份证11010519491231002X.docx" "$NFD/已脱敏.docx" >> "$REPORT" 2>&1
[ -s "$NFD/已脱敏.docx" ] || fail 'NFC/NFD Chinese-path processing did not produce output'
pass 'Chinese NFC/NFD path and direct DOCX processing'

kill "$PID"
wait "$PID" || true
PID=''
pass 'clean shutdown'

if [ "${DOC_REDACTION_REQUIRE_SIGNED:-0}" = 1 ]; then
  codesign --verify --deep --strict "$ROOT" >> "$REPORT" 2>&1 || fail 'Developer ID signature verification'
  spctl --assess --type execute --verbose=2 "$ROOT/start-macos.command" >> "$REPORT" 2>&1 || fail 'Gatekeeper assessment'
  pass 'Developer ID signature and Gatekeeper assessment'
else
  note 'signature/notarization gate not requested; set DOC_REDACTION_REQUIRE_SIGNED=1 for release acceptance'
fi

note "completedAt=$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
printf 'MACOS_ACCEPTANCE_PASS=%s\n' "$REPORT"
