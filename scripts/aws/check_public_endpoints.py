"""Read-only readiness checks. Empty token request cannot create a login/session."""
import argparse
import json
from urllib import error, parse, request

PORTAL = 'https://ocbc-customer-portal.vercel.app'


def check(origin, customer=True):
    url = parse.urlsplit(origin)
    if (url.scheme != 'https' or not url.hostname or url.username or url.password
            or url.query or url.fragment or url.path not in ('', '/')):
        raise RuntimeError('Use a verified HTTPS origin without credentials or an API path.')
    origin = origin.rstrip('/')

    class NoRedirect(request.HTTPRedirectHandler):
        def redirect_request(self, *args):
            raise RuntimeError('Unexpected API redirect.')

    opener = request.build_opener(NoRedirect())

    def call(path, method='GET', headers=None, data=None):
        try:
            response = opener.open(request.Request(origin + path, method=method,
                                                   headers=headers or {}, data=data), timeout=20)
        except error.HTTPError as exc:
            response = exc
        with response:
            raw = response.read()
            try:
                body = json.loads(raw)
            except (ValueError, UnicodeError):
                body = {}
            return response.code, response.headers, body

    status, _, body = call('/actuator/health')
    if status != 200 or body.get('status') != 'UP':
        raise RuntimeError('Backend health check failed.')
    if not customer:
        print('PASS: HTTPS and backend health after rollback; customer integration not requalified.')
        return
    # A protected /me returning 401 alone does not prove customer routing is broken.
    status, _, body = call('/api/customer/auth/token', 'POST',
                           {'Content-Type': 'application/json'}, b'{}')
    if status != 401 or body.get('code') != 'INVALID_CREDENTIALS':
        raise RuntimeError('Customer token endpoint is not handled by the expected customer API.')
    status, headers, _ = call('/api/customer/auth/token', 'OPTIONS', {
        'Origin': PORTAL, 'Access-Control-Request-Method': 'POST',
        'Access-Control-Request-Headers': 'authorization,content-type,idempotency-key'})
    allowed = {v.strip().lower() for v in headers.get('Access-Control-Allow-Headers', '').split(',')}
    if (status != 200 or headers.get('Access-Control-Allow-Origin') != PORTAL
            or not {'authorization', 'content-type', 'idempotency-key'} <= allowed):
        raise RuntimeError('Exact-origin customer CORS preflight failed.')
    status, _, _ = call('/api/customer/auth/token', 'OPTIONS', {
        'Origin': 'https://untrusted.example', 'Access-Control-Request-Method': 'POST'})
    if status != 403:
        raise RuntimeError('Untrusted-origin preflight was not rejected.')
    print('PASS: HTTPS, backend health, customer token routing and exact-origin CORS.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--origin', default='https://afi-ocbcignite.duckdns.org')
    args = parser.parse_args()
    try:
        check(args.origin)
    except (RuntimeError, OSError):
        raise SystemExit('FAILED: public readiness; check routing, HTTPS and exact-origin CORS privately.')
