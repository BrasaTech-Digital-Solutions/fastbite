#!/usr/bin/env bash
# Builds a throwaway screenshot fixture on the local stack through FastBite's own HTTP flows.
# Never touches `cake` or real data. Writes cookie jars and metadata to D:/git/fastbite-shots/<run>/fixture.env.
#
# Usage: bash scripts/screens/fixture.sh [BASE_URL] [RUN_DIR]
#   BASE_URL defaults to http://sandbox.fastbite.localhost:8088
#   RUN_DIR  defaults to D:/git/fastbite-shots/run-<YYYYMMDD-HHMMSS>
set -euo pipefail

BASE="${1:-http://sandbox.fastbite.localhost:8088}"
PROD="${FASTBITE_PROD_URL:-http://fastbite.localhost:8088}"
MAILPIT="${FASTBITE_MAILPIT_URL:-http://localhost:8027}"
if [ "$BASE" != 'http://sandbox.fastbite.localhost:8088' ] || \
   [ "$PROD" != 'http://fastbite.localhost:8088' ] || \
   [ "$MAILPIT" != 'http://localhost:8027' ]; then
  echo 'Fixture only runs against the local FastBite sandbox, production companion, and Mailpit.' >&2
  exit 2
fi
umask 077
STAMP=$(date +%Y%m%d%H%M%S)
RAW_RUN_DIR="${2:-D:/git/fastbite-shots/run-$STAMP}"
T="shots${STAMP}"
PASS="shots-test-password-123"

# Convert Windows drive paths (e.g. D:/...) when running under WSL bash
to_shell_path() {
  local p="$1"
  if grep -qi microsoft /proc/version 2>/dev/null && [[ "$p" =~ ^([A-Za-z]):/(.*)$ ]]; then
    local drv="${BASH_REMATCH[1],,}"
    echo "/mnt/$drv/${BASH_REMATCH[2]}"
  else
    echo "$p"
  fi
}

to_win_path() {
  local p="$1"
  if [[ "$p" =~ ^/mnt/([a-z])/(.*)$ ]]; then
    echo "${BASH_REMATCH[1]^^}:/${BASH_REMATCH[2]}"
  elif [[ "$p" =~ ^/([a-z])/(.*)$ ]]; then
    echo "${BASH_REMATCH[1]^^}:/${BASH_REMATCH[2]}"
  else
    echo "$p"
  fi
}

RUN_DIR=$(to_shell_path "$RAW_RUN_DIR")
if [ -e "$RUN_DIR/fixture.env" ]; then
  echo 'Fixture output already exists; choose a new run directory.' >&2
  exit 2
fi

PY=$(command -v python3 || command -v python || true)
if [ -z "$PY" ]; then
  echo "Python 3 is required to read Mailpit verification links and parse JSON." >&2
  exit 2
fi

mkdir -p "$RUN_DIR"
RUN_DIR_POSIX=$(cd "$RUN_DIR" && pwd)
RUN_DIR_ENV=$(to_win_path "$RUN_DIR_POSIX")

csrf() { grep -o 'name="_csrf"[^>]*value="[^"]*"' | head -1 | sed 's/.*value="//; s/"$//'; }
meta() { grep -o 'name="_csrf" content="[^"]*"\|name="csrf-token" content="[^"]*"' | head -1 | sed 's/.*content="//; s/"$//'; }
loc()  { grep -i '^location:' | tr -d '\r' | sed 's/^[Ll]ocation: //'; }

verification_token() {
  "$PY" - "$1" "$MAILPIT" <<'PYCODE'
import json, re, sys, time, urllib.parse, urllib.request
email, mailpit = sys.argv[1], sys.argv[2].rstrip('/')
for _ in range(20):
    try:
        q = urllib.parse.quote('to:' + email)
        search = json.load(urllib.request.urlopen(f'{mailpit}/api/v1/search?query={q}', timeout=3))
        for item in search.get('messages', []):
            detail = json.load(urllib.request.urlopen(f'{mailpit}/api/v1/message/{item["ID"]}', timeout=3))
            match = re.search(r'/verify-email\?token=([A-Za-z0-9_-]{43})', detail.get('Text', '') or '')
            if match:
                print(match.group(1)); sys.exit(0)
    except Exception:
        pass
    time.sleep(0.5)
sys.exit(1)
PYCODE
}

# Pre-flight check
PROD_CODE=$(curl -s -o /dev/null -w '%{http_code}' "$PROD/legal/terms" || true)
BASE_CODE=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/legal/terms" || true)
if [ "$PROD_CODE" != "200" ] || [ "$BASE_CODE" != "200" ]; then
  echo "Local stack not ready (prod=$PROD_CODE at $PROD, target=$BASE_CODE at $BASE)." >&2
  exit 2
fi

cd "$RUN_DIR_POSIX"
OWNER_JAR="owner.cookies"
MANAGER_JAR="manager.cookies"
COOK_JAR="cook.cookies"
GUEST_JAR="guest.cookies"
ONLINE_GUEST_JAR="online-guest.cookies"
GUEST_CANCELLED_JAR="guest-cancelled.cookies"

echo "== [1/6] Signing up throwaway owner ${T}owner and verifying via Mailpit"
SIGNUP_HTML=$(curl -sS -c "$OWNER_JAR" -b "$OWNER_JAR" "$PROD/signup")
SIGNUP_CSRF=$(echo "$SIGNUP_HTML" | csrf)
SIGNUP_CODE=$(curl -sS -o /dev/null -w '%{http_code}' -c "$OWNER_JAR" -b "$OWNER_JAR" "$PROD/signup" \
  --data-urlencode "_csrf=$SIGNUP_CSRF" \
  --data-urlencode "tenantId=$T" \
  --data-urlencode "username=${T}owner" \
  --data-urlencode "password=$PASS" \
  --data-urlencode "fullName=Ana García" \
  --data-urlencode "email=${T}@example.test" \
  --data-urlencode "acceptTerms=true")
case "$SIGNUP_CODE" in 302|303) ;; *) echo "FAIL: signup expected HTTP 302/303, got HTTP $SIGNUP_CODE" >&2; exit 1;; esac

fail() { echo "FAIL: $1" >&2; echo "  expected: $2" >&2; echo "  got:      $3" >&2; exit 1; }
require_nonempty() { [ -n "$2" ] || fail "$1 is empty" "a non-empty value" "(empty)"; }
expect_redirect() { case "$2" in 302|303) ;; *) fail "$1" "HTTP 302/303" "HTTP $2";; esac; }

VERIFY_TOKEN=$(verification_token "${T}@example.test") || fail "no verification email in Mailpit" "a /verify-email link for ${T}@example.test" "nothing after 10s"
VERIFY_CODE=$(curl -sS -o /dev/null -w '%{http_code}' "$PROD/verify-email?token=$VERIFY_TOKEN")
case "$VERIFY_CODE" in 200|302|303) ;; *) fail "email verification" "HTTP 200/302/303" "HTTP $VERIFY_CODE";; esac

echo "== [2/6] Signing in owner and entering sandbox"
LOGIN_CSRF=$(curl -sS -c "$OWNER_JAR" -b "$OWNER_JAR" "$PROD/login" | csrf)
LOGIN_TO=$(curl -sS -D - -o /dev/null -c "$OWNER_JAR" -b "$OWNER_JAR" "$PROD/login" \
  --data-urlencode "_csrf=$LOGIN_CSRF" \
  --data-urlencode "username=${T}owner" \
  --data-urlencode "password=$PASS" | loc)
case "$LOGIN_TO" in *error*|*/login*|'') fail "owner sign-in" "a redirect away from /login" "${LOGIN_TO:-(no redirect)}";; esac

if [ "$BASE" != "$PROD" ]; then
  LINK=$(curl -sS -D - -o /dev/null -c "$OWNER_JAR" -b "$OWNER_JAR" "$PROD/owner/sandbox?location=$T" | loc)
  case "$LINK" in "$BASE"/*) ;; *) fail "enter sandbox" "a redirect to $BASE/..." "${LINK:-(no redirect)}";; esac
  curl -sS -f -o /dev/null -c "$OWNER_JAR" -b "$OWNER_JAR" "$LINK"
fi

echo "== [3/6] Adopting cafe demo template (with photos, allergens and tables)"
SETUP_CSRF=$(curl -sS -c "$OWNER_JAR" -b "$OWNER_JAR" "$BASE/owner/console?location=$T" | csrf)
ADOPT_CODE=$(curl -sS -o /dev/null -w '%{http_code}' -c "$OWNER_JAR" -b "$OWNER_JAR" \
  "$BASE/owner/workspace/$T/setup" \
  --data-urlencode "_csrf=$SETUP_CSRF" \
  -d "action=adopt&template=cafe&theme=cafe")
expect_redirect "adopt cafe demo template" "$ADOPT_CODE"

echo "== [4/6] Creating manager and cook staff accounts"
TEAM_CSRF=$(curl -sS -c "$OWNER_JAR" -b "$OWNER_JAR" "$BASE/owner/console?view=team&location=$T" | meta)
for pair in "manager:MANAGER:Laura Martín" "cook:COOK:Carlos Ruiz"; do
  SUFFIX="${pair%%:*}"
  REST="${pair#*:}"
  ROLE="${REST%%:*}"
  FULLNAME="${REST#*:}"
  CODE=$(curl -sS -o /dev/null -w '%{http_code}' -c "$OWNER_JAR" -b "$OWNER_JAR" \
    -H "X-CSRF-TOKEN: $TEAM_CSRF" \
    -H "Referer: $BASE/owner/console?view=team&location=$T" \
    --data-urlencode "username=${T}${SUFFIX}" \
    --data-urlencode "fullName=$FULLNAME" \
    --data-urlencode "password=$PASS" \
    --data-urlencode "role=$ROLE" \
    "$BASE/owner/locations/$T/users")
  [ "$CODE" = "200" ] || fail "create $ROLE ${T}${SUFFIX}" "HTTP 200" "HTTP $CODE"
done

# Sign in manager and cook
for pair in "manager:$MANAGER_JAR" "cook:$COOK_JAR"; do
  SUFFIX="${pair%%:*}"
  JAR="${pair#*:}"
  LCSRF=$(curl -sS -c "$JAR" -b "$JAR" "$BASE/$T/login" | csrf)
  curl -sS -o /dev/null -c "$JAR" -b "$JAR" \
    --data-urlencode "_csrf=$LCSRF" \
    --data-urlencode "username=${T}${SUFFIX}" \
    --data-urlencode "password=$PASS" \
    "$BASE/login?tenantId=$T"
  STAFF_CODE=$(curl -sS -o /dev/null -w '%{http_code}' -b "$JAR" -c "$JAR" "$BASE/$T/dashboard")
  [ "$STAFF_CODE" = "200" ] || fail "${T}${SUFFIX} sign-in (dashboard reachable)" "HTTP 200" "HTTP $STAFF_CODE"
done

echo "== [5/6] Discovering catalog products, tables and signed table QR URL"
PRODUCTS_JSON=$(curl -sS -b "$MANAGER_JAR" -c "$MANAGER_JAR" -H "Referer: $BASE/$T/counter" "$BASE/counter/api/catalog/products")
TABLES_JSON=$(curl -sS -b "$MANAGER_JAR" -c "$MANAGER_JAR" -H "Referer: $BASE/$T/counter" "$BASE/counter/api/catalog/tables")
QR_HTML=$(curl -sS -b "$MANAGER_JAR" -c "$MANAGER_JAR" "$BASE/$T/backoffice/tables/qr-codes")

read -r PROD1 PROD2 PROD1_PRICE TABLE1 TABLE2 < <("$PY" - "$PRODUCTS_JSON" "$TABLES_JSON" <<'PYCODE' | tr -d '\r'
import json, sys
prods = json.loads(sys.argv[1])
tables = json.loads(sys.argv[2])
active_p = [p for p in prods if p.get('active', True)]
p1 = active_p[0]
p2 = active_p[1] if len(active_p) > 1 else p1
t1 = tables[0]['id'] if tables else ''
t2 = tables[1]['id'] if len(tables) > 1 else t1
print(p1['id'], p2['id'], p1.get('price', 1), t1, t2)
PYCODE
)
for v in PROD1 PROD2 PROD1_PRICE TABLE1 TABLE2; do require_nonempty "$v" "${!v:-}"; done

TABLE_QR_URL=$("$PY" - "$QR_HTML" "$BASE" "$T" <<'PYCODE'
import re, sys
html, base, tenant = sys.argv[1], sys.argv[2].rstrip('/'), sys.argv[3]
m = re.search(r'(?:https?://[^"\'\s<>]+)?/' + re.escape(tenant) + r'/menu\?table=[^"\'\s<>]+', html)
if not m:
    sys.exit(1)
url = m.group(0).replace('&amp;', '&')
if url.startswith('/'):
    url = base + url
else:
    # Rewrite origin to BASE so local proxy host matches
    path_idx = url.find('/' + tenant + '/menu')
    url = base + url[path_idx:]
print(url)
PYCODE
)

echo "== [6/6] Seeding fixed orders (paid counter order, unpaid table order, guest active & cancelled orders)"
COUNTER_CSRF=$(curl -sS -b "$MANAGER_JAR" -c "$MANAGER_JAR" "$BASE/$T/counter" | meta)
DASH_CSRF=$(curl -sS -b "$MANAGER_JAR" -c "$MANAGER_JAR" "$BASE/$T/dashboard" | meta)

list_orders() {
  curl -sS -f -X POST -b "$MANAGER_JAR" -c "$MANAGER_JAR" \
    -H "X-CSRF-TOKEN: $DASH_CSRF" \
    -H "Referer: $BASE/$T/dashboard" \
    "$BASE/$T/api/order"
}

# The guest's create-order answer carries no id, so find the order the way staff do: in the
# dashboard's order list, the one order that is new since BEFORE and sits at the given table.
find_new_order_id() {
  "$PY" - "$1" "$2" "$3" <<'PYCODE'
import json, sys
before = {o['id'] for o in json.loads(sys.argv[1])}
after, table = json.loads(sys.argv[2]), sys.argv[3]
added = [o for o in after if o['id'] not in before]
match = [o['id'] for o in added if o.get('tableId') == table]
if len(match) != 1:
    sys.stderr.write("FAIL: guest order not found in the dashboard list\n"
                     f"  expected: exactly one new order at table {table}\n"
                     f"  got:      {len(match)} (new orders: {[(o['id'], o.get('tableId')) for o in added]})\n")
    sys.exit(1)
print(match[0])
PYCODE
}

create_guest_order() {  # jar csrf line-id -> prints nothing; fails loudly unless status=success
  local resp
  resp=$(curl -sS -f -b "$1" -c "$1" \
    -H "Content-Type: application/json" \
    -H "X-CSRF-TOKEN: $2" \
    -H "Referer: $BASE/$T/menu" \
    -d "{\"items\":[{\"id\":\"$3\",\"itemId\":\"$PROD1\",\"quantity\":1,\"price\":$PROD1_PRICE,\"customizations\":[]}],\"serviceType\":\"DINE_IN\",\"tableNumber\":\"$TABLE1\"}" \
    "$BASE/$T/api/create-order")
  case "$resp" in *'"status":"success"'*) ;; *) fail "guest create-order ($3)" '{"status":"success",...}' "$resp";; esac
}

# Order 1: paid cash order at counter -> creates fiscal ticket & enters kitchen queue
PAID_ORDER_RESP=$(curl -sS -f -b "$MANAGER_JAR" -c "$MANAGER_JAR" \
  -H "Content-Type: application/json" \
  -H "X-CSRF-TOKEN: $COUNTER_CSRF" \
  -H "Referer: $BASE/$T/counter" \
  -d "{\"items\":[{\"id\":\"l1\",\"itemId\":\"$PROD1\",\"quantity\":2,\"customizations\":[]},{\"id\":\"l2\",\"itemId\":\"$PROD2\",\"quantity\":1,\"customizations\":[]}],\"paid\":true,\"paymentMethod\":\"CASH\",\"cashReceived\":50}" \
  "$BASE/counter/api/order")
PAID_ORDER_ID=$("$PY" -c "import json,sys; d=json.loads(sys.argv[1]); print(d.get('orderId') or d.get('id') or '')" "$PAID_ORDER_RESP")
require_nonempty "PAID_ORDER_ID" "$PAID_ORDER_ID"

# Order 2: unpaid table order -> sits in kitchen queue & occupies TABLE1
TABLE_ORDER_RESP=$(curl -sS -f -b "$MANAGER_JAR" -c "$MANAGER_JAR" \
  -H "Content-Type: application/json" \
  -H "X-CSRF-TOKEN: $COUNTER_CSRF" \
  -H "Referer: $BASE/$T/counter" \
  -d "{\"items\":[{\"id\":\"l1\",\"itemId\":\"$PROD2\",\"quantity\":1,\"customizations\":[]}],\"paid\":false,\"tableId\":\"$TABLE1\"}" \
  "$BASE/counter/api/order")
TABLE_ORDER_ID=$("$PY" -c "import json,sys; d=json.loads(sys.argv[1]); print(d.get('orderId') or d.get('id') or '')" "$TABLE_ORDER_RESP")
require_nonempty "TABLE_ORDER_ID" "$TABLE_ORDER_ID"

# Order 3: guest session with active table order -> paymentSelection & confirmation pages
BEFORE_GUEST=$(list_orders)
GUEST_MENU_CSRF=$(curl -sS -f -L -b "$GUEST_JAR" -c "$GUEST_JAR" "$TABLE_QR_URL" | meta)
require_nonempty "guest menu CSRF token" "$GUEST_MENU_CSRF"
create_guest_order "$GUEST_JAR" "$GUEST_MENU_CSRF" g1
GUEST_ORDER_ID=$(find_new_order_id "$BEFORE_GUEST" "$(list_orders)" "$TABLE1")
require_nonempty "GUEST_ORDER_ID" "$GUEST_ORDER_ID"

# A separate guest completes a real Stripe test-mode checkout. Keep the unpaid
# guest session above intact for the payment-selection screenshot.
BEFORE_ONLINE=$(list_orders)
ONLINE_CSRF=$(curl -sS -f -L -b "$ONLINE_GUEST_JAR" -c "$ONLINE_GUEST_JAR" "$TABLE_QR_URL" | meta)
require_nonempty "online guest menu CSRF token" "$ONLINE_CSRF"
create_guest_order "$ONLINE_GUEST_JAR" "$ONLINE_CSRF" online1
ONLINE_ORDER_ID=$(find_new_order_id "$BEFORE_ONLINE" "$(list_orders)" "$TABLE1")
require_nonempty "ONLINE_ORDER_ID" "$ONLINE_ORDER_ID"
curl -sS -f -o checkout-test.json -b "$ONLINE_GUEST_JAR" -c "$ONLINE_GUEST_JAR" \
  -H 'Content-Type: application/json' -H "X-CSRF-TOKEN: $ONLINE_CSRF" \
  -d '{"tipPercent":0}' "$BASE/$T/api/stripe/create-checkout-session"
node - "$RUN_DIR_ENV" "$BASE" "$T" <<'NODE'
const fs = require('fs'), path = require('path'), os = require('os');
const [dir, base, tenant] = process.argv.slice(2);
const checkout = JSON.parse(fs.readFileSync(path.join(dir, 'checkout-test.json')));
if (!checkout.sessionId?.startsWith('cs_test_') || new URL(checkout.checkoutUrl).hostname !== 'checkout.stripe.com') throw Error('Refusing non-test Stripe checkout');
const playwrightPath = process.env.PLAYWRIGHT_DIR || path.join(os.homedir(), 'AppData/Local/npm-cache/_npx/e41f203b7505f1fb/node_modules/playwright');
const { chromium } = require(playwrightPath);
const browserPath = process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE || path.join(os.homedir(), 'AppData/Local/ms-playwright/chromium-1234/chrome-win64/chrome.exe');
const jar = fs.readFileSync(path.join(dir, 'online-guest.cookies'), 'utf8');
const cookies = jar.split(/\r?\n/).map(s => s.replace(/^#HttpOnly_/, '').split('\t')).filter(a => a.length === 7).map(a => ({name:a[5], value:a[6], domain:a[0], path:a[2], secure:a[3] === 'TRUE'}));
(async () => {
  const browser = await chromium.launch({headless:true, executablePath:browserPath});
  try {
    const context = await browser.newContext({locale:'en-US'}); await context.addCookies(cookies);
    const page = await context.newPage(); await page.goto(checkout.checkoutUrl, {waitUntil:'domcontentloaded'});
    let form;
    for (let i=0; i<60 && !form; i++) { for (const frame of page.frames()) if (await frame.getByText('Card', {exact:true}).count()) { form=frame; break; } if (!form) await page.waitForTimeout(500); }
    if (!form) throw Error('Stripe card option unavailable');
    await form.getByText('Card', {exact:true}).first().click();
    await form.locator('input[name="number"]').waitFor({timeout:15000});
    await form.locator('input[name="email"]').fill('fixture@example.test');
    await form.locator('input[name="number"]').fill('4242424242424242');
    await form.locator('input[name="expiry"]').fill('1234');
    await form.locator('input[name="cvc"]').fill('123');
    await form.locator('input[name="name"]').fill('Test Customer');
    await form.getByRole('button', {name:'Pay', exact:true}).click();
    await page.waitForURL(url => url.origin === new URL(base).origin && url.pathname === `/${tenant}/order-confirmation`, {timeout:60000});
    await page.getByText('Payment received. Thank you!').waitFor({timeout:15000});
    console.log('Stripe test payment confirmed');
  } finally { await browser.close(); }
})().catch(e => { console.error(`FAIL: Stripe test payment did not complete (${e.message.split('\n')[0]})`); process.exitCode=1; });
NODE

# Order 4: guest session with cancelled order -> orderCancelled page via /select-payment
BEFORE_CANCEL=$(list_orders)
CANCEL_MENU_CSRF=$(curl -sS -f -L -b "$GUEST_CANCELLED_JAR" -c "$GUEST_CANCELLED_JAR" "$TABLE_QR_URL" | meta)
require_nonempty "cancelled guest menu CSRF token" "$CANCEL_MENU_CSRF"
create_guest_order "$GUEST_CANCELLED_JAR" "$CANCEL_MENU_CSRF" g2
CANCEL_ORDER_ID=$(find_new_order_id "$BEFORE_CANCEL" "$(list_orders)" "$TABLE1")
require_nonempty "CANCEL_ORDER_ID" "$CANCEL_ORDER_ID"

# Cancel it exactly as the kitchen dashboard does (dashboard.js cancelOrderUrl)
CANCEL_HTTP=$(curl -sS -o /dev/null -w '%{http_code}' -X POST -b "$MANAGER_JAR" -c "$MANAGER_JAR" \
  -H "Content-Type: application/json" \
  -H "X-CSRF-TOKEN: $DASH_CSRF" \
  -H "Referer: $BASE/$T/dashboard" \
  -d '{"value":"Fixture cancelled order"}' \
  "$BASE/$T/api/order/$CANCEL_ORDER_ID/cancel")
[ "$CANCEL_HTTP" = "200" ] || fail "cancel guest order $CANCEL_ORDER_ID" "HTTP 200" "HTTP $CANCEL_HTTP"

# Verify resulting fixture state loudly
for v in PAID_ORDER_ID TABLE_ORDER_ID GUEST_ORDER_ID ONLINE_ORDER_ID CANCEL_ORDER_ID; do require_nonempty "$v" "${!v:-}"; done

CANCELLED_PAGE=$(curl -sS -f -H 'Accept-Language: en' -b "$GUEST_CANCELLED_JAR" -c "$GUEST_CANCELLED_JAR" "$BASE/$T/select-payment")
CANCELLED_TITLE=$(echo "$CANCELLED_PAGE" | tr -d '\r\n' | grep -o '<title[^>]*>[^<]*</title>' | head -1 || true)
case "$CANCELLED_TITLE" in *'>Order cancelled'*'</title>') ;; *) fail "cancelled guest /select-payment" "the Order cancelled page" "${CANCELLED_TITLE:-(no title)}";; esac

# Kitchen queue: every seeded order is listed with the state it should have, and each guest's
# /order-confirmation shows that order's number.
FINAL_ORDERS=$(list_orders)
ORDER_NUMBERS=$("$PY" - "$FINAL_ORDERS" "$PAID_ORDER_ID" "$TABLE_ORDER_ID" "$GUEST_ORDER_ID" "$ONLINE_ORDER_ID" "$CANCEL_ORDER_ID" <<'PYCODE'
import json, sys
orders = {o['id']: o for o in json.loads(sys.argv[1])}
paid, table, guest, online, cancel = sys.argv[2:7]
expect = {paid: ('queued', 'PAID'), table: ('queued', 'UNPAID'), guest: ('queued', 'UNPAID'),
          online: ('queued', 'PAID'), cancel: ('CANCELLED', None)}
bad = []
for oid, (state, pay) in expect.items():
    o = orders.get(oid)
    if o is None:
        bad.append(f"{oid}: expected listed, got missing"); continue
    st = o.get('status')
    ok = st == 'CANCELLED' if state == 'CANCELLED' else st in ('CREATED', 'ACCEPTED', 'PROCESSING')
    if not ok:
        bad.append(f"{oid}: expected {state}, got status={st}")
    if pay and o.get('paymentStatus') != pay:
        bad.append(f"{oid}: expected paymentStatus={pay}, got {o.get('paymentStatus')}")
if bad:
    sys.stderr.write("FAIL: kitchen queue does not hold the seeded orders\n" + "".join(f"  {b}\n" for b in bad))
    sys.exit(1)
print(orders[guest]['orderNumber'], orders[online]['orderNumber'])
PYCODE
)
read -r GUEST_ORDER_NUMBER ONLINE_ORDER_NUMBER <<<"$(tr -d '\r' <<<"$ORDER_NUMBERS")"

check_confirmation() {  # jar expected-number label
  local html got
  html=$(curl -sS -f -L -b "$1" -c "$1" "$BASE/$T/order-confirmation")
  got=$("$PY" -c 'import re,sys; m=re.search(r"<[^>]*id=\"orderNumber\"[^>]*>\s*([^<]*?)\s*<", sys.stdin.read()); print(m.group(1) if m else "")' <<<"$html")
  [ "$got" = "$2" ] || fail "$3 /order-confirmation" "order number $2" "${got:-(no #orderNumber)}"
}
check_confirmation "$GUEST_JAR" "$GUEST_ORDER_NUMBER" "active guest"
check_confirmation "$ONLINE_GUEST_JAR" "$ONLINE_ORDER_NUMBER" "paid online guest"

# Write fixture.env
cat > "$RUN_DIR_POSIX/fixture.env" <<EOF
BASE=$BASE
PROD=$PROD
TENANT=$T
OWNER_USER=${T}owner
MANAGER_USER=${T}manager
COOK_USER=${T}cook
PASSWORD=$PASS
OWNER_JAR=$RUN_DIR_ENV/$OWNER_JAR
MANAGER_JAR=$RUN_DIR_ENV/$MANAGER_JAR
COOK_JAR=$RUN_DIR_ENV/$COOK_JAR
GUEST_JAR=$RUN_DIR_ENV/$GUEST_JAR
ONLINE_GUEST_JAR=$RUN_DIR_ENV/$ONLINE_GUEST_JAR
GUEST_CANCELLED_JAR=$RUN_DIR_ENV/$GUEST_CANCELLED_JAR
PRODUCT_1=$PROD1
PRODUCT_2=$PROD2
TABLE_1=$TABLE1
TABLE_2=$TABLE2
PAID_ORDER_ID=$PAID_ORDER_ID
TABLE_ORDER_ID=$TABLE_ORDER_ID
GUEST_ORDER_ID=$GUEST_ORDER_ID
ONLINE_ORDER_ID=$ONLINE_ORDER_ID
CANCEL_ORDER_ID=$CANCEL_ORDER_ID
EOF

echo "Fixture ready: $RUN_DIR_POSIX/fixture.env (tenant=$T)"
