"""Helper-only safety tests; no AWS calls or live authentication claims."""
import contextlib
import io
import unittest

from provision_test_accounts import Api, NoRedirect, SetupError, activate


VALUES = {'AFI_TEST_CUSTOMER_EMAIL': 'synthetic-test',
          'AFI_TEST_CUSTOMER_PASSWORD': 'unused-synthetic-password',
          'AFI_TEST_RM_USERNAME': 'reviewer',
          'AFI_TEST_RM_PASSWORD': 'unused-synthetic-password'}


class StubApi:
    def __init__(self, roles=('REVIEWER',), conflict=False):
        self.calls = []
        self.roles = roles
        self.conflict = conflict

    def call(self, method, path, authorization=None, body=None, allowed=(200,)):
        self.calls.append((method, path, body))
        if path == '/api/auth/me':
            return 200, {'username': 'reviewer', 'roles': self.roles}
        if path == '/api/documents/customers':
            return (409 if self.conflict else 200), {}
        if path.endswith('/auth/token'):
            return 200, {'accessToken': 'unit-test-token'}
        if path == '/api/customer/me' and authorization.startswith('Bearer '):
            return 200, {'customerReference': 'SYN-ALEX-001'}
        return allowed[0], None


class ProvisioningSafetyTest(unittest.TestCase):
    def run_flow(self, api, provision):
        with contextlib.redirect_stdout(io.StringIO()):
            activate(api, VALUES, provision)

    def test_verify_does_not_provision_and_revokes_session(self):
        api = StubApi()
        self.run_flow(api, False)
        self.assertNotIn('/api/documents/customers', [p for _, p, _ in api.calls])
        self.assertEqual(api.calls[-1][1], '/api/customer/auth/logout')

    def test_duplicate_never_retries_creation_or_changes_identity(self):
        api = StubApi(conflict=True)
        self.run_flow(api, True)
        creates = [body for _, p, body in api.calls if p == '/api/documents/customers']
        self.assertEqual(len(creates), 1)
        self.assertEqual(creates[0]['username'], VALUES['AFI_TEST_CUSTOMER_EMAIL'])
        self.assertEqual(creates[0]['customerReference'], 'SYN-ALEX-001')

    def test_missing_reviewer_role_stops_before_account_mutation(self):
        api = StubApi(roles=('OPERATOR',))
        with self.assertRaises(SetupError):
            self.run_flow(api, True)
        self.assertEqual(len(api.calls), 1)

    def test_redirect_cannot_forward_credentials(self):
        with self.assertRaises(SetupError):
            NoRedirect().redirect_request(None, None, 302, '', {}, 'https://untrusted.example')

    def test_fixture_retry_preserves_request_key_and_body(self):
        api = Api('https://example.invalid')
        requests = []

        def capture(req, allowed, timeout):
            requests.append(req)
            return 200, {}

        api.send = capture
        api.submit_fixture('Bearer unit-test-only', 'synthetic-test')
        api.submit_fixture('Bearer unit-test-only', 'synthetic-test')
        headers = [{k.lower(): v for k, v in req.header_items()} for req in requests]
        self.assertEqual(headers[0]['idempotency-key'], headers[1]['idempotency-key'])
        self.assertEqual(requests[0].data, requests[1].data)
        self.assertEqual(requests[0].data.count(b'name="files"'), 1)
        self.assertIn(b'%PDF-', requests[0].data)


if __name__ == '__main__':
    unittest.main()
