#!/usr/bin/env bash
# End-to-end smoke test against a running instance. Needs only bash + curl.
#   ./scripts/smoke-test.sh                  # defaults to http://localhost:8080
#   BASE=http://host:port ADMIN_KEY=... ./scripts/smoke-test.sh
# ADMIN_KEY defaults to the local-dev key in docker-compose.yml. The script issues its own
# client keys with it, so it never needs any other secret.
set -u
BASE="${BASE:-http://localhost:8080}"
ADMIN_KEY="${ADMIN_KEY:-sk_local_dev_admin_change_me}"
PASS=0
FAIL=0
RUN="$(date +%s)"
ALIAS="smoke-$RUN"

check() { # name expected actual
  if [ "$2" = "$3" ]; then
    PASS=$((PASS + 1)); printf '  PASS  %s\n' "$1"
  else
    FAIL=$((FAIL + 1)); printf '  FAIL  %s (expected %s, got %s)\n' "$1" "$2" "$3"
  fi
}
status() { curl -s -o /dev/null -w '%{http_code}' "$@"; }
auth() { printf 'Authorization: Bearer %s' "$1"; }
create() { # key-or-empty json
  if [ -n "$1" ]; then
    curl -s -w '\n%{http_code}' -X POST "$BASE/api/v1/links" -H "$(auth "$1")" -H 'Content-Type: application/json' -d "$2"
  else
    curl -s -w '\n%{http_code}' -X POST "$BASE/api/v1/links" -H 'Content-Type: application/json' -d "$2"
  fi
}
issue_key() { # name -> prints the secret
  curl -s -X POST "$BASE/api/v1/admin/keys" -H "$(auth "$ADMIN_KEY")" -H 'Content-Type: application/json' \
    -d "{\"name\":\"$1\"}" | sed -n 's/.*"key":"\([^"]*\)".*/\1/p'
}
code_of() { printf '%s' "$1" | head -n1 | sed -n 's/.*"code":"\([^"]*\)".*/\1/p'; }
http_of() { printf '%s' "$1" | tail -n1; }

echo "Smoke testing $BASE"

check "readiness is UP"                 200 "$(status "$BASE/actuator/health/readiness")"

# ---- keys (Phase 3) ----
KEY=$(issue_key "smoke-$RUN")
OTHER=$(issue_key "smoke-other-$RUN")
check "admin can issue a client key"    "sk_" "${KEY:0:3}"

# ---- core (Phase 1) ----
out=$(create "$KEY" '{"url":"https://example.com/smoke?x=1"}')
code=$(code_of "$out")
check "create returns 201"              201 "$(http_of "$out")"
check "create returns a 7-char code"    7   "${#code}"

check "get link returns 200"            200 "$(status -H "$(auth "$KEY")" "$BASE/api/v1/links/$code")"
check "redirect returns 302"            302 "$(status "$BASE/$code")"
loc=$(curl -s -o /dev/null -w '%{redirect_url}' "$BASE/$code")
check "redirect Location is the long URL" "https://example.com/smoke?x=1" "$loc"
check "HEAD redirect returns 302"       302 "$(status -I "$BASE/$code")"

# ---- analytics (Phase 2): the two GET redirects count, the HEAD does not ----
clicks=""
for _ in 1 2 3 4 5 6 7 8 9 10; do
  clicks=$(curl -s -H "$(auth "$KEY")" "$BASE/api/v1/links/$code/stats" | sed -n 's/.*"totalClicks":\([0-9]*\).*/\1/p')
  [ "$clicks" = "2" ] && break
  sleep 0.5
done
check "stats count GET redirects only"  2   "$clicks"
check "stats with bad days returns 400" 400 "$(status -H "$(auth "$KEY")" "$BASE/api/v1/links/$code/stats?days=0")"

out=$(create "$KEY" "{\"url\":\"https://example.com\",\"alias\":\"$ALIAS\"}")
check "custom alias returns 201"        201 "$(http_of "$out")"
out=$(create "$KEY" "{\"url\":\"https://example.com\",\"alias\":\"$ALIAS\"}")
check "duplicate alias returns 409"     409 "$(http_of "$out")"
out=$(create "$KEY" '{"url":"javascript:alert(1)"}')
check "unsafe scheme returns 400"       400 "$(http_of "$out")"
out=$(create "$KEY" '{"url":"https://example.com","expiresAt":"2000-01-01T00:00:00Z"}')
check "past expiry returns 400"         400 "$(http_of "$out")"

check "list returns 200"                200 "$(status -H "$(auth "$KEY")" "$BASE/api/v1/links?limit=5")"
check "unknown code returns 404"        404 "$(status "$BASE/zzzzzzzz-unknown")"

# ---- abuse controls (Phase 3) ----
out=$(create "" '{"url":"https://example.com/anonymous"}')
check "anonymous create still works"    201 "$(http_of "$out")"
check "anonymous delete returns 401"    401 "$(status -X DELETE "$BASE/api/v1/links/$code")"
check "anonymous list returns 401"      401 "$(status "$BASE/api/v1/links")"
check "unknown key returns 401"         401 "$(status -H "$(auth sk_not_a_real_key)" "$BASE/api/v1/links")"
check "other key can't delete (404)"    404 "$(status -X DELETE -H "$(auth "$OTHER")" "$BASE/api/v1/links/$code")"
check "client key on admin API is 403"  403 "$(status -H "$(auth "$KEY")" "$BASE/api/v1/admin/keys")"
check "metrics need the admin key"      403 "$(status -H "$(auth "$KEY")" "$BASE/actuator/metrics")"
out=$(create "$KEY" '{"url":"https://bit.ly/3abcdef"}')
check "link to another shortener is 400" 400 "$(http_of "$out")"

DOMAIN="smoke-$RUN.example"
out=$(create "$KEY" "{\"url\":\"https://login.$DOMAIN/\"}")
phish=$(code_of "$out")
check "admin can block a domain"        201 "$(status -X POST -H "$(auth "$ADMIN_KEY")" -H 'Content-Type: application/json' \
                                              -d "{\"domain\":\"$DOMAIN\",\"reason\":\"smoke test\"}" "$BASE/api/v1/admin/blocked-domains")"
check "existing link to it is now 410"  410 "$(status "$BASE/$phish")"
check "admin can unblock it"            204 "$(status -X DELETE -H "$(auth "$ADMIN_KEY")" "$BASE/api/v1/admin/blocked-domains/$DOMAIN")"
check "and it redirects again"          302 "$(status "$BASE/$phish")"

# ---- delete (Phase 1, now owner-only) ----
check "owner delete returns 204"        204 "$(status -X DELETE -H "$(auth "$KEY")" "$BASE/api/v1/links/$code")"
check "deleted link redirect is 410"    410 "$(status "$BASE/$code")"
check "delete again is still 204"       204 "$(status -X DELETE -H "$(auth "$KEY")" "$BASE/api/v1/links/$code")"

echo "Result: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
