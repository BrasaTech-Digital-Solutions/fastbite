#!/usr/bin/env bash
# Estimate wire-body bytes for an initial and immediately repeated page visit.
# Usage: measure-transfer.sh BASE_URL COOKIE_JAR PAGE_PATH
set -euo pipefail
if [ "$#" -ne 3 ]; then
  echo 'Usage: measure-transfer.sh BASE_URL COOKIE_JAR PAGE_PATH' >&2
  exit 2
fi
PY=$(command -v python3 || command -v python || true)
if [ -z "$PY" ]; then echo 'Python 3 is required.' >&2; exit 2; fi
# Git Bash converts POSIX arguments before a native Python process sees them.
# Convert the jar deliberately, and disable argument conversion for this call.
JAR=$2
if command -v cygpath >/dev/null 2>&1; then JAR=$(cygpath -w "$JAR"); fi
MSYS_NO_PATHCONV=1 "$PY" - "$1" "$JAR" "$3" <<'PY'
import email.utils
import gzip
import http.cookiejar
import io
import socket
from html.parser import HTMLParser
from pathlib import Path
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

base, jar_path, page = sys.argv[1:]
if urllib.parse.urlsplit(base).scheme not in ('http', 'https'):
    sys.exit('BASE_URL must use HTTP or HTTPS.')
if not Path(jar_path).is_file():
    sys.exit('COOKIE_JAR must be an existing Netscape-format cookie jar.')
start = urllib.parse.urljoin(base.rstrip('/') + '/', page.lstrip('/'))
if urllib.parse.urlsplit(start).netloc != urllib.parse.urlsplit(base).netloc:
    sys.exit('PAGE_PATH must be relative to BASE_URL.')

jar = http.cookiejar.MozillaCookieJar(jar_path)
try:
    # curl writes HttpOnly cookies as a comment-prefixed Netscape row. Python's
    # MozillaCookieJar otherwise skips them; curl's expiry 0 means session.
    rows = []
    for row in Path(jar_path).read_text(encoding='utf-8').splitlines(keepends=True):
        if row.startswith('#HttpOnly_'):
            row = row[len('#HttpOnly_'):]
        if not row.startswith('#') and row.strip():
            fields = row.rstrip('\r\n').split('\t')
            if len(fields) == 7 and fields[4] == '0':
                fields[4] = ''
                row = '\t'.join(fields) + '\n'
        rows.append(row)
    jar._really_load(io.StringIO(''.join(rows)), jar_path, True, False)
except (OSError, UnicodeError, http.cookiejar.LoadError):
    sys.exit('Could not read cookie jar.')

# RFC 6761 reserves localhost and subdomains for loopback. Windows does not
# consistently resolve subdomains; retain the original URL for Host and TLS.
_original_getaddrinfo = socket.getaddrinfo
def localhost_getaddrinfo(host, *args, **kwargs):
    if isinstance(host, str) and (host.lower().rstrip('.') == 'localhost' or host.lower().rstrip('.').endswith('.localhost')):
        host = '127.0.0.1'
    return _original_getaddrinfo(host, *args, **kwargs)
socket.getaddrinfo = localhost_getaddrinfo

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None

opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPCookieProcessor(jar), NoRedirect())
cache = {}

def safe_url(url):
    parts = urllib.parse.urlsplit(url)
    return urllib.parse.urlunsplit((parts.scheme, parts.netloc, parts.path, '', ''))

def resolve(parent, ref):
    ref = (ref or '').strip()
    if not ref or ref.startswith(('#', 'data:', 'blob:', 'javascript:', 'mailto:')):
        return None
    url = urllib.parse.urljoin(parent, ref)
    parts = urllib.parse.urlsplit(url)
    if parts.scheme not in ('http', 'https'):
        return None
    return urllib.parse.urlunsplit((parts.scheme, parts.netloc, parts.path, parts.query, ''))

def directives(headers):
    result = {}
    for part in headers.get('cache-control', '').split(','):
        key, _, value = part.strip().partition('=')
        if key:
            result[key.lower()] = value.strip().strip('"')
    return result

def request_for(url):
    request = urllib.request.Request(url, headers={
        'Accept-Encoding': 'gzip, br', 'User-Agent': 'FastBite-transfer-measure/1.0'})
    # Capture the cookie that would be sent now. This matters when the first
    # page creates a session and responses use Vary: Cookie.
    jar.add_cookie_header(request)
    return request

def request_headers(request):
    return {name.lower(): value for name, value in request.header_items()}

def fresh(entry, request):
    headers = entry['headers']
    cc = directives(headers)
    vary = {x.strip().lower() for x in headers.get('vary', '').split(',') if x.strip()}
    sent = request_headers(request)
    if ('no-store' in cc or 'no-cache' in cc or '*' in vary
            or ('cache-control' not in headers and 'no-cache' in headers.get('pragma', '').lower())
            or any(entry['request_headers'].get(key) != sent.get(key) for key in vary)):
        return False
    try:
        age = max(0, int(headers.get('age', '0')))
    except ValueError:
        age = 0
    # RFC cache age includes an old origin Date even when Age is absent.
    apparent_age = 0
    if headers.get('date'):
        try:
            date = email.utils.parsedate_to_datetime(headers['date']).timestamp()
            apparent_age = max(0, entry['stored'] - date)
        except (ValueError, TypeError, OverflowError):
            pass
    resident = max(0, time.time() - entry['stored'])
    try:
        lifetime = max(0, int(cc['max-age'])) if 'max-age' in cc else None
    except ValueError:
        lifetime = 0
    if lifetime is None and headers.get('expires'):
        try:
            expires = email.utils.parsedate_to_datetime(headers['expires']).timestamp()
            date = email.utils.parsedate_to_datetime(headers['date']).timestamp() if headers.get('date') else entry['stored']
            lifetime = max(0, expires - date)
        except (ValueError, TypeError, OverflowError):
            lifetime = 0
    return lifetime is not None and max(age, apparent_age) + resident < lifetime

def decode(raw, encoding):
    for method in reversed([x.strip().lower() for x in encoding.split(',') if x.strip()]):
        if method == 'gzip':
            raw = gzip.decompress(raw)
        elif method == 'br':
            try:
                import brotli
            except ImportError:
                sys.exit('A response used Brotli; install the Python brotli package to parse its assets.')
            raw = brotli.decompress(raw)
        elif method != 'identity':
            sys.exit('Unsupported Content-Encoding: ' + method)
    return raw

def fetch(url, visit):
    current = url
    total = 0
    requests = 0
    for _ in range(11):
        existing = cache.get(current)
        request = request_for(current)
        sent = request_headers(request)
        if visit == 2 and existing and fresh(existing, request):
            entry = existing
        else:
            if visit == 2 and existing:
                # Validators describe a particular Vary representation. Never
                # revalidate an older session's representation with new cookies.
                vary = {x.strip().lower() for x in existing['headers'].get('vary', '').split(',') if x.strip()}
                same_representation = '*' not in vary and all(
                    existing['request_headers'].get(key) == sent.get(key) for key in vary)
                if same_representation and existing['headers'].get('etag'):
                    request.add_header('If-None-Match', existing['headers']['etag'])
                elif same_representation and existing['headers'].get('last-modified'):
                    request.add_header('If-Modified-Since', existing['headers']['last-modified'])
            requests += 1
            try:
                response = opener.open(request, timeout=30)
            except urllib.error.HTTPError as error:
                response = error
            except (urllib.error.URLError, OSError) as error:
                raise RuntimeError('Request failed for ' + safe_url(current) + ': ' + type(error).__name__) from error
            with response:
                status = response.status
                response_headers = {k.lower(): v for k, v in response.headers.items()}
                raw = response.read()
            total += len(raw)
            if status == 304 and existing:
                merged = dict(existing['headers'])
                merged.update(response_headers)
                entry = {'headers': merged, 'body': existing['body'], 'stored': time.time(), 'status': existing['status'],
                         'request_headers': sent}
            elif status in (200, 301, 302, 303, 307, 308):
                entry = {'headers': response_headers, 'body': decode(raw, response_headers.get('content-encoding', '')),
                         'stored': time.time(), 'status': status, 'request_headers': sent}
            else:
                raise RuntimeError(f'HTTP {status} fetching {safe_url(current)}')
            if 'no-store' not in directives(entry['headers']):
                cache[current] = entry
            else:
                cache.pop(current, None)
        if entry['status'] not in (301, 302, 303, 307, 308):
            return current, entry, total, requests
        location = resolve(current, entry['headers'].get('location'))
        if not location:
            raise RuntimeError('Redirect without a valid HTTP location: ' + safe_url(current))
        current = location
    raise RuntimeError('Too many redirects: ' + safe_url(url))

css_urls = re.compile(r'''url\(\s*["']?([^\)"']+)["']?\s*\)|@import\s+["']([^"']+)["']''', re.I)
def css_refs(text):
    return [a or b for a, b in css_urls.findall(text)]

class Page(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.refs = []
        self.in_style = False
        self.base = None
        self.login_form = False
        self.password_field = False
    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == 'form' and re.search(r'/(?:login|signin|sign-in)(?:[/?#]|$)', attrs.get('action', ''), re.I):
            self.login_form = True
        if tag == 'input' and (attrs.get('type') or '').lower() == 'password':
            self.password_field = True
        if tag == 'base' and attrs.get('href') and self.base is None:
            self.base = attrs['href']
        if tag == 'style':
            self.in_style = True
        if attrs.get('style'):
            self.refs.extend(css_refs(attrs['style']))
        if tag == 'script' and attrs.get('src'):
            self.refs.append(attrs['src'])
        if tag in ('img', 'source', 'input') and attrs.get('src'):
            self.refs.append(attrs['src'])
        if tag in ('img', 'source') and attrs.get('srcset'):
            # Fetch all candidates conservatively; no viewport selection is simulated.
            self.refs.extend(part.strip().split()[0] for part in attrs['srcset'].split(',') if part.strip())
        if tag == 'video' and attrs.get('poster'):
            self.refs.append(attrs['poster'])
        if tag == 'link' and attrs.get('href'):
            rel = set((attrs.get('rel') or '').lower().split())
            if rel & {'stylesheet', 'preload', 'modulepreload', 'icon'}:
                self.refs.append(attrs['href'])
    def handle_endtag(self, tag):
        if tag == 'style': self.in_style = False
    def handle_data(self, data):
        if self.in_style: self.refs.extend(css_refs(data))

def text_body(entry):
    charset = 'utf-8'
    match = re.search(r'charset\s*=\s*["\']?([^;"\'\s]+)', entry['headers'].get('content-type', ''), re.I)
    if match: charset = match.group(1)
    try:
        return entry['body'].decode(charset, errors='replace')
    except LookupError:
        return entry['body'].decode('utf-8', errors='replace')

def category(url, headers):
    content = headers.get('content-type', '').lower().split(';')[0].strip()
    path = urllib.parse.urlsplit(url).path.lower()
    if content in ('text/html', 'application/xhtml+xml'): return 'HTML'
    if content == 'text/css' or path.endswith('.css'): return 'CSS'
    if content in ('text/javascript', 'application/javascript', 'application/x-javascript') or path.endswith(('.js', '.mjs')): return 'JS'
    if content.startswith('font/') or path.endswith(('.woff', '.woff2', '.ttf', '.otf', '.eot')): return 'Fonts'
    if content.startswith('image/') or path.endswith(('.png', '.jpg', '.jpeg', '.webp', '.gif', '.svg', '.ico', '.avif')): return 'Images'
    return 'Other'

def host_has_cookie(url):
    host = (urllib.parse.urlsplit(url).hostname or '').lower().rstrip('.')
    return any(host == c.domain.lower().lstrip('.') or host.endswith('.' + c.domain.lower().lstrip('.'))
               for c in jar if c.domain)

def visit(number):
    pending = [start]
    seen = set()
    transferred = 0
    breakdown = {name: [0, 0] for name in ('HTML', 'CSS', 'JS', 'Fonts', 'Images', 'Other')}
    final_path = None
    while pending:
        url = pending.pop(0)
        if url in seen: continue
        seen.add(url)
        if len(seen) > 500: raise RuntimeError('More than 500 referenced URLs; refusing an incomplete measurement.')
        if url == start and host_has_cookie(start) and not request_for(start).get_header('Cookie'):
            raise RuntimeError('A cookie exists for this host but no cookie would be sent to the start page.')
        final_url, entry, size, requests = fetch(url, number)
        transferred += size
        bucket = category(final_url, entry['headers'])
        breakdown[bucket][0] += requests
        breakdown[bucket][1] += size
        if url == start:
            final_path = urllib.parse.urlsplit(final_url).path or '/'
            if re.search(r'/(?:login|signin|sign-in)(?:/|$)', final_path, re.I):
                raise RuntimeError('Start page redirected to login; refusing to measure an unauthenticated page.')
        ctype = entry['headers'].get('content-type', '').lower()
        path = urllib.parse.urlsplit(final_url).path.lower()
        refs = []
        parent = final_url
        if 'text/html' in ctype or (url == start and not ctype):
            parser = Page()
            parser.feed(text_body(entry))
            if url == start and parser.login_form and parser.password_field:
                raise RuntimeError('Start page contains a login form; refusing to measure an unauthenticated page.')
            parent = resolve(final_url, parser.base) or final_url
            refs = parser.refs
        elif 'text/css' in ctype or path.endswith('.css'):
            refs = css_refs(text_body(entry))
        for ref in refs:
            asset = resolve(parent, ref)
            if asset and asset not in seen: pending.append(asset)
    return transferred, len(seen), breakdown, final_path

try:
    first, first_count, first_types, first_path = visit(1)
    repeat, repeat_count, repeat_types, repeat_path = visit(2)
except (RuntimeError, OSError, ValueError) as exc:
    sys.exit(str(exc))
if first_path != repeat_path:
    sys.exit('Start page changed path on the repeat visit; refusing to compare different pages.')
print(f'Start page: {first_path}')
for label, total, count, types in (('First visit', first, first_count, first_types), ('Repeat visit', repeat, repeat_count, repeat_types)):
    print(f'{label}: {total} encoded response-body bytes ({count} referenced URLs)')
    for name, (requests, size) in types.items():
        print(f'  {name}: {requests} requests, {size} bytes')
print('Includes static HTML/CSS references, CSS imports, fonts/images and srcset candidates; excludes HTTP headers and JS-created requests.')
print('Conservative estimate: fetches all srcset/font candidates; cannot simulate browser viewport or JavaScript execution.')
PY
