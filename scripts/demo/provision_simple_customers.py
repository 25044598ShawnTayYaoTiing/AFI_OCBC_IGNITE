"""Create the ten explicitly requested prototype customer logins using the staff API."""
import argparse
import base64

from provision_test_accounts import Api, SetupError, credentials


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--credentials-file', required=True)
    parser.add_argument('--base-url', default='https://afi-ocbcignite.duckdns.org')
    args = parser.parse_args()
    values = credentials(args.credentials_file)
    api = Api(args.base_url)
    reviewer = values['AFI_TEST_RM_USERNAME']
    basic = 'Basic ' + base64.b64encode(
        (reviewer + ':' + values['AFI_TEST_RM_PASSWORD']).encode()).decode()
    _, staff = api.call('GET', '/api/auth/me', basic)
    if staff.get('username') != reviewer or 'REVIEWER' not in staff.get('roles', []):
        raise SetupError('Expected reviewer account is unavailable.')
    for number in range(1, 11):
        username = f'User{number}'
        reference = f'SYN-PROTOTYPE-USER-{number:02d}'
        status, _ = api.call('POST', '/api/documents/customers', basic, {
            'username': username,
            'password': 'Password123',
            'customerName': f'User {number}',
            'customerReference': reference,
            'relationshipManager': reviewer,
            'repositorySubmissionId': None,
        }, allowed=(200, 409))
        _, session = api.call('POST', '/api/customer/auth/token', body={
            'username': username, 'password': 'Password123',
        })
        bearer = 'Bearer ' + session['accessToken']
        try:
            _, profile = api.call('GET', '/api/customer/me', bearer)
            if profile.get('customerReference') != reference:
                raise SetupError(f'{username}: existing customer reference differs.')
        finally:
            api.call('POST', '/api/customer/auth/logout', bearer, allowed=(204,))
        print(f'{username}: {"created" if status == 200 else "already exists"}; login verified')


if __name__ == '__main__':
    try:
        main()
    except SetupError as exc:
        raise SystemExit(str(exc)) from None
