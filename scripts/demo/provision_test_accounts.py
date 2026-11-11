"""Opt-in provisioning through existing APIs. Never prints credentials or tokens."""
import argparse
import base64
import hashlib
import json
import re
from pathlib import Path
from urllib import error, parse, request


class SetupError(Exception):
    pass


def credentials(path):
    values = {}
    for line in Path(path).read_text(encoding='utf-8-sig').splitlines():
        if not line.strip() or line.lstrip().startswith('#'):
            continue
        key, sep, value = line.partition('=')
        if not sep:
            raise SetupError('Invalid credentials file format.')
        values[key.strip()] = value.strip().strip('\"\x27')
    required = ['AFI_TEST_CUSTOMER_EMAIL', 'AFI_TEST_CUSTOMER_PASSWORD',
                'AFI_TEST_RM_USERNAME', 'AFI_TEST_RM_PASSWORD']
    if any(not values.get(k) for k in required):
        raise SetupError('The private file must contain all four test credential values.')
    if values['AFI_TEST_RM_USERNAME'] != 'reviewer':
        raise SetupError('Existing staff configuration requires the reviewer username.')
    if not re.fullmatch(r'[a-zA-Z0-9][a-zA-Z0-9._-]{2,79}', values[required[0]]):
        raise SetupError('Customer login must be a provisionable username, not an email address.')
    for key in ['AFI_TEST_CUSTOMER_PASSWORD', 'AFI_TEST_RM_PASSWORD']:
        if len(values[key]) < 8 or len(values[key].encode('utf-8')) > 72:
            raise SetupError('Use passwords of at least 8 characters and at most 72 UTF-8 bytes.')
    return values


class NoRedirect(request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise SetupError('API redirected; refusing to forward private credentials.')


class Api:
    def __init__(self, base):
        url = parse.urlsplit(base)
        if (url.scheme != 'https' or not url.hostname or url.username or url.password
                or url.query or url.fragment or url.path not in ('', '/')):
            raise SetupError('Use the complete verified HTTPS backend origin without an API path.')
        self.base = base.rstrip('/')
        self.opener = request.build_opener(NoRedirect())

    def call(self, method, path, authorization=None, body=None, allowed=(200,)):
        headers = {'Accept': 'application/json'}
        if authorization:
            headers['Authorization'] = authorization
        data = None
        if body is not None:
            data = json.dumps(body).encode('utf-8')
            headers['Content-Type'] = 'application/json'
        req = request.Request(self.base + path, data=data, headers=headers, method=method)
        return self.send(req, allowed)

    def send(self, req, allowed, timeout=30):
        try:
            response = self.opener.open(req, timeout=timeout)
        except error.HTTPError as exc:
            response = exc
        except (error.URLError, TimeoutError):
            raise SetupError('HTTPS API connection failed; no credentials or server body logged.') from None
        with response:
            status = response.code
            raw = response.read()
        if status not in allowed:
            raise SetupError(f'API request: HTTP {status}; check deployment, credentials and roles.')
        if status in (204, 401, 403, 409):
            return status, None
        try:
            return status, json.loads(raw)
        except (ValueError, UnicodeError):
            raise SetupError('API response was not JSON; check API routing.') from None

    def submit_fixture(self, authorization, username):
        fixture = Path(__file__).resolve().parents[2] / 'backend/src/main/resources/synthetic/customer-information.pdf'
        data = fixture.read_bytes()
        if not data.startswith(b'%PDF-') or not 0 < len(data) <= 10 * 1024 * 1024:
            raise SetupError('The checked-in synthetic PDF fixture is missing or invalid.')
        digest = hashlib.sha256(username.encode() + b'\0' + fixture.name.encode() + b'\0' + data).hexdigest()
        # Stable for the same account/file: a retry reuses the existing case.
        boundary = 'afi-synthetic-' + digest
        body = (f'--{boundary}\r\nContent-Disposition: form-data; name="files"; filename="{fixture.name}"\r\n'
                'Content-Type: application/pdf\r\n\r\n').encode() + data + f'\r\n--{boundary}--\r\n'.encode()
        req = request.Request(self.base + '/api/customer/submissions', data=body, method='POST', headers={
            'Authorization': authorization, 'Content-Type': 'multipart/form-data; boundary=' + boundary,
            'Idempotency-Key': 'phase6-' + digest, 'Accept': 'application/json'})
        return self.send(req, (200,), timeout=660)[1]


def activate(api, values, provision, submit_fixture=False):
    username = values['AFI_TEST_CUSTOMER_EMAIL']
    rm = values['AFI_TEST_RM_USERNAME']
    basic = 'Basic ' + base64.b64encode(
        (rm + ':' + values['AFI_TEST_RM_PASSWORD']).encode('utf-8')).decode('ascii')
    _, staff = api.call('GET', '/api/auth/me', basic)
    if staff.get('username') != rm or not {'OPERATOR', 'REVIEWER'} <= set(staff.get('roles', [])):
        raise SetupError('Configured reviewer does not have the expected RM/reviewer roles.')
    if provision:
        # Fixed fixture reference is deliberate. Never change identity to bypass a collision.
        status, _ = api.call('POST', '/api/documents/customers', basic, {
            'username': username, 'password': values['AFI_TEST_CUSTOMER_PASSWORD'],
            'customerName': 'Alex Tan (Fictional)', 'customerReference': 'SYN-ALEX-001',
            'relationshipManager': rm, 'repositorySubmissionId': None,
        }, allowed=(200, 409))
        if status == 409:
            print('Identity already exists; verifying the supplied login without changing it.')
    _, session = api.call('POST', '/api/customer/auth/token', body={
        'username': username, 'password': values['AFI_TEST_CUSTOMER_PASSWORD']})
    bearer = 'Bearer ' + session['accessToken']
    try:
        _, profile = api.call('GET', '/api/customer/me', bearer)
        if profile.get('customerReference') != 'SYN-ALEX-001':
            raise SetupError('Existing customer identity does not match the synthetic fixture reference.')
        api.call('GET', '/api/customer/submissions', bearer)
        api.call('GET', '/api/documents/submissions', basic)
        api.call('GET', '/api/documents/submissions', bearer, allowed=(401, 403))
        api.call('GET', '/api/customer/me', basic, allowed=(401, 403))
        if submit_fixture:
            result = api.submit_fixture(bearer, username)
            case_id = result['submission']['id']
            if not re.fullmatch(r'DOC-[A-Za-z0-9-]+', case_id):
                raise SetupError('Unexpected submission identifier.')
            _, detail = api.call('GET', '/api/documents/submissions/' + case_id, basic)
            case = detail['submission']
            if (case.get('id') != case_id or case.get('submission_source') != 'CUSTOMER_PORTAL'
                    or case.get('owner_name') != rm or case.get('customer_reference') != 'SYN-ALEX-001'
                    or not detail.get('documents')):
                raise SetupError('Synthetic submission is not visible with the expected RM ownership and document.')
            print('PASS: one synthetic PDF submission visible to RM; case ID: ' + case_id)
    finally:
        api.call('POST', '/api/customer/auth/logout', bearer, allowed=(204,))
    print('PASS: customer login, reviewer roles, submission access and role separation; session revoked.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--credentials-file', type=Path, required=True)
    parser.add_argument('--base-url', default='https://afi-ocbcignite.duckdns.org')
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument('--activate', action='store_true', help='After approval: provision one customer and verify both logins.')
    mode.add_argument('--verify', action='store_true', help='Verify existing accounts without provisioning.')
    parser.add_argument('--submit-fixture', action='store_true',
                        help='With --verify, submit one checked-in synthetic PDF and check RM visibility after approval.')
    args = parser.parse_args()
    if args.submit_fixture and not args.verify:
        parser.error('--submit-fixture requires --verify and explicit approval for a synthetic upload.')
    try:
        values = credentials(args.credentials_file)
        api = Api(args.base_url)
        if args.activate or args.verify:
            activate(api, values, args.activate, args.submit_fixture)
        else:
            print('Private credentials validated. No network requests or account changes made.')
    except SetupError as exc:
        import sys
        print('FAILED: ' + str(exc), file=sys.stderr)
        return 1
    except OSError:
        import sys
        print('FAILED: private file could not be read; no file contents logged.', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
