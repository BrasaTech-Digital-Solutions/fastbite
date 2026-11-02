"""Exercise the transfer estimator against an isolated, synthetic HTTP server."""
import gzip
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import threading
import unittest


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / 'scripts' / 'measure-transfer.sh'
BASH = (r'C:\Program Files\Git\bin\bash.exe'
        if Path(r'C:\Program Files\Git\bin\bash.exe').exists() else shutil.which('bash'))
PAGE = b'<link rel="stylesheet" href="/style.css"><script src="/app.js"></script>'
CSS = b'body{background:url(/image.png)}'
SCRIPT_BODY = b'console.log("test");'
IMAGE = b'fake-image'


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        self.server.requests.append((self.path, self.headers.get('Cookie'), self.headers.get('If-None-Match')))
        self.server.hosts.append(self.headers.get('Host'))
        if self.path == '/secure':
            if self.headers.get('Cookie') != '__session=secret-session':
                self.send_response(302)
                self.send_header('Location', '/login?return=%2Fsecure')
                self.end_headers()
                return
            return self.respond(PAGE, 'text/html', 'no-store')
        if self.path == '/login' or self.path.startswith('/login?'):
            return self.respond(b'<title>Log in</title>', 'text/html', 'no-store')
        if self.path == '/login-form':
            return self.respond(b'<form action="/login"><input type="password"></form>', 'text/html', 'no-store')
        if self.path == '/redirect-login':
            self.send_response(302)
            self.send_header('Location', '/login?return=private-token')
            self.end_headers()
            return
        if self.path == '/':
            self.server.page_visits += 1
            cookie = 'second' if self.server.change_cookie and self.server.page_visits > 1 else 'first'
            return self.respond(PAGE, 'text/html', 'no-store', extra={'Set-Cookie': f'session={cookie}; Path=/'})
        if self.path == '/style.css':
            # A public response that varies on cookies and encoding can still be
            # reused when both request values match.
            if self.headers.get('If-None-Match') == '"css"':
                self.send_response(304)
                self.send_header('ETag', '"css"')
                self.end_headers()
                return
            extra = {'Content-Encoding': 'gzip', 'Vary': 'Cookie, Accept-Encoding', 'ETag': '"css"'}
            if self.server.stale_style:
                extra['Date'] = 'Sun, 06 Nov 1994 08:49:37 GMT'
            return self.respond(gzip.compress(CSS, mtime=0), 'text/css', 'public, max-age=600',
                                extra=extra)
        if self.path == '/app.js':
            if self.headers.get('If-None-Match') == '"script"':
                self.send_response(304)
                self.send_header('ETag', '"script"')
                self.end_headers()
                return
            return self.respond(SCRIPT_BODY, 'text/javascript', 'no-cache', extra={'ETag': '"script"'})
        if self.path == '/image.png':
            return self.respond(IMAGE, 'image/png', 'public, max-age=31536000, immutable')
        self.send_error(404)

    def respond(self, body, content_type, cache_control, extra=None):
        self.send_response(200)
        self.send_header('Content-Type', content_type)
        self.send_header('Cache-Control', cache_control)
        self.send_header('Content-Length', str(len(body)))
        for key, value in (extra or {}).items():
            self.send_header(key, value)
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format, *args):
        pass


@unittest.skipUnless(BASH, 'bash is required')
class TransferMeasurementTest(unittest.TestCase):
    def run_fixture(self, change_cookie=False, stale_style=False):
        server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        server.requests = []
        server.hosts = []
        server.page_visits = 0
        server.change_cookie = change_cookie
        server.stale_style = stale_style
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()
        try:
            with tempfile.TemporaryDirectory() as directory:
                jar = Path(directory) / 'cookies.txt'
                jar.write_text('# Netscape HTTP Cookie File\n', encoding='utf-8')
                result = subprocess.run([BASH, str(SCRIPT), f'http://127.0.0.1:{server.server_port}',
                                         str(jar), '/'], capture_output=True, text=True, timeout=30,
                                        env={**os.environ, 'MSYS_NO_PATHCONV': '1'})
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            counts = re.findall(r'(?:First|Repeat) visit:\s+(\d+)', result.stdout)
            self.assertEqual(len(counts), 2, result.stdout)
            return list(map(int, counts)), server.requests
        finally:
            server.shutdown()
            server.server_close()
            worker.join()

    def run_cookie_fixture(self, host='127.0.0.1', page='/secure', cookie_path='/',
                           use_git_bash_paths=False):
        server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        server.requests, server.hosts = [], []
        server.page_visits = 0
        server.change_cookie = False
        server.stale_style = False
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()
        try:
            with tempfile.TemporaryDirectory() as directory:
                jar = Path(directory) / 'curl cookies.txt'
                # curl stores HttpOnly cookies with a #HttpOnly_ prefix and
                # expiry zero for a browser-session cookie.
                jar.write_text('# Netscape HTTP Cookie File\n'
                               f'#HttpOnly_{host}\tFALSE\t{cookie_path}\tFALSE\t0\t__session\tsecret-session\n',
                               encoding='utf-8')
                jar_arg = str(jar)
                env = dict(os.environ)
                env.pop('MSYS_NO_PATHCONV', None)
                if use_git_bash_paths:
                    if os.name != 'nt' or not Path(r'C:\Program Files\Git\bin\bash.exe').exists():
                        self.skipTest('Git Bash path conversion requires Windows Git Bash')
                    jar_arg = '/' + jar.drive[0].lower() + '/' + jar.as_posix()[3:]
                result = subprocess.run([BASH, str(SCRIPT), f'http://{host}:{server.server_port}',
                                         jar_arg, page], capture_output=True, text=True, timeout=30, env=env)
                return result, list(server.requests), list(server.hosts), server.server_port
        finally:
            server.shutdown()
            server.server_close()
            worker.join()

    def test_curl_httponly_session_cookie_is_sent_and_totals_are_broken_down(self):
        result, requests, hosts, port = self.run_cookie_fixture()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn(('/secure', '__session=secret-session', None), requests)
        self.assertIn('Start page: /secure', result.stdout)
        self.assertNotIn('secret-session', result.stdout + result.stderr)
        for label in ('HTML', 'CSS', 'JS', 'Fonts', 'Images'):
            self.assertGreaterEqual(len(re.findall(rf'{label}:\s*\d+ requests?,\s*\d+ bytes', result.stdout)), 2,
                                    result.stdout)
        first, repeat = result.stdout.split('Repeat visit:', 1)
        self.assertIn(f'HTML: 1 requests, {len(PAGE)} bytes', first)
        self.assertIn(f'CSS: 1 requests, {len(gzip.compress(CSS, mtime=0))} bytes', first)
        self.assertIn(f'JS: 1 requests, {len(SCRIPT_BODY)} bytes', first)
        self.assertIn(f'Images: 1 requests, {len(IMAGE)} bytes', first)
        self.assertIn('Fonts: 0 requests, 0 bytes', first)
        self.assertIn(f'HTML: 1 requests, {len(PAGE)} bytes', repeat)
        self.assertIn('CSS: 0 requests, 0 bytes', repeat)
        self.assertIn('JS: 1 requests, 0 bytes', repeat)  # conditional 304
        self.assertIn('Images: 0 requests, 0 bytes', repeat)
        self.assertIn('Fonts: 0 requests, 0 bytes', repeat)
        self.assertIn(f'127.0.0.1:{port}', hosts)

    def test_login_redirect_is_rejected_without_exposing_query(self):
        result, _, _, _ = self.run_cookie_fixture(page='/redirect-login')
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn('login', (result.stdout + result.stderr).lower())
        self.assertNotIn('private-token', result.stdout + result.stderr)

    def test_login_form_on_start_page_is_rejected(self):
        result, _, _, _ = self.run_cookie_fixture(page='/login-form')
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn('login', (result.stdout + result.stderr).lower())

    def test_host_cookie_that_is_not_sent_is_rejected(self):
        result, _, _, _ = self.run_cookie_fixture(cookie_path='/another-path')
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn('cookie', (result.stdout + result.stderr).lower())
        self.assertNotIn('secret-session', result.stdout + result.stderr)

    def test_localhost_subdomain_uses_loopback_preserving_host(self):
        result, requests, hosts, port = self.run_cookie_fixture(host='cafe.localhost')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn(('/secure', '__session=secret-session', None), requests)
        self.assertIn(f'cafe.localhost:{port}', hosts)

    def test_git_bash_page_and_cookie_paths(self):
        result, requests, _, _ = self.run_cookie_fixture(use_git_bash_paths=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn(('/secure', '__session=secret-session', None), requests)

    def test_fresh_vary_cookie_assets_are_cached_and_no_cache_is_revalidated(self):
        counts, requests = self.run_fixture()
        self.assertEqual(counts, [len(PAGE) + len(gzip.compress(CSS, mtime=0)) + len(SCRIPT_BODY) + len(IMAGE),
                                  len(PAGE)])
        self.assertEqual([path for path, _, _ in requests].count('/style.css'), 1)
        self.assertIn(('/app.js', 'session=first', '"script"'), requests)
        self.assertEqual([path for path, _, _ in requests].count('/image.png'), 1)

    def test_vary_cookie_change_fetches_new_representation_without_old_validator(self):
        counts, requests = self.run_fixture(change_cookie=True)
        self.assertEqual(counts[1], len(PAGE) + len(gzip.compress(CSS, mtime=0)))
        self.assertIn(('/style.css', 'session=second', None), requests)

    def test_old_origin_date_requires_revalidation_even_without_age_header(self):
        counts, requests = self.run_fixture(stale_style=True)
        self.assertEqual(counts[1], len(PAGE))
        self.assertIn(('/style.css', 'session=first', '"css"'), requests)


if __name__ == '__main__':
    unittest.main()
