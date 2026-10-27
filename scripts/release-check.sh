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
  CONSECUTIVE=0
  while [ $SECONDS -lt $WAIT_DEADLINE ]; do
    PROD_BODY=$(curl -s -f "$P/legal/terms" || true)
    SAND_BODY=$(curl -s -f "$S/legal/terms" || true)
    if [ -n "$PROD_BODY" ] && ! echo "$PROD_BODY" | grep -qF '[Sandbox]' \
       && echo "$SAND_BODY" | grep -qF '[Sandbox]'; then
      CONSECUTIVE=$((CONSECUTIVE + 1))
      if [ "$CONSECUTIVE" -ge 3 ]; then
        break
      fi
    else
      CONSECUTIVE=0
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
  local ok=0
  if [ "$code" = "$expected" ]; then
    ok=1
  elif [ "$expected" = "302" ] && [ "$code" = "303" ]; then
    ok=1
  fi
  if [ "$ok" -eq 1 ]; then
    printf '%-4s %s\n' "$code" "$desc"
  else
    printf '%-4s %s (FAILED, expected %s)\n' "$code" "$desc" "$expected"
    FAILED=$((FAILED + 1))
  fi
}

record_redirect() {
  local code="$1"
  local loc="$2"
  local expected_proxy="$3"
  local expected_path="$4"
  local desc="$5"
  TOTAL=$((TOTAL + 1))
  local ok=1
  if [ "$code" != "302" ] && [ "$code" != "303" ]; then
    ok=0
  fi
  if echo "$loc" | grep -qE '^https?://(production|sandbox)(:[0-9]+)?(/|$)|\.run\.app|:8080(/|$)'; then
    ok=0
  fi
  if ! [[ "$loc" =~ ^/ ]] && ! [[ "$loc" =~ ^$expected_proxy/ ]]; then
    ok=0
  fi
  if [ -n "$expected_path" ] && ! echo "$loc" | grep -qF "$expected_path"; then
    ok=0
  fi
  if [ "$ok" -eq 1 ]; then
    printf '%-4s %s (Location: %s)\n' "$code" "$desc" "$loc"
  else
    printf '%-4s %s (FAILED, Location: %s)\n' "${code:-000}" "$desc" "${loc:-(none)}"
    FAILED=$((FAILED + 1))
  fi
}

check_redirect() {
  local url="$1"
  local expected_proxy="$2"
  local expected_path="$3"
  local headers
  headers=$(curl -s -i "$url" | tr -d '\r')
  local code
  code=$(echo "$headers" | head -n 1 | awk '{print $2}')
  local location
  location=$(echo "$headers" | grep -i '^location:' | head -n 1 | sed 's/^[Ll]ocation: //')
  record_redirect "$code" "$location" "$expected_proxy" "$expected_path" "GET $url"
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

echo "== [0/6] Host header & redirect isolation"
check_redirect "$P/owner/console" "$P" "/login"
check_redirect "$P/cake/counter" "$P" "/cake/login"
check_redirect "$S/owner/console" "$S" "/login"
check_redirect "$S/cake/counter" "$S" "/cake/login"

echo "== [1/6] Setting up $T: signup in production, cafe concept, cash payment in sandbox"
SIGNUP_HTML=$(curl -s -c "$J" -b "$J" "$P/signup")
TOKEN=$(echo "$SIGNUP_HTML" | csrf)
SIGNUP_CODE=$(curl -s -o /dev/null -w '%{http_code}' -c "$J" -b "$J" "$P/signup" \
  --data-urlencode "_csrf=$TOKEN" \
  --data-urlencode "tenantId=$T" \
  --data-urlencode "username=${T}owner" \
  --data-urlencode "password=$PASS" \
  --data-urlencode "fullName=Release Owner" \
  --data-urlencode "acceptTerms=true")
record "$SIGNUP_CODE" 200 "POST $P/signup"
TERMS_ROWS=$($DC exec -T postgres psql -U fastbite -d fastbite_production -tAc "select count(*) from public.legal_acceptances where username='${T}owner' and document='terms' and version <> ''" 2>/dev/null | tr -d '\r')
[[ "$TERMS_ROWS" == "1" ]] && TERMS_OK=1 || TERMS_OK=0
record_match "$TERMS_OK" "signup recorded the owner's acceptance of the terms, with its date and version"

LOGIN_HTML=$(curl -s -c "$J" -b "$J" "$P/login")
TOKEN=$(echo "$LOGIN_HTML" | csrf)
LOGIN_RESP=$(curl -s -i -c "$J" -b "$J" "$P/login" \
  --data-urlencode "_csrf=$TOKEN" \
  --data-urlencode "username=${T}owner" \
  --data-urlencode "password=$PASS")
LOGIN_CODE=$(echo "$LOGIN_RESP" | head -n 1 | awk '{print $2}')
LOGIN_LOC=$(echo "$LOGIN_RESP" | loc)
record_redirect "$LOGIN_CODE" "$LOGIN_LOC" "$P" "/owner/console" "POST $P/login"

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
CASHIER_LOGIN_RESP=$(curl -s -i -c "$C" -b "$C" \
  --data-urlencode "_csrf=$CASHIER_LOGIN_CSRF" \
  --data-urlencode "username=${T}cashier" \
  --data-urlencode "password=$PASS" \
  "$S/login?tenantId=$T")
CASHIER_AUTH_CODE=$(echo "$CASHIER_LOGIN_RESP" | head -n 1 | awk '{print $2}')
CASHIER_AUTH_LOC=$(echo "$CASHIER_LOGIN_RESP" | loc)
record_redirect "$CASHIER_AUTH_CODE" "$CASHIER_AUTH_LOC" "$S" "" "POST $S/login?tenantId=$T (cashier login)"

for path in "/$T/counter" "/$T/counter/tickets" "/$T/counter/day-report" "/$T/counter/api/cash" "/$T/dashboard"; do
  check "$C" "$S$path"
done
if [ -n "$TICKET" ]; then
  check "$C" "$S/$T/counter/tickets/$TICKET"
fi

# The counter page loads its menu and tables from the counter catalog, which cashiers may read
for kind in products groups customizations tables; do
  CATALOG_OUT=$(curl -s -w '\n%{http_code}' -b "$C" -c "$C" -H "Referer: $S/$T/counter" "$S/counter/api/catalog/$kind" || true)
  record "$(echo "$CATALOG_OUT" | tail -n 1)" 200 "$S/counter/api/catalog/$kind (cashier, from $T's counter page)"
done
if echo "$(curl -s -b "$C" -c "$C" -H "Referer: $S/$T/counter" "$S/counter/api/catalog/products")" | grep -q '"id"'; then
  record_match 1 "Cashier's counter catalog lists $T's products"
else
  record_match 0 "Cashier's counter catalog lists $T's products"
fi
CASHIER_BO_CODE=$(curl -s -o /dev/null -w '%{http_code}' -b "$C" -c "$C" -H "Referer: $S/$T/counter" "$S/api/backoffice/products" || true)
record "${CASHIER_BO_CODE:-000}" 403 "$S/api/backoffice/products (cashier still kept out of the back office)"

# Order completion & unpaid workflow checks
if [ -n "$PRODUCT" ]; then
  CASHIER_CT=$(curl -s -b "$C" -c "$C" "$S/$T/counter" | meta)
  DASH_CSRF=$(curl -s -b "$C" -c "$C" "$S/$T/dashboard" | meta)

  # 1. Unpaid order: moves through kitchen to DELIVERED (stays unpaid/delivered), then charges to COMPLETE
  UNPAID_ORDER_RESP=$(curl -s -b "$C" -c "$C" \
    -H "Content-Type: application/json" \
    -H "X-CSRF-TOKEN: $CASHIER_CT" \
    -H "Referer: $S/$T/counter" \
    -d "{\"items\":[{\"id\":\"l2\",\"itemId\":\"$PRODUCT\",\"quantity\":1,\"customizations\":[]}],\"paid\":false}" \
    "$S/counter/api/order")
  UNPAID_ORDER_ID=$(echo "$UNPAID_ORDER_RESP" | grep -o '"orderId":"[^"]*"' | sed 's/"orderId":"//; s/"$//')
  if [ -n "$UNPAID_ORDER_ID" ]; then
    record_match 1 "Counter unpaid order placed ($UNPAID_ORDER_ID)"
    # Kitchen moves: CREATED -> ACCEPTED -> PROCESSING -> DONE -> DELIVERED
    for step in 1 2 3 4; do
      curl -s -o /dev/null -X POST -b "$C" -c "$C" -H "X-CSRF-TOKEN: $DASH_CSRF" -H "Referer: $S/$T/dashboard" "$S/api/order/$UNPAID_ORDER_ID/next"
    done

    SERVED_STATUS=$($DC exec -T postgres psql -U fastbite -d fastbite_sandbox -tAc "select status from tenant_$T.orders where id='$UNPAID_ORDER_ID'" 2>/dev/null | tr -d '\r')
    if [ "$SERVED_STATUS" = "4" ]; then
      record_match 1 "Served unpaid order stays DELIVERED (status=4, awaiting payment)"
    else
      record_match 0 "Served unpaid order stays DELIVERED (expected status 4, got ${SERVED_STATUS:-empty})"
    fi

    # Cashier charges the served order via counter settle endpoint -> drops immediately into COMPLETE (status=5)
    SETTLE_PAYLOAD="{\"orderIds\":[\"$UNPAID_ORDER_ID\"],\"paymentMethod\":\"CASH\",\"cashReceived\":50,\"invoice\":null}"
    PAY_CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST -b "$C" -c "$C" \
      -H "Content-Type: application/json" \
      -H "X-CSRF-TOKEN: $CASHIER_CT" \
      -H "Referer: $S/$T/counter" \
      -d "$SETTLE_PAYLOAD" \
      "$S/counter/api/settle")
    record "$PAY_CODE" 200 "POST $S/counter/api/settle (charge served unpaid order)"

    FINAL_STATUS=$($DC exec -T postgres psql -U fastbite -d fastbite_sandbox -tAc "select status from tenant_$T.orders where id='$UNPAID_ORDER_ID'" 2>/dev/null | tr -d '\r')
    if [ "$FINAL_STATUS" = "5" ]; then
      record_match 1 "Paid served order transitions to COMPLETE (status=5)"
    else
      record_match 0 "Paid served order transitions to COMPLETE (expected status 5, got ${FINAL_STATUS:-empty})"
    fi
  else
    record_match 0 "Counter unpaid order placed (order ID was empty)"
  fi

  # 2. Paid order: kitchen moves through to DELIVERED -> directly completes (Rule 1)
  PAID_ORDER_RESP=$(curl -s -b "$C" -c "$C" \
    -H "Content-Type: application/json" \
    -H "X-CSRF-TOKEN: $CASHIER_CT" \
    -H "Referer: $S/$T/counter" \
    -d "{\"items\":[{\"id\":\"l3\",\"itemId\":\"$PRODUCT\",\"quantity\":1,\"customizations\":[]}],\"paid\":true,\"paymentMethod\":\"CASH\",\"cashReceived\":50}" \
    "$S/counter/api/order")
  PAID_ORDER_ID=$(echo "$PAID_ORDER_RESP" | grep -o '"orderId":"[^"]*"' | sed 's/"orderId":"//; s/"$//')
  if [ -n "$PAID_ORDER_ID" ]; then
    record_match 1 "Counter paid order placed ($PAID_ORDER_ID)"
    # Kitchen moves: ACCEPTED -> PROCESSING -> DONE -> COMPLETE (direct upon delivery)
    for step in 1 2 3; do
      curl -s -o /dev/null -X POST -b "$C" -c "$C" -H "X-CSRF-TOKEN: $DASH_CSRF" -H "Referer: $S/$T/dashboard" "$S/api/order/$PAID_ORDER_ID/next"
    done

    PAID_SERVED_STATUS=$($DC exec -T postgres psql -U fastbite -d fastbite_sandbox -tAc "select status from tenant_$T.orders where id='$PAID_ORDER_ID'" 2>/dev/null | tr -d '\r')
    if [ "$PAID_SERVED_STATUS" = "5" ]; then
      record_match 1 "Paid order directly completes when marked served (status=5)"
    else
      record_match 0 "Paid order directly completes when marked served (expected status 5, got ${PAID_SERVED_STATUS:-empty})"
    fi
  else
    record_match 0 "Counter paid order placed (order ID was empty)"
  fi

  # 3. Manager dashboard order list API responds with 200 and includes orders
  DASH_LIST_CODE=$(curl -s -o /dev/null -w '%{http_code}' -b "$C" -c "$C" \
    -H "Content-Type: application/json" \
    -H "X-CSRF-TOKEN: $DASH_CSRF" \
    -H "Referer: $S/$T/dashboard" \
    -X POST "$S/api/order")
  record "$DASH_LIST_CODE" 200 "POST $S/api/order (dashboard order list with table batching)"
fi

# Cashier limits: 403 on table QR codes
QR_CODE=$(curl -s -o /dev/null -w '%{http_code}' -b "$C" -c "$C" "$S/$T/backoffice/tables/qr-codes")
record "$QR_CODE" 403 "GET $S/$T/backoffice/tables/qr-codes (cashier forbidden)"

echo "== [2.5/6] Trusted & Supervised table QR orders, switch, settle reset"
# 1. Get signed QR link as owner from backoffice QR codes page
QR_PAGE=$(curl -s -b "$J" -c "$J" "$S/$T/backoffice/tables/qr-codes")
QR_LINK=$(echo "$QR_PAGE" | grep -o 'data-url="[^"]*"' | head -1 | sed 's/data-url="//; s/"$//' | sed 's/&amp;/\&/g')
TABLE_ID=$(echo "$QR_LINK" | grep -o 'table=[^&]*' | sed 's/table=//')
if [ -n "$QR_LINK" ] && [ -n "$TABLE_ID" ]; then
  record_match 1 "Found signed QR link for table $TABLE_ID from QR codes page"
else
  record_match 0 "Could not find signed QR link in $S/$T/backoffice/tables/qr-codes"
fi

# 2. Guest visits signed QR link to bind session to table
QR_ENTER_CODE=$(curl -s -o /dev/null -w '%{http_code}' -b "$G" -c "$G" "$QR_LINK")
record "$QR_ENTER_CODE" 200 "GET $QR_LINK (guest binds session to table)"

# 3. Table is supervised by default: guest places order -> status must be CREATED (0)
GUEST_MENU_CSRF=$(curl -s -b "$G" -c "$G" "$S/$T/menu" | meta)
ORDER1_OUT="table_order_1.json"
ORDER1_PAYLOAD="{\"items\":[{\"id\":\"tq1\",\"itemId\":\"$PRODUCT\",\"quantity\":1,\"price\":$PRICE}],\"customerName\":\"Table Guest\"}"
ORDER1_CODE=$(curl -s -o "$ORDER1_OUT" -w '%{http_code}' -b "$G" -c "$G" \
  -H "Content-Type: application/json" \
  -H "X-CSRF-TOKEN: $GUEST_MENU_CSRF" \
  -H "Referer: $S/$T/menu" \
  -d "$ORDER1_PAYLOAD" \
  "$S/$T/api/create-order")
record "$ORDER1_CODE" 200 "POST $S/$T/api/create-order (guest table order while supervised)"

ORDER1_ID=$($DC exec -T postgres psql -U fastbite -d fastbite_sandbox -tAc "select id from tenant_$T.orders where order_channel = 2 order by created_at desc limit 1" 2>/dev/null | tr -d '\r')
ORDER1_STATUS=$($DC exec -T postgres psql -U fastbite -d fastbite_sandbox -tAc "select status from tenant_$T.orders where id = '$ORDER1_ID'" 2>/dev/null | tr -d '\r')
if [ "$ORDER1_STATUS" = "0" ]; then
  record_match 1 "Supervised table QR order starts CREATED (status=0, order $ORDER1_ID)"
else
  record_match 0 "Supervised table QR order starts CREATED (expected 0, got ${ORDER1_STATUS:-empty})"
fi

# Order createdAt must be in restaurant-local time (Europe/Madrid), within 2 minutes of Madrid now
MADRID_TZ="Europe/Madrid"
if [ "$(TZ="$MADRID_TZ" date +%Z 2>/dev/null)" = "GMT" ] && [ ! -f /usr/share/zoneinfo/Europe/Madrid ]; then
  MADRID_TZ="CET-1CEST,M3.5.0,M10.5.0/3"
fi
ORDER1_CREATED_AT=$($DC exec -T postgres psql -U fastbite -d fastbite_sandbox -tAc "select created_at from tenant_$T.orders where id = '$ORDER1_ID'" 2>/dev/null | tr -d '\r')
MADRID_NOW_SEC=$(TZ="$MADRID_TZ" date +%s)
ORDER1_SEC=$(TZ="$MADRID_TZ" date -d "${ORDER1_CREATED_AT%.*}" +%s 2>/dev/null || date -d "${ORDER1_CREATED_AT%.*}" +%s 2>/dev/null || echo 0)
CREATED_DIFF=$(( MADRID_NOW_SEC - ORDER1_SEC ))
if [ "$CREATED_DIFF" -lt 0 ]; then CREATED_DIFF=$(( -CREATED_DIFF )); fi
if [ "$ORDER1_SEC" -gt 0 ] && [ "$CREATED_DIFF" -le 120 ]; then
  record_match 1 "Order createdAt ($ORDER1_CREATED_AT) is within 2 minutes of restaurant time (diff ${CREATED_DIFF}s)"
else
  record_match 0 "Order createdAt ($ORDER1_CREATED_AT) is within 2 minutes of restaurant time (diff ${CREATED_DIFF}s, now: $(TZ="$MADRID_TZ" date +'%Y-%m-%d %H:%M:%S'))"
fi

# 4. Cashier switches the table to trusted via counter API
CASHIER_COUNTER_CSRF=$(curl -s -b "$C" -c "$C" "$S/$T/counter" | meta)
SWITCH_CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST -b "$C" -c "$C" \
  -H "X-CSRF-TOKEN: $CASHIER_COUNTER_CSRF" \
  -H "Referer: $S/$T/counter" \
  "$S/counter/api/tables/$TABLE_ID/qr-trusted?trusted=true")
record "$SWITCH_CODE" 200 "POST $S/counter/api/tables/$TABLE_ID/qr-trusted?trusted=true (cashier sets table trusted)"

TABLE_TRUSTED_DB=$($DC exec -T postgres psql -U fastbite -d fastbite_sandbox -tAc "select qr_trusted from tenant_$T.dining_tables where id = '$TABLE_ID'" 2>/dev/null | tr -d '\r')
if [ "$TABLE_TRUSTED_DB" = "t" ]; then
  record_match 1 "Table $TABLE_ID has qr_trusted=true in database"
else
  record_match 0 "Table $TABLE_ID has qr_trusted=true in database (got ${TABLE_TRUSTED_DB:-empty})"
fi

# 5. Guest places another QR order: while table is trusted -> status must be ACCEPTED (1)
ORDER2_OUT="table_order_2.json"
ORDER2_PAYLOAD="{\"items\":[{\"id\":\"tq2\",\"itemId\":\"$PRODUCT\",\"quantity\":1,\"price\":$PRICE}],\"customerName\":\"Table Guest\"}"
ORDER2_CODE=$(curl -s -o "$ORDER2_OUT" -w '%{http_code}' -b "$G" -c "$G" \
  -H "Content-Type: application/json" \
  -H "X-CSRF-TOKEN: $GUEST_MENU_CSRF" \
  -H "Referer: $S/$T/menu" \
  -d "$ORDER2_PAYLOAD" \
  "$S/$T/api/create-order")
record "$ORDER2_CODE" 200 "POST $S/$T/api/create-order (guest table order while trusted)"

ORDER2_ID=$($DC exec -T postgres psql -U fastbite -d fastbite_sandbox -tAc "select id from tenant_$T.orders where order_channel = 2 order by created_at desc limit 1" 2>/dev/null | tr -d '\r')
ORDER2_STATUS=$($DC exec -T postgres psql -U fastbite -d fastbite_sandbox -tAc "select status from tenant_$T.orders where id = '$ORDER2_ID'" 2>/dev/null | tr -d '\r')
if [ "$ORDER2_STATUS" = "1" ]; then
  record_match 1 "Trusted table QR order starts ACCEPTED (status=1, order $ORDER2_ID)"
else
  record_match 0 "Trusted table QR order starts ACCEPTED (expected 1, got ${ORDER2_STATUS:-empty})"
fi

# 6. Cashier charges the table at the counter: settles both orders
SETTLE_PAYLOAD="{\"orderIds\":[\"$ORDER1_ID\",\"$ORDER2_ID\"],\"paymentMethod\":\"CASH\",\"cashReceived\":50,\"invoice\":null}"
SETTLE_CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST -b "$C" -c "$C" \
  -H "Content-Type: application/json" \
  -H "X-CSRF-TOKEN: $CASHIER_COUNTER_CSRF" \
  -H "Referer: $S/$T/counter" \
  -d "$SETTLE_PAYLOAD" \
  "$S/counter/api/settle")
record "$SETTLE_CODE" 200 "POST $S/counter/api/settle (settle table orders)"

# 7. Settle resets table override: qr_trusted must be null (back to restaurant default)
TABLE_RESET_DB=$($DC exec -T postgres psql -U fastbite -d fastbite_sandbox -tAc "select qr_trusted from tenant_$T.dining_tables where id = '$TABLE_ID'" 2>/dev/null | tr -d '\r')
if [ -z "$TABLE_RESET_DB" ]; then
  record_match 1 "Table override reset on settle (qr_trusted is null, back to default)"
else
  record_match 0 "Table override reset on settle (expected null/empty, got $TABLE_RESET_DB)"
fi

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
    "/$T/dashboard" "/$T/backoffice" \
    "/owner/console?location=$T" "/owner/console?view=locations" "/owner/console?view=team&location=$T" \
    "/owner/console?view=settings" "/owner/console?view=location-settings&location=$T"; do
  check "$J" "$S$path"
done
if [ -n "$TICKET" ]; then
  check "$J" "$S/$T/counter/tickets/$TICKET"
fi
# The counter page loads these without a restaurant in the URL; the browser's Referer names it
for fragment in categories products tables; do
  FRAGMENT_CODE=$(curl -s -o /dev/null -w '%{http_code}' -b "$J" -c "$J" -H "Referer: $S/$T/counter" "$S/counter/fragments/$fragment" || true)
  record "${FRAGMENT_CODE:-000}" 200 "$S/counter/fragments/$fragment (from $T's counter page)"
done
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

if [[ "$LEGAL_LOC" =~ ^/ ]]; then
  LEGAL_LOC="$S$LEGAL_LOC"
fi
LEGAL_REDIRECT_PAGE=$(curl -s -b "$J" -c "$J" "$LEGAL_LOC")
if echo "$LEGAL_REDIRECT_PAGE" | grep -q "INVALID_NIF_999"; then
  record_match 1 "Invalid legal form draft preserved on redirect (INVALID_NIF_999)"
else
  record_match 0 "Invalid legal form draft preserved on redirect (INVALID_NIF_999 not found)"
fi

# Refused foral postal code (Bilbao 48001): assert redirect and TicketBAI error message
FORAL_CSRF=$(echo "$LEGAL_REDIRECT_PAGE" | meta)
FORAL_RESP=$(curl -s -D - -o /dev/null -b "$J" -c "$J" \
  -H "Referer: $S/owner/console?view=location-settings&section=legal&location=$T" \
  --data-urlencode "_csrf=$FORAL_CSRF" \
  --data-urlencode "postalCode=48001" \
  "$S/owner/workspace/$T/legal")
FORAL_CODE=$(echo "$FORAL_RESP" | head -n 1 | awk '{print $2}')
FORAL_LOC=$(echo "$FORAL_RESP" | loc)
record "${FORAL_CODE:-000}" 302 "POST $S/owner/workspace/$T/legal (refused foral postal code redirect)"

if [[ "$FORAL_LOC" =~ ^/ ]]; then
  FORAL_LOC="$S$FORAL_LOC"
fi
FORAL_PAGE=$(curl -s -b "$J" -c "$J" "$FORAL_LOC")
if echo "$FORAL_PAGE" | grep -q "TicketBAI"; then
  record_match 1 "Refused foral postal code shows TicketBAI explanation"
else
  record_match 0 "Refused foral postal code shows TicketBAI explanation"
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

# Save restaurant settings with tableQrTrusted in sandbox
SETTINGS_SAVE_CODE=$(curl -s -o /dev/null -w '%{http_code}' -X PUT -b "$J" -c "$J" \
  -H 'Content-Type: application/json' \
  -H "X-CSRF-TOKEN: $BT" \
  -H "Referer: $S/$T/backoffice" \
  -d '{"dineIn":true,"takeaway":true,"kdsYellowMinutes":5,"kdsRedMinutes":10,"tableQrTrusted":true}' \
  "$S/$T/api/backoffice/settings")
record "$SETTINGS_SAVE_CODE" 200 "PUT $S/$T/api/backoffice/settings (sandbox owner saves settings)"

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

# Verify @JsonCreator settings in the native image:
PROD_SETTINGS_CODE=$(curl -s -o /dev/null -w '%{http_code}' -b "$J" -c "$J" "$P/$T/api/backoffice/settings")
record "$PROD_SETTINGS_CODE" 200 "GET $P/$T/api/backoffice/settings (production native image reads settings)"

PROD_TRUSTED_DB=$($DC exec -T postgres psql -U fastbite -d fastbite_production -tAc "select table_qr_trusted from tenant_$T.restaurant_settings" 2>/dev/null | tr -d '\r')
if [ "$PROD_TRUSTED_DB" = "f" ] || [ "$PROD_TRUSTED_DB" = "t" ]; then
  record_match 1 "Production bundle restored RestaurantSettings via @JsonCreator into native image (table_qr_trusted=$PROD_TRUSTED_DB)"
else
  record_match 0 "Production bundle restored RestaurantSettings into native image (expected f or t, got ${PROD_TRUSTED_DB:-empty})"
fi

# Production fiscal issuer registration: saving a valid NIF records in production only
PROD_LEGAL_PAGE=$(curl -s -b "$J" -c "$J" "$P/owner/console?view=location-settings&section=legal&location=$T")
PROD_LEGAL_CSRF=$(echo "$PROD_LEGAL_PAGE" | meta)
PROD_LEGAL_RESP=$(curl -s -D - -o /dev/null -b "$J" -c "$J" \
  -H "Referer: $P/owner/console?view=location-settings&section=legal&location=$T" \
  --data-urlencode "_csrf=$PROD_LEGAL_CSRF" \
  --data-urlencode "legalName=Test Release Cafe S.L." \
  --data-urlencode "tradeName=Release Cafe" \
  --data-urlencode "taxId=B12345674" \
  --data-urlencode "addressLine=Calle Mayor 1" \
  --data-urlencode "postalCode=28013" \
  --data-urlencode "city=Madrid" \
  --data-urlencode "province=Madrid" \
  --data-urlencode "seriesPrefix=RC" \
  --data-urlencode "defaultVatRate=10" \
  "$P/owner/workspace/$T/legal")
PROD_LEGAL_CODE=$(echo "$PROD_LEGAL_RESP" | head -n 1 | awk '{print $2}')
record "${PROD_LEGAL_CODE:-000}" 302 "POST $P/owner/workspace/$T/legal (production valid fiscal profile save)"

PROD_ISSUER_ROWS=$($DC exec -T postgres psql -U fastbite -d fastbite_production -tAc "select count(*) from public.fiscal_issuers where tenant_id='$T' and tax_id='B12345674'" 2>/dev/null | tr -d '\r')
if [ "$PROD_ISSUER_ROWS" = "1" ]; then
  record_match 1 "fastbite_production.public.fiscal_issuers recorded B12345674 for $T"
else
  record_match 0 "fastbite_production.public.fiscal_issuers recorded B12345674 for $T (got: $PROD_ISSUER_ROWS)"
fi

SAND_ISSUER_ROWS=$($DC exec -T postgres psql -U fastbite -d fastbite_sandbox -tAc "select count(*) from public.fiscal_issuers where tax_id='B12345674'" 2>/dev/null | tr -d '\r')
if [ "$SAND_ISSUER_ROWS" = "0" ]; then
  record_match 1 "fastbite_sandbox.public.fiscal_issuers does not contain B12345674"
else
  record_match 0 "fastbite_sandbox.public.fiscal_issuers does not contain B12345674 (got: $SAND_ISSUER_ROWS)"
fi

# Production location reset preserves fiscal tickets and VERI*FACTU records across sales
PROD_CT=$(curl -s -b "$J" -c "$J" "$P/$T/counter" | meta)
PROD_SALE1_RESP=$(curl -s -w '\n%{http_code}' -b "$J" -c "$J" \
  -H "Content-Type: application/json" \
  -H "X-CSRF-TOKEN: $PROD_CT" \
  -H "Referer: $P/$T/counter" \
  -d "{\"items\":[{\"id\":\"p1\",\"itemId\":\"$PRODUCT\",\"quantity\":1,\"customizations\":[]}],\"paid\":true,\"paymentMethod\":\"CASH\",\"cashReceived\":50}" \
  "$P/counter/api/order")
PROD_SALE1_CODE=$(echo "$PROD_SALE1_RESP" | tail -n 1)
PROD_SALE1_JSON=$(echo "$PROD_SALE1_RESP" | head -n -1)
record "$PROD_SALE1_CODE" 200 "POST $P/counter/api/order (production cash sale 1)"

PROD_SALE1_TICKET=$(echo "$PROD_SALE1_JSON" | grep -o '"ticketId":"[^"]*"' | head -1 | sed 's/"ticketId":"//; s/"$//')
PROD_SALE1_NUM=$($DC exec -T postgres psql -U fastbite -d fastbite_production -tAc "select ticket_number from tenant_$T.fiscal_tickets where id='$PROD_SALE1_TICKET'" 2>/dev/null | tr -d '\r')
PROD_TICKETS_BEFORE=$($DC exec -T postgres psql -U fastbite -d fastbite_production -tAc "select count(*) from tenant_$T.fiscal_tickets" 2>/dev/null | tr -d '\r')

PROD_MAINT_CSRF=$(curl -s -b "$J" -c "$J" "$P/owner/console?view=location-settings&section=advanced&location=$T" | meta)
CLEAN_CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST -b "$J" -c "$J" \
  -H "X-CSRF-TOKEN: $PROD_MAINT_CSRF" \
  -H "Referer: $P/owner/console?view=location-settings&section=advanced&location=$T" \
  "$P/$T/api/backoffice/maintenance/clean")
record "$CLEAN_CODE" 200 "POST $P/$T/api/backoffice/maintenance/clean (production location reset)"

PROD_TICKETS_AFTER=$($DC exec -T postgres psql -U fastbite -d fastbite_production -tAc "select count(*) from tenant_$T.fiscal_tickets" 2>/dev/null | tr -d '\r')
PROD_VF_COUNT=$($DC exec -T postgres psql -U fastbite -d fastbite_production -tAc "select count(*) from tenant_$T.verifactu_records where ticket_id='$PROD_SALE1_TICKET'" 2>/dev/null | tr -d '\r')
if [ "$PROD_TICKETS_AFTER" = "$PROD_TICKETS_BEFORE" ] && [ "$PROD_VF_COUNT" -ge 1 ]; then
  record_match 1 "Reset preserved fiscal tickets ($PROD_TICKETS_AFTER) and VERI*FACTU record for $PROD_SALE1_TICKET"
else
  record_match 0 "Reset preserved fiscal tickets ($PROD_TICKETS_BEFORE -> $PROD_TICKETS_AFTER, vf=$PROD_VF_COUNT)"
fi

# Re-import bundle so counter catalog has products for second sale
PROD_BUNDLE_CSRF=$(curl -s -b "$J" -c "$J" "$P/owner/console?view=location-settings&section=backup&location=$T" | meta)
curl -s -o /dev/null -b "$J" -c "$J" \
  -H "X-CSRF-TOKEN: $PROD_BUNDLE_CSRF" \
  -F "file=@$BUNDLE;type=application/zip" \
  "$P/$T/api/backoffice/maintenance/import-production-bundle"

PROD_CT2=$(curl -s -b "$J" -c "$J" "$P/$T/counter" | meta)
PROD_SALE2_RESP=$(curl -s -w '\n%{http_code}' -b "$J" -c "$J" \
  -H "Content-Type: application/json" \
  -H "X-CSRF-TOKEN: $PROD_CT2" \
  -H "Referer: $P/$T/counter" \
  -d "{\"items\":[{\"id\":\"p2\",\"itemId\":\"$PRODUCT\",\"quantity\":1,\"customizations\":[]}],\"paid\":true,\"paymentMethod\":\"CASH\",\"cashReceived\":50}" \
  "$P/counter/api/order")
PROD_SALE2_CODE=$(echo "$PROD_SALE2_RESP" | tail -n 1)
PROD_SALE2_JSON=$(echo "$PROD_SALE2_RESP" | head -n -1)
record "$PROD_SALE2_CODE" 200 "POST $P/counter/api/order (production cash sale 2 after reset)"

PROD_SALE2_TICKET=$(echo "$PROD_SALE2_JSON" | grep -o '"ticketId":"[^"]*"' | head -1 | sed 's/"ticketId":"//; s/"$//')
PROD_SALE2_NUM=$($DC exec -T postgres psql -U fastbite -d fastbite_production -tAc "select ticket_number from tenant_$T.fiscal_tickets where id='$PROD_SALE2_TICKET'" 2>/dev/null | tr -d '\r')

EXPECTED_NUM=$((PROD_SALE1_NUM + 1))
if [ "$PROD_SALE2_NUM" = "$EXPECTED_NUM" ]; then
  record_match 1 "Second sale after reset got sequential ticket number ($PROD_SALE2_NUM = $EXPECTED_NUM in series RC)"
else
  record_match 0 "Second sale after reset got sequential ticket number (expected $EXPECTED_NUM, got ${PROD_SALE2_NUM:-none})"
fi

echo "== [5.5/6] Free plan pause, one-click unsubscribe & restaurant privacy PDFs"
# 1. Free plan: signup with plan=FREE (and marketing=true for the unsubscribe check below);
#    a guest order on its menu gets 402 (no Stripe Connect), and its location settings show takeaway locked.
TF="free$RANDOM"
JF="free_owner.cookies"
GF="free_guest.cookies"
FREE_SIGNUP_HTML=$(curl -s -c "$JF" -b "$JF" "$P/signup")
FREE_CSRF=$(echo "$FREE_SIGNUP_HTML" | csrf)
FREE_SIGNUP_CODE=$(curl -s -o /dev/null -w '%{http_code}' -c "$JF" -b "$JF" "$P/signup"   --data-urlencode "_csrf=$FREE_CSRF"   --data-urlencode "tenantId=$TF"   --data-urlencode "username=${TF}owner"   --data-urlencode "password=$PASS"   --data-urlencode "fullName=Free Plan Owner"   --data-urlencode "plan=FREE"   --data-urlencode "acceptTerms=true"   --data-urlencode "marketing=true")
record "$FREE_SIGNUP_CODE" 200 "POST $P/signup (plan=FREE, marketing=true)"

FREE_MENU_CSRF=$(curl -s -b "$GF" -c "$GF" "$P/$TF/menu" | meta)
FREE_ORDER_CODE=$(curl -s -o /dev/null -w '%{http_code}' -b "$GF" -c "$GF"   -H "Content-Type: application/json"   -H "X-CSRF-TOKEN: $FREE_MENU_CSRF"   -H "Referer: $P/$TF/menu"   -d '{"items":[{"id":"f1","itemId":"1","quantity":1,"price":5.00}],"customerName":"Free Guest"}'   "$P/$TF/api/create-order")
record "$FREE_ORDER_CODE" 402 "POST $P/$TF/api/create-order (Free plan without Stripe Connect gets 402)"

FREE_LOGIN_HTML=$(curl -s -c "$JF" -b "$JF" "$P/login")
FREE_LOGIN_CSRF=$(echo "$FREE_LOGIN_HTML" | csrf)
FREE_LOGIN_RESP=$(curl -s -i -c "$JF" -b "$JF" "$P/login"   --data-urlencode "_csrf=$FREE_LOGIN_CSRF"   --data-urlencode "username=${TF}owner"   --data-urlencode "password=$PASS")
FREE_LOGIN_CODE=$(echo "$FREE_LOGIN_RESP" | head -n 1 | awk '{print $2}')
FREE_LOGIN_LOC=$(echo "$FREE_LOGIN_RESP" | loc)
record_redirect "$FREE_LOGIN_CODE" "$FREE_LOGIN_LOC" "$P" "/owner/console" "POST $P/login (Free plan owner)"

FREE_SETTINGS_OUT="free_settings.html"
FREE_SETTINGS_CODE=$(curl -s -o "$FREE_SETTINGS_OUT" -w '%{http_code}' -b "$JF" -c "$JF"   "$P/owner/console?view=location-settings&section=general&location=$TF")
record "$FREE_SETTINGS_CODE" 200 "GET $P/owner/console?view=location-settings&section=general&location=$TF"
if grep -q "Locked on the Free Plan" "$FREE_SETTINGS_OUT" 2>/dev/null; then
  record_match 1 "Free plan location settings show takeaway locked"
else
  record_match 0 "Free plan location settings show takeaway locked"
fi

# 2. Unsubscribe: issue token for ${TF}owner, GET (200, nothing withdrawn), POST (200, withdrawn_at set), tampered token (400)
UNSUB_RESP=$(curl -s -w '
%{http_code}' -H "X-FastBite-Jobs-Secret: $JOBS_SECRET"   "$P/internal/jobs/unsubscribe-token?username=${TF}owner" || true)
UNSUB_ISSUE_CODE=$(echo "$UNSUB_RESP" | tail -n 1)
UNSUB_TOKEN=$(echo "$UNSUB_RESP" | head -n -1 | tr -d '
')
record "${UNSUB_ISSUE_CODE:-000}" 200 "GET $P/internal/jobs/unsubscribe-token (authenticated with secret)"

UNSUB_GET_CODE=$(curl -s -o /dev/null -w '%{http_code}' "$P/legal/unsubscribe?token=$UNSUB_TOKEN" || true)
record "${UNSUB_GET_CODE:-000}" 200 "GET $P/legal/unsubscribe?token=... (confirmation page)"

MKT_ACTIVE_ROWS=$($DC exec -T postgres psql -U fastbite -d fastbite_production -tAc "select count(*) from public.legal_acceptances where username='${TF}owner' and document='marketing' and withdrawn_at is null" 2>/dev/null | tr -d '')
[[ "$MKT_ACTIVE_ROWS" == "1" ]] && MKT_STILL_ACTIVE=1 || MKT_STILL_ACTIVE=0
record_match "$MKT_STILL_ACTIVE" "GET unsubscribe link does not withdraw consent"

UNSUB_POST_CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST   --data-urlencode "token=$UNSUB_TOKEN"   "$P/legal/unsubscribe" || true)
record "${UNSUB_POST_CODE:-000}" 200 "POST $P/legal/unsubscribe (withdraw consent)"

MKT_WITHDRAWN_ROWS=$($DC exec -T postgres psql -U fastbite -d fastbite_production -tAc "select count(*) from public.legal_acceptances where username='${TF}owner' and document='marketing' and withdrawn_at is not null" 2>/dev/null | tr -d '')
[[ "$MKT_WITHDRAWN_ROWS" == "1" ]] && MKT_WITHDRAWN=1 || MKT_WITHDRAWN=0
record_match "$MKT_WITHDRAWN" "POST unsubscribe link sets withdrawn_at in fastbite_production"

BAD_UNSUB_CODE=$(curl -s -o /dev/null -w '%{http_code}' "$P/legal/unsubscribe?token=${UNSUB_TOKEN}tampered" || true)
record "${BAD_UNSUB_CODE:-000}" 400 "GET $P/legal/unsubscribe (tampered token gets 400)"

# 3. Privacy PDF: upload valid PDF as release owner ($J), check /$T/privacy and /$T/privacy/pdf, refuse non-PDF, restore generated
PDF_FILE="valid_privacy.pdf"
PDF_DOWN="downloaded_privacy.pdf"
BAD_PDF_FILE="not_a_pdf.txt"
printf '%%PDF-1.4
1 0 obj << /Type /Catalog >> endobj
trailer << /Root 1 0 R >>
%%%%EOF
' > "$PDF_FILE"
printf 'plain text not a pdf
' > "$BAD_PDF_FILE"

PDF_CSRF=$(curl -s -b "$J" -c "$J" "$P/owner/console?view=location-settings&section=legal&location=$T" | meta)
PDF_UPLOAD_CODE=$(curl -s -o /dev/null -w '%{http_code}' -b "$J" -c "$J"   -H "X-CSRF-TOKEN: $PDF_CSRF"   -F "file=@$PDF_FILE;type=application/pdf"   "$P/owner/workspace/$T/privacy-notice")
record "${PDF_UPLOAD_CODE:-000}" 302 "POST $P/owner/workspace/$T/privacy-notice (valid PDF upload)"

PRIVACY_PAGE_OUT="privacy_pdf_page.html"
PRIVACY_PAGE_CODE=$(curl -s -o "$PRIVACY_PAGE_OUT" -w '%{http_code}' "$P/$T/privacy" || true)
record "${PRIVACY_PAGE_CODE:-000}" 200 "GET $P/$T/privacy (with active PDF)"
if grep -q "/$T/privacy/pdf" "$PRIVACY_PAGE_OUT" 2>/dev/null && grep -q "privacy-pdf-frame" "$PRIVACY_PAGE_OUT" 2>/dev/null; then
  record_match 1 "GET $P/$T/privacy shows the restaurant PDF page"
else
  record_match 0 "GET $P/$T/privacy shows the restaurant PDF page"
fi

PRIVACY_PDF_HEADERS="privacy_pdf_headers.txt"
PRIVACY_PDF_CODE=$(curl -s -D "$PRIVACY_PDF_HEADERS" -o "$PDF_DOWN" -w '%{http_code}' "$P/$T/privacy/pdf" || true)
record "${PRIVACY_PDF_CODE:-000}" 200 "GET $P/$T/privacy/pdf (serves active PDF)"
if grep -qi '^content-type: application/pdf' "$PRIVACY_PDF_HEADERS" 2>/dev/null && cmp -s "$PDF_FILE" "$PDF_DOWN"; then
  record_match 1 "GET $P/$T/privacy/pdf returns application/pdf with the uploaded bytes"
else
  record_match 0 "GET $P/$T/privacy/pdf returns application/pdf with the uploaded bytes"
fi

BAD_PDF_CSRF=$(curl -s -b "$J" -c "$J" "$P/owner/console?view=location-settings&section=legal&location=$T" | meta)
BAD_PDF_RESP=$(curl -s -D - -o /dev/null -b "$J" -c "$J"   -H "X-CSRF-TOKEN: $BAD_PDF_CSRF"   -F "file=@$BAD_PDF_FILE;type=text/plain"   "$P/owner/workspace/$T/privacy-notice")
BAD_PDF_CODE=$(echo "$BAD_PDF_RESP" | head -n 1 | awk '{print $2}')
BAD_PDF_LOC=$(echo "$BAD_PDF_RESP" | loc)
record "${BAD_PDF_CODE:-000}" 302 "POST $P/owner/workspace/$T/privacy-notice (non-PDF upload redirect)"
if [[ "$BAD_PDF_LOC" =~ ^/ ]]; then
  BAD_PDF_LOC="$P$BAD_PDF_LOC"
fi
BAD_PDF_PAGE=$(curl -s -b "$J" -c "$J" "$BAD_PDF_LOC")
PDF_COUNT_DB=$($DC exec -T postgres psql -U fastbite -d fastbite_production -tAc "select count(*) from tenant_$T.guest_privacy_notices" 2>/dev/null | tr -d '')
if echo "$BAD_PDF_PAGE" | grep -q "%PDF-" && [ "$PDF_COUNT_DB" = "1" ]; then
  record_match 1 "Non-PDF upload is refused and keeps active PDF unchanged"
else
  record_match 0 "Non-PDF upload is refused and keeps active PDF unchanged (count=$PDF_COUNT_DB)"
fi

GEN_CSRF=$(echo "$BAD_PDF_PAGE" | meta)
USE_GEN_CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST -b "$J" -c "$J"   -H "X-CSRF-TOKEN: $GEN_CSRF"   "$P/owner/workspace/$T/privacy-notice/generated")
record "${USE_GEN_CODE:-000}" 302 "POST $P/owner/workspace/$T/privacy-notice/generated (use generated notice)"

GEN_PAGE=$(curl -s "$P/$T/privacy" || true)
GEN_PDF_CODE=$(curl -s -o /dev/null -w '%{http_code}' "$P/$T/privacy/pdf" || true)
if ! echo "$GEN_PAGE" | grep -q "privacy-pdf-frame" && [ "$GEN_PDF_CODE" = "404" ]; then
  record_match 1 "GET $P/$T/privacy restores the generated privacy notice"
else
  record_match 0 "GET $P/$T/privacy restores the generated privacy notice (pdf_code=$GEN_PDF_CODE)"
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

# A Spanish browser gets Spanish: the native image must contain every language of the messages
SPANISH_MENU=$(curl -s -H 'Accept-Language: es' "$P/$T/menu" || true)
if [[ "$SPANISH_MENU" == *"Pedir en L"* ]]; then
  record_match 1 "$P/$T/menu answers in Spanish to a Spanish browser"
else
  record_match 0 "$P/$T/menu answers in Spanish to a Spanish browser"
fi

SPANISH_HOME=$(curl -s -H 'Accept-Language: es' "$P/" || true)
if [[ "$SPANISH_HOME" == *"preparado para VERI*FACTU"* ]]; then
  record_match 1 "$P/ answers in Spanish to a Spanish browser"
else
  record_match 0 "$P/ answers in Spanish to a Spanish browser"
fi

# A restaurant that doesn't exist gets the branded 404, never Spring's Whitelabel page or a 500
for url in "$P/nosuchplace$T/menu" "$S/nosuchplace$T/counter" "$P/$T/menu/no-such-page"; do
  # As a browser asks: API callers (no text/html) get the JSON error instead
  NOT_FOUND_OUT=$(curl -s -H 'Accept: text/html' -w '\n%{http_code}' "$url" || true)
  NOT_FOUND_CODE=$(echo "$NOT_FOUND_OUT" | tail -n 1)
  record "$NOT_FOUND_CODE" 404 "GET $url"
  if [[ "$NOT_FOUND_OUT" == *confirmation-card* && "$NOT_FOUND_OUT" != *[Ww]hitelabel* ]]; then
    record_match 1 "$url shows the FastBite error page"
  else
    record_match 0 "$url shows the FastBite error page"
  fi
done

# The public schema holds only platform tables (V23); restaurant data lives in tenant_* schemas.
PLATFORM_TABLES="fiscal_issuers flyway_schema_history group_quote_requests legal_acceptances location_appearance owner_appearance owner_onboarding owner_setup_tokens sandbox_provisioning sandbox_sign_in spring_session spring_session_attributes tenant_billing tenant_lifecycle tenant_locations user_roles users"
for db in fastbite_production fastbite_sandbox; do
  PUBLIC_TABLES=$($DC exec -T postgres psql -U fastbite -d "$db" -tAc "select string_agg(table_name, ' ' order by table_name) from information_schema.tables where table_schema = 'public'" 2>/dev/null | tr -d '\r')
  if [ "$PUBLIC_TABLES" = "$PLATFORM_TABLES" ]; then
    record_match 1 "$db: public holds only the platform tables"
  else
    record_match 0 "$db: public holds only the platform tables (got: ${PUBLIC_TABLES:-none})"
  fi
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
