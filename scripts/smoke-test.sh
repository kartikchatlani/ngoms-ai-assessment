#!/usr/bin/env bash
# End-to-end smoke test against a running instance. Needs only bash + curl.
#   ./scripts/smoke-test.sh                  # defaults to http://localhost:8080
#   BASE=http://host:port ./scripts/smoke-test.sh
set -u
BASE="${BASE:-http://localhost:8080}"
PASS=0
FAIL=0
ALIAS="smoke-$(date +%s)"

check() { # name expected actual
  if [ "$2" = "$3" ]; then
    PASS=$((PASS + 1)); printf '  PASS  %s\n' "$1"
  else
    FAIL=$((FAIL + 1)); printf '  FAIL  %s (expected %s, got %s)\n' "$1" "$2" "$3"
  fi
}
status() { curl -s -o /dev/null -w '%{http_code}' "$@"; }
create() { curl -s -w '\n%{http_code}' -X POST "$BASE/api/v1/links" -H 'Content-Type: application/json' -d "$1"; }

echo "Smoke testing $BASE"

check "readiness is UP"                 200 "$(status "$BASE/actuator/health/readiness")"

out=$(create '{"url":"https://example.com/smoke?x=1"}')
code=$(printf '%s' "$out" | head -n1 | sed -n 's/.*"code":"\([^"]*\)".*/\1/p')
check "create returns 201"              201 "$(printf '%s' "$out" | tail -n1)"
check "create returns a 7-char code"    7   "${#code}"

check "get link returns 200"            200 "$(status "$BASE/api/v1/links/$code")"
check "redirect returns 302"            302 "$(status "$BASE/$code")"
loc=$(curl -s -o /dev/null -w '%{redirect_url}' "$BASE/$code")
check "redirect Location is the long URL" "https://example.com/smoke?x=1" "$loc"

out=$(create "{\"url\":\"https://example.com\",\"alias\":\"$ALIAS\"}")
check "custom alias returns 201"        201 "$(printf '%s' "$out" | tail -n1)"
out=$(create "{\"url\":\"https://example.com\",\"alias\":\"$ALIAS\"}")
check "duplicate alias returns 409"     409 "$(printf '%s' "$out" | tail -n1)"
out=$(create '{"url":"javascript:alert(1)"}')
check "unsafe scheme returns 400"       400 "$(printf '%s' "$out" | tail -n1)"
out=$(create '{"url":"https://example.com","expiresAt":"2000-01-01T00:00:00Z"}')
check "past expiry returns 400"         400 "$(printf '%s' "$out" | tail -n1)"

check "list returns 200"                200 "$(status "$BASE/api/v1/links?limit=5")"
check "unknown code returns 404"        404 "$(status "$BASE/zzzzzzzz-unknown")"
check "delete returns 204"              204 "$(status -X DELETE "$BASE/api/v1/links/$code")"
check "deleted link redirect is 410"    410 "$(status "$BASE/$code")"
check "delete again is still 204"       204 "$(status -X DELETE "$BASE/api/v1/links/$code")"

echo "Result: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
