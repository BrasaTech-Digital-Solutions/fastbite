#!/usr/bin/env bash
# Release check: runs against the local Firebase-like proxy (http://fastbite.localhost:8088
# and http://sandbox.fastbite.localhost:8088), covering guest, owner, cashier staff, legal pages,
# order calculation, cash payment, backoffice image fragments, bundle export/import, static assets,
# and verifies 0 logged template or server errors in both production and sandbox.
set -uo pipefail

P="http://fastbite.localhost:8088"
S="http://sandbox.fastbite.localhost:8088"
T="check$RANDOM"
PASS="a-long-test-password-123"
JOBS_SECRET="${FASTBITE_JOBS_SECRET:-local-jobs-secret-for-docker-compose-only}"

# Resolve repo root before changing directory
ROOT=$(cd "$(dirname "$0")/.." && pwd)
DC="docker compose -f $ROOT/docker-compose.yml"

COLD=0
for arg in "$@"; do
  case "$arg" in
    --cold)
      COLD=1
      ;;
    -h|--help)
      echo "Usage: $0 [--cold]"
      echo "  --cold  Recreate production and sandbox containers to exercise cold starts before running check"
      exit 0
      ;;
    *)
      echo "Unknown option: $arg"
      echo "Usage: $0 [--cold]"
      exit 1
      ;;
  esac
done

SINCE=$(date -u +%Y-%m-%dT%H:%M:%SZ)

if [ "$COLD" -eq 1 ]; then
  echo "== Recreating production and sandbox containers (--cold)..."
  $DC up -d --force-recreate production sandbox
  echo "Waiting for production and sandbox to answer through proxy..."
  WAIT_DEADLINE=$((SECONDS + 60))
  while [ $SECONDS -lt $WAIT_DEADLINE ]; do
    PROD_STATUS=$(curl -s -o /dev/null -w '%{http_code}' "$P/legal/terms" || true)
    SAND_STATUS=$(curl -s -o /dev/null -w '%{http_code}' "$S/legal/terms" || true)
    if [ "${PROD_STATUS:-000}" = "200" ] && [ "${SAND_STATUS:-000}" = "200" ]; then
      break
    fi
    sleep 1
  done
fi

# Pre-flight check: ensure stack is running
PROD_STATUS=$(curl -s -o /dev/null -w '%{http_code}' "$P/legal/terms" || true)
SAND_STATUS=$(curl -s -o /dev/null -w '%{http_code}' "$S/legal/terms" || true)
PROD_STATUS=${PROD_STATUS:-000}
SAND_STATUS=${SAND_STATUS:-000}
if [ "$PROD_STATUS" != "200" ] || [ "$SAND_STATUS" != "200" ]; then
  echo "Stack is not responding on $P and $S (production: $PROD_STATUS, sandbox: $SAND_STATUS)."
  echo "Start the stack with: docker compose up -d"
  exit 2
fi

# Work directory for cookies and temporary files, cleaned up on exit
WORK_DIR=$(mktemp -d -t fastbite-release-check-XXXXXX)
trap 'cd "$ROOT"; rm -rf "$WORK_DIR"' EXIT

# cd into WORK_DIR so relative filenames work portably across MSYS and Linux
cd "$WORK_DIR"

G="guest.cookies"
J="owner.cookies"
C="cashier.cookies"
BUNDLE="bundle.zip"
CART_OUT="cart.json"
GALLERY_OUT="gallery.html"
PROD_MENU_OUT="prod_menu.html"

TOTAL=0
FAILED=0

csrf() { grep -o 'name="_csrf"[^>]*value="[^"]*"' | head -1 | sed 's/.*value="//; s/"$//'; }
meta() { grep -o 'name="_csrf" content="[^"]*"\|name="csrf-token" content="[^"]*"' | head -1 | sed 's/.*content="//; s/"$//'; }
loc() { grep -i '^location:' | tr -d '\r' | sed 's/^[Ll]ocation: //'; }

record() {
  local code="$1"
  local expected="$2"
  local desc="$3"
  TOTAL=$((TOTAL + 1))
  if [ "$code" -eq "$expected" ]; then
    printf '%-4s %s\n' "$code" "$desc"
  else
    printf '%-4s %s (FAILED, expected %s)\n' "$code" "$desc" "$expected"
    FAILED=$((FAILED + 1))
  fi
}

record_match() {
  local matched="$1"
  local desc="$2"
  TOTAL=$((TOTAL + 1))
  if [ "$matched" -eq 1 ]; then
    printf 'OK   %s\n' "$desc"
  else
    printf 'FAIL %s\n' "$desc"
    FAILED=$((FAILED + 1))
  fi
}

check() {
  local cookie_jar="$1"
  local url="$2"
  local code
  code=$(curl -s -o /dev/null -w '%{http_code}' -b "$cookie_jar" -c "$cookie_jar" "$url" || true)
  record "${code:-000}" 200 "$url"
}

echo "== [1/6] Setting up $T: signup in production, cafe concept, cash payment in sandbox"
SIGNUP_HTML=$(curl -s -c "$J" -b "$J" "$P/signup")
TOKEN=$(echo "$SIGNUP_HTML" | csrf)
SIGNUP_CODE=$(curl -s -o /dev/null -w '%{http_code}' -c "$J" -b "$J" "$P/signup" \
  --data-urlencode "_csrf=$TOKEN" \
  --data-urlencode "tenantId=$T" \
  --data-urlencode "username=${T}owner" \
  --data-urlencode "password=$PASS" \
  --data-urlencode "fullName=Release Owner")
record "$SIGNUP_CODE" 200 "POST $P/signup"

LOGIN_HTML=$(curl -s -c "$J" -b "$J" "$P/login")
TOKEN=$(echo "$LOGIN_HTML" | csrf)
LOGIN_RESP=$(curl -s -i -c "$J" -b "$J" "$P/login" \
  --data-urlencode "_csrf=$TOKEN" \
  --data-urlencode "username=${T}owner" \
  --data-urlencode "password=$PASS")
LOGIN_CODE=$(echo "$LOGIN_RESP" | head -n 1 | awk '{print $2}')
LOGIN_LOC=$(echo "$LOGIN_RESP" | loc)
if [ "$LOGIN_CODE" = "302" ] && [[ "$LOGIN_LOC" =~ /owner/console ]]; then
  record "302" 302 "POST $P/login (redirect to $LOGIN_LOC)"
else
  record "${LOGIN_CODE:-000}" 302 "POST $P/login (redirect to owner console, got $LOGIN_LOC)"
fi

LINK_RESP=$(curl -s -D - -o /dev/null -c "$J" -b "$J" "$P/owner/sandbox?location=$T")
LINK=$(echo "$LINK_RESP" | loc)
if [[ "$LINK" =~ ^$S/link/enter ]]; then
  record_match 1 "GET $P/owner/sandbox (location starts with $S/link/enter)"
else
  record_match 0 "GET $P/owner/sandbox (expected location to start with $S/link/enter, got $LINK)"
fi

ENTER_CODE=$(curl -s -o /dev/null -w '%{http_code}' -c "$J" -b "$J" "$LINK")
record "$ENTER_CODE" 302 "GET $LINK (enter sandbox)"

ST=$(curl -s -c "$J" -b "$J" "$S/owner/console?location=$T" | csrf)
ADOPT_CODE=$(curl -s -o /dev/null -w '%{http_code}' -c "$J" -b "$J" "$S/owner/workspace/$T/setup" \
  --data-urlencode "_csrf=$ST" \
  -d "action=adopt&template=cafe&theme=cafe")
record "$ADOPT_CODE" 302 "POST $S/owner/workspace/$T/setup (adopt cafe)"

# Query product and price from sandbox DB
PRODUCT=$($DC exec -T postgres psql -U fastbite -d fastbite_sandbox -tAc "select id from tenant_$T.products order by id limit 1" 2>/dev/null | tr -d '\r')
if [ -n "$PRODUCT" ]; then
  record_match 1 "Query demo product from sandbox DB ($PRODUCT)"
  PRICE=$($DC exec -T postgres psql -U fastbite -d fastbite_sandbox -tAc "select price from tenant_$T.products where id='$PRODUCT'" 2>/dev/null | tr -d '\r')
else
  record_match 0 "Query demo product from sandbox DB (empty product list)"
  PRICE=""
fi

# Place cash order at counter if product found
TICKET=""
if [ -n "$PRODUCT" ]; then
  CT=$(curl -s -c "$J" -b "$J" "$S/$T/counter" | meta)
  ORDER_CODE=$(curl -s -o /dev/null -w '%{http_code}' -c "$J" -b "$J" \
    -H "Content-Type: application/json" \
    -H "X-CSRF-TOKEN: $CT" \
    -H "Referer: $S/$T/counter" \
    -d "{\"items\":[{\"id\":\"l1\",\"itemId\":\"$PRODUCT\",\"quantity\":2,\"customizations\":[]}],\"paid\":true,\"paymentMethod\":\"CASH\",\"cashReceived\":50}" \
    "$S/counter/api/order")
  record "$ORDER_CODE" 200 "POST $S/counter/api/order (cash payment)"

  TICKET=$($DC exec -T postgres psql -U fastbite -d fastbite_sandbox -tAc "select id from tenant_$T.fiscal_tickets order by issued_local desc limit 1" 2>/dev/null | tr -d '\r')
  if [ -n "$TICKET" ]; then
    record_match 1 "Fiscal ticket created in sandbox DB ($TICKET)"
  else
    record_match 0 "Fiscal ticket created in sandbox DB (ticket ID was empty)"
  fi
fi

echo "== [2/6] Team member & Cashier flow"
OWNER_CSRF=$(curl -s -c "$J" -b "$J" "$S/owner/console?view=team&location=$T" | meta)
ADD_STAFF_CODE=$(curl -s -o /dev/null -w '%{http_code}' -b "$J" -c "$J" \
  -H "X-CSRF-TOKEN: $OWNER_CSRF" \
  -H "Referer: $S/owner/console?view=team&location=$T" \
  --data-urlencode "username=${T}cashier" \
  --data-urlencode "fullName=Cashier Staff" \
  --data-urlencode "password=$PASS" \
  --data-urlencode "role=CASHIER" \
  "$S/owner/locations/$T/users")
record "$ADD_STAFF_CODE" 200 "POST $S/owner/locations/$T/users (add cashier)"

CASHIER_LOGIN_CSRF=$(curl -s -c "$C" -b "$C" "$S/$T/login" | csrf)
CASHIER_AUTH_CODE=$(curl -s -o /dev/null -w '%{http_code}' -c "$C" -b "$C" \
  --data-urlencode "_csrf=$CASHIER_LOGIN_CSRF" \
  --data-urlencode "username=${T}cashier" \
  --data-urlencode "password=$PASS" \
  "$S/login?tenantId=$T")
record "$CASHIER_AUTH_CODE" 302 "POST $S/login?tenantId=$T (cashier login)"

for path in "/$T/counter" "/$T/counter/tickets" "/$T/counter/day-report" "/$T/counter/api/cash" "/$T/dashboard"; do
  check "$C" "$S$path"
done
if [ -n "$TICKET" ]; then
  check "$C" "$S/$T/counter/tickets/$TICKET"
fi

# Cashier limits: 403 on table QR codes
QR_CODE=$(curl -s -o /dev/null -w '%{http_code}' -b "$C" -c "$C" "$S/$T/backoffice/tables/qr-codes")
record "$QR_CODE" 403 "GET $S/$T/backoffice/tables/qr-codes (cashier forbidden)"

echo "== [3/6] Guest surfaces & Legal pages"
for path in "/$T/menu" "/$T/allergens" "/cake/menu" "/cake/allergens" "/verifactu/declaracion-responsable"; do
  check "$G" "$S$path"
done
for lang in es en pt; do
  for path in /legal/notice /legal/privacy /legal/cookies /legal/terms /legal/data-processing "/$T/privacy"; do
    check "$G" "$S$path?lang=$lang"
  done
done
for path in /legal/terms "/$T/privacy"; do
  check "$G" "$P$path"
done

# Guest calculate cart: verify 200 and IVA/VAT mention
if [ -n "$PRODUCT" ] && [ -n "$PRICE" ]; then
  GT=$(curl -s -b "$G" -c "$G" "$S/$T/menu" | meta)
  CART_CODE=$(curl -s -o "$CART_OUT" -w '%{http_code}' -b "$G" -c "$G" \
    -H 'Content-Type: application/json' \
    -H "X-CSRF-TOKEN: $GT" \
    -d "[{\"id\":\"l1\",\"itemId\":\"$PRODUCT\",\"name\":\"x\",\"quantity\":2,\"customizations\":[],\"price\":$PRICE}]" \
    "$S/$T/api/calculate-cart")
  record "$CART_CODE" 200 "POST $S/$T/api/calculate-cart"
  if grep -q -E "IVA|VAT" "$CART_OUT" 2>/dev/null; then
    record_match 1 "Cart calculation contains IVA/VAT"
  else
    record_match 0 "Cart calculation contains IVA/VAT"
  fi

  # Guest online Stripe checkout (when STRIPE_SECRET_KEY is configured in sandbox container)
  SANDBOX_ID=$($DC ps -q sandbox 2>/dev/null || true)
  if [ -n "$SANDBOX_ID" ] && docker inspect "$SANDBOX_ID" --format '{{range .Config.Env}}{{println .}}{{end}}' 2>/dev/null | grep -qE '^STRIPE_SECRET_KEY=.+'; then
    GUEST_ORDER_OUT="guest_order.json"
    ORDER_PAYLOAD="{\"items\":[{\"id\":\"l1\",\"itemId\":\"$PRODUCT\",\"quantity\":1,\"price\":$PRICE}],\"customerName\":\"Release Check Guest\",\"paymentMethod\":\"stripe\"}"
    GUEST_ORDER_CODE=$(curl -s -o "$GUEST_ORDER_OUT" -w '%{http_code}' -b "$G" -c "$G" \
      -H 'Content-Type: application/json' \
      -H "X-CSRF-TOKEN: $GT" \
      -H "Referer: $S/$T/menu" \
      -d "$ORDER_PAYLOAD" \
      "$S/$T/api/create-order")
    record "$GUEST_ORDER_CODE" 200 "POST $S/$T/api/create-order (guest takeaway order)"

    CHECKOUT_OUT="checkout_session.json"
    CHECKOUT_CODE=$(curl -s -o "$CHECKOUT_OUT" -w '%{http_code}' -b "$G" -c "$G" \
      -H 'Content-Type: application/json' \
      -H "X-CSRF-TOKEN: $GT" \
      -H "Referer: $S/$T/select-payment" \
      -d '{"tipPercent":10}' \
      "$S/$T/api/stripe/create-checkout-session")
    record "$CHECKOUT_CODE" 200 "POST $S/$T/api/stripe/create-checkout-session"
    if grep -q "checkout.stripe.com" "$CHECKOUT_OUT" 2>/dev/null; then
      record_match 1 "Stripe checkout URL returned (checkout.stripe.com)"
    else
      record_match 0 "Stripe checkout URL returned (expected checkout.stripe.com in response)"
    fi
  else
    echo "SKIP Guest online Stripe checkout (STRIPE_SECRET_KEY not set in sandbox container)"
  fi
fi

echo "== [4/6] Owner surfaces & Backoffice fragments"
for path in "/$T/counter" "/$T/counter/tickets" "/$T/counter/day-report" "/$T/counter/api/cash" \
    "/$T/dashboard" "/$T/backoffice" "/counter/fragments/categories" "/counter/fragments/products" "/counter/fragments/tables" \
    "/owner/console?location=$T" "/owner/console?view=locations" "/owner/console?view=team&location=$T" \
    "/owner/console?view=settings" "/owner/console?view=location-settings&location=$T"; do
  check "$J" "$S$path"
done
if [ -n "$TICKET" ]; then
  check "$J" "$S/$T/counter/tickets/$TICKET"
fi
for section in legal appearance payments backup advanced; do
  check "$J" "$S/owner/console?view=location-settings&section=$section&location=$T"
done

# Invalid legal form submission: assert 302 and flash draft preserved across redirect
LEGAL_PAGE=$(curl -s -b "$J" -c "$J" "$S/owner/console?view=location-settings&section=legal&location=$T")
LEGAL_CSRF=$(echo "$LEGAL_PAGE" | meta)
LEGAL_RESP=$(curl -s -D - -o /dev/null -b "$J" -c "$J" \
  -H "Referer: $S/owner/console?view=location-settings&section=legal&location=$T" \
  --data-urlencode "_csrf=$LEGAL_CSRF" \
  --data-urlencode "legalName=Test Invalid Cafe" \
  --data-urlencode "taxId=INVALID_NIF_999" \
  "$S/owner/workspace/$T/legal")
LEGAL_CODE=$(echo "$LEGAL_RESP" | head -n 1 | awk '{print $2}')
LEGAL_LOC=$(echo "$LEGAL_RESP" | loc)
record "${LEGAL_CODE:-000}" 302 "POST $S/owner/workspace/$T/legal (invalid tax ID redirect)"

LEGAL_REDIRECT_PAGE=$(curl -s -b "$J" -c "$J" "$LEGAL_LOC")
if echo "$LEGAL_REDIRECT_PAGE" | grep -q "INVALID_NIF_999"; then
  record_match 1 "Invalid legal form draft preserved on redirect (INVALID_NIF_999)"
else
  record_match 0 "Invalid legal form draft preserved on redirect (INVALID_NIF_999 not found)"
fi

# Backoffice image gallery fragment: verify 200 and 'burgers' folder name
BT=$(curl -s -b "$J" -c "$J" "$S/$T/backoffice" | meta)
GALLERY_CODE=$(curl -s -o "$GALLERY_OUT" -w '%{http_code}' -b "$J" -c "$J" \
  -H 'Content-Type: application/json' \
  -H "X-CSRF-TOKEN: $BT" \
  -H "Referer: $S/$T/backoffice" \
  -d '{"type":"user","imagesByFolder":{"burgers":[{"url":"/user-images/x.png","name":"x.png"}]}}' \
  "$S/api/backoffice/fragments/image-gallery")
record "$GALLERY_CODE" 200 "POST $S/api/backoffice/fragments/image-gallery"
if grep -q "burgers" "$GALLERY_OUT" 2>/dev/null; then
  record_match 1 "Image gallery fragment contains folder 'burgers'"
else
  record_match 0 "Image gallery fragment contains folder 'burgers'"
fi

echo "== [5/6] Production bundle export & import"
EXPORT_CODE=$(curl -s -w '%{http_code}' -b "$J" -c "$J" \
  -o "$BUNDLE" \
  "$S/$T/api/backoffice/maintenance/export-production-bundle")
record "$EXPORT_CODE" 200 "GET $S/$T/api/backoffice/maintenance/export-production-bundle"

# Import bundle into production (using relative file path inside WORK_DIR)
PROD_CSRF=$(curl -s -b "$J" -c "$J" "$P/owner/console?view=location-settings&section=backup&location=$T" | meta)
IMPORT_CODE=$(curl -s -o /dev/null -w '%{http_code}' -b "$J" -c "$J" \
  -H "X-CSRF-TOKEN: $PROD_CSRF" \
  -F "file=@$BUNDLE;type=application/zip" \
  "$P/$T/api/backoffice/maintenance/import-production-bundle")
record "$IMPORT_CODE" 200 "POST $P/$T/api/backoffice/maintenance/import-production-bundle"

# Verify production menu now serves the imported products
PROD_MENU_CODE=$(curl -s -o "$PROD_MENU_OUT" -w '%{http_code}' -b "$G" -c "$G" "$P/$T/menu")
record "$PROD_MENU_CODE" 200 "GET $P/$T/menu (after promotion bundle import)"
if [ $(grep -c -i 'product' "$PROD_MENU_OUT" 2>/dev/null || echo 0) -gt 0 ]; then
  record_match 1 "Production menu contains imported products"
else
  record_match 0 "Production menu contains imported products"
fi

echo "== [6/6] Static assets, Self-hosted fonts & Scheduled jobs endpoint"
for asset in "/js/live-updates.js" "/css/fonts.css" "/css/fonts/dm-sans-latin.woff2" "/css/fonts/manrope-latin.woff2" "/js/qrcode.min.js"; do
  check "$G" "$S$asset"
  check "$G" "$P$asset"
done

for base in "$P" "$S"; do
  UNAUTH_JOBS_CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$base/internal/jobs/run" || true)
  record "${UNAUTH_JOBS_CODE:-000}" 401 "POST $base/internal/jobs/run (unauthorized without secret)"

  AUTH_JOBS_CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST \
    -H "X-FastBite-Jobs-Secret: $JOBS_SECRET" \
    "$base/internal/jobs/run" || true)
  record "${AUTH_JOBS_CODE:-000}" 200 "POST $base/internal/jobs/run (authenticated with secret)"
done

echo "== Checking logs for errors since run start ($SINCE)"
ERROR_PATTERN="TemplateProcessingException|SpelEvaluationException|MissingReflectionRegistrationError| ERROR "
PROD_ERRORS=$($DC logs --since "$SINCE" production 2>&1 | grep -E "$ERROR_PATTERN" || true)
SANDBOX_ERRORS=$($DC logs --since "$SINCE" sandbox 2>&1 | grep -E "$ERROR_PATTERN" || true)

ERR_COUNT=0
if [ -n "$PROD_ERRORS" ]; then
  echo "Production errors detected:"
  echo "$PROD_ERRORS"
  ERR_COUNT=$((ERR_COUNT + $(echo "$PROD_ERRORS" | wc -l)))
fi

if [ -n "$SANDBOX_ERRORS" ]; then
  echo "Sandbox errors detected:"
  echo "$SANDBOX_ERRORS"
  ERR_COUNT=$((ERR_COUNT + $(echo "$SANDBOX_ERRORS" | wc -l)))
fi

echo "========================================================"
echo "SUMMARY: Checked $TOTAL items, $FAILED failures, $ERR_COUNT errors logged."
echo "========================================================"

if [ "$FAILED" -ne 0 ] || [ "$ERR_COUNT" -ne 0 ]; then
  exit 1
fi

exit 0
