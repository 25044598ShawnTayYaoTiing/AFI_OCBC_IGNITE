"""Real shared-stack acceptance; uses synthetic PDFs and never sends email.

Run with ai-service/.venv/Scripts/python.exe scripts/demo/customer_acceptance.py.
Uses existing reviewer credentials from the private root .env. Generated demo
customer credentials stay in ignored .local/customer-api-demo.json, never stdout.
No database resets, mock successful responses, infrastructure changes or deploys.
"""
import argparse
import json
import os
from pathlib import Path
import secrets
import uuid

import httpx
from dotenv import load_dotenv

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--base-url', default='http://localhost:8080')
    parser.add_argument('--state-file', type=Path, default=ROOT / '.local/customer-api-demo.json',
                        help='Private per-database demo credentials; use a fresh path for each disposable database.')
    parser.add_argument('--require-live-intake', action='store_true',
                        help='Require GEMINI for the three intake forms, investigation and replacement; report repository provenance separately.')
    args = parser.parse_args()
    load_dotenv(ROOT / '.env', override=False, encoding='utf-8-sig')
    staff = httpx.Client(base_url=args.base_url, auth=('operator', os.getenv('OPERATOR_PASSWORD', 'operator-demo')), timeout=600)
    reviewer = httpx.Client(base_url=args.base_url, auth=('reviewer', os.getenv('REVIEWER_PASSWORD', 'reviewer-demo')), timeout=600)
    portal = httpx.Client(base_url=args.base_url + '/api/customer', timeout=600)

    def call(client, method, path, expected=200, **kwargs):
        r = client.request(method, path, **kwargs)
        if r.status_code != expected:
            code = r.json().get('code', 'unknown') if r.headers.get('content-type', '').startswith('application/json') else 'non-json'
            raise AssertionError(f'{method} {path}: expected {expected}, got {r.status_code} ({code})')
        return None if expected == 204 else r.json()

    state_path = args.state_file.expanduser().resolve()
    if state_path.exists():
        state = json.loads(state_path.read_text(encoding='utf-8-sig'))
        if state['baseUrl'] != args.base_url:
            raise AssertionError('Private demo state belongs to a different API. Use the matching API or a separate private state file.')
    else:
        source = call(staff, 'POST', '/api/documents/demo/restore')['submission']['id']
        state = {'baseUrl': args.base_url, 'sourceId': source}
        for name, reference in [('primary', 'SYN-ALEX-001'), ('secondary', 'SYN-ISOLATION-' + uuid.uuid4().hex[:12])]:
            account = {'username': 'demo-' + uuid.uuid4().hex[:16], 'password': secrets.token_urlsafe(24)}
            profile = call(reviewer, 'POST', '/api/documents/customers', json={**account,
                'customerName': 'Alex Tan (Fictional)' if name == 'primary' else 'Isolation Customer (Fictional)',
                'customerReference': reference, 'relationshipManager': 'operator',
                'repositorySubmissionId': source if name == 'primary' else None})
            state[name] = {**account, 'id': profile['id']}
        state_path.parent.mkdir(parents=True, exist_ok=True)
        state_path.write_text(json.dumps(state, indent=2) + '\n', encoding='utf-8')
        if os.name != 'nt':
            state_path.chmod(0o600)

    primary = call(portal, 'POST', '/auth/token', json={k: state['primary'][k] for k in ['username', 'password']})
    secondary = call(portal, 'POST', '/auth/token', json={k: state['secondary'][k] for k in ['username', 'password']})
    headers = {'Authorization': 'Bearer ' + primary['accessToken']}
    other = {'Authorization': 'Bearer ' + secondary['accessToken']}
    assert call(portal, 'GET', '/me', headers=headers)['id'] == state['primary']['id']
    before = call(portal, 'GET', '/submissions', headers=headers)
    fixtures = ROOT / 'backend/src/main/resources/synthetic'
    names = ['customer-information.pdf', 'account-application.pdf', 'tax-residency.pdf']
    files = [('files', (name, (fixtures / name).read_bytes(), 'application/pdf')) for name in names]
    key = str(uuid.uuid4())
    first = call(portal, 'POST', '/submissions', headers={**headers, 'Idempotency-Key': key}, files=files)
    id = first['submission']['id']
    assert first['submission']['status'] == 'CORRECTION_REQUIRED', first['submission']['status']
    assert len(first['corrections']) == 2, 'Expected only address/date requests; inspect staff evidence if repository eligibility or extraction failed.'
    internal = call(staff, 'GET', '/api/documents/submissions/' + id)
    assert internal['submission']['submission_source'] == 'CUSTOMER_PORTAL'
    assert internal['submission']['owner_name'] == 'operator'
    assert any(e['check_key'] == 'MATCH_MAILING' and e['status'] == 'OPEN' for e in internal['exceptions'])
    assert len(internal['automation']) == 1 and internal['automation'][0]['status'] == 'VERIFIED'
    assert id in [s['id'] for s in call(staff, 'GET', '/api/documents/submissions')]
    replay = call(portal, 'POST', '/submissions', headers={**headers, 'Idempotency-Key': key}, files=files)
    assert replay['submission']['id'] == id
    call(portal, 'POST', '/submissions', expected=409, headers={**headers, 'Idempotency-Key': key}, files=list(reversed(files)))
    call(portal, 'GET', '/submissions/' + id, expected=404, headers=other)
    call(portal, 'GET', '/submissions/' + state['sourceId'], expected=404, headers=headers)
    tax = next(d for d in first['documents'] if d['type'] == 'TAX_RESIDENCY')
    path = '/submissions/' + id + '/documents/' + tax['id'] + '/replacement'
    corrected_file = [('files', ('tax-residency-corrected.pdf', (fixtures / 'tax-residency-corrected.pdf').read_bytes(), 'application/pdf'))]
    call(portal, 'POST', path, expected=404, headers={**other, 'Idempotency-Key': str(uuid.uuid4())}, files=corrected_file)
    correction_key = str(uuid.uuid4())
    result = call(portal, 'POST', path, headers={**headers, 'Idempotency-Key': correction_key}, files=corrected_file)
    assert result['submission']['status'] == 'OPERATIONS' and result['corrections'] == []
    call(portal, 'POST', path, headers={**headers, 'Idempotency-Key': correction_key}, files=corrected_file)
    final = call(staff, 'GET', '/api/documents/submissions/' + id)
    assert final['submission']['id'] == result['submission']['id']
    assert final['submission']['status'] == 'OPERATIONS'
    assert len(final['routing']) == 1 and len(final['automation']) == 1
    assert len(next(d for d in final['documents'] if d['id'] == tax['id'])['versions']) == 2
    assert not any(e['status'] == 'OPEN' for e in final['exceptions'])
    assert not any(t['status'] != 'RESOLVED' for t in final['tasks'])
    assert all(c['passed'] for c in final['checks'])
    assert len(call(portal, 'GET', '/submissions', headers=headers)) == len(before) + 1
    inbox = call(portal, 'GET', '/notifications', headers=headers)
    notices = [n for n in inbox if n['submissionId'] == id]
    assert {'CORRECTION_REQUIRED', 'OPERATIONS_READY'}.issubset({n['type'] for n in notices})
    assert all(n['channel'] == 'PORTAL' and n['deliveryStatus'] == 'RECORDED' for n in notices)
    methods = {d['document_type']: d['extraction']['method'] for d in internal['documents']}
    replacement_method = next(d for d in final['documents'] if d['id'] == tax['id'])['extraction']['method']
    investigation = internal['investigations'][0]['method']
    live = all(methods[k] == 'GEMINI' for k in ['CUSTOMER_INFORMATION', 'ACCOUNT_APPLICATION', 'TAX_RESIDENCY']) and investigation == 'GEMINI' and replacement_method == 'GEMINI'
    report = {'caseId': id, 'passed': not args.require_live_intake or live, 'functionalFlowPassed': True,
              'status': result['submission']['status'], 'checksPassed': len(final['checks']),
              'intakeMethods': methods, 'investigationMethod': investigation, 'replacementMethod': replacement_method,
              'intakeFallbackReasons': {d['document_type']: d['extraction'].get('fallback_reason') for d in internal['documents']},
              'liveIntakeAndCorrection': live, 'repositoryMethod': methods['SUPPORTING_DOCUMENT'],
              'routingRecords': len(final['routing']), 'customerIsolation': True, 'idempotentReplay': True, 'emailSent': False}
    print(json.dumps(report, indent=2))
    suffix = 'live' if live else 'live-attempt' if args.require_live_intake else 'fallback'
    (state_path.parent / f'customer-acceptance-{suffix}.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    call(portal, 'POST', '/auth/logout', expected=204, headers=headers)
    call(portal, 'GET', '/me', expected=401, headers=headers)
    call(portal, 'POST', '/auth/logout', expected=204, headers=other)
    if args.require_live_intake and not live:
        raise AssertionError('The functional flow passed, but real Gemini intake/correction was not verified. See the method report; fallback is not live AI.')


if __name__ == '__main__':
    main()
