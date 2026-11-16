"""Small real-HTTP customer-to-Operations walkthrough; preserves existing cases.

Requires requests and python-dotenv. Private demo credentials and case IDs are
kept in ignored .local/customer-api-demo.json so reruns replay the same requests.
"""
import json
import os
import secrets
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import requests
from dotenv import load_dotenv

ROOT = Path(__file__).resolve().parents[2]
load_dotenv(ROOT / '.env', encoding='utf-8-sig')
BASE = os.getenv('AFI_API', 'http://localhost:5173').rstrip('/')
STATE = ROOT / '.local/customer-api-demo.json'
PDFS = ROOT / 'backend/src/main/resources/synthetic'


def main():
    staff = requests.Session()
    staff.auth = ('reviewer', os.getenv('REVIEWER_PASSWORD', 'reviewer-demo'))
    state = json.loads(STATE.read_text()) if STATE.exists() else {'baseUrl': BASE}
    assert state['baseUrl'] == BASE

    def save():
        STATE.parent.mkdir(exist_ok=True, parents=True)
        STATE.write_text(json.dumps(state, indent=2) + '\n')

    def call(session, method, path, expected=200, **kwargs):
        r = session.request(method, BASE + path, timeout=300, **kwargs)
        assert r.status_code == expected, (method, path, r.status_code, r.text[:300])
        return r.json() if r.content else None

    def files(names):
        return [('files', (name, (PDFS / name).read_bytes(), 'application/pdf')) for name in names]

    if 'sourceId' not in state:
        source = call(staff, 'POST', '/api/documents/demo/restore')
        state['sourceId'] = source['submission']['id']
        save()
    for label, name, reference in [('primary', 'Alex Tan (Fictional)', 'SYN-ALEX-001'),
                                    ('other', 'Morgan Lee (Fictional)', 'SYN-MORGAN-002')]:
        if label not in state:
            account = {'username': f'portal-{label}-{secrets.token_hex(4)}',
                       'password': secrets.token_urlsafe(24), 'customerName': name,
                       'customerReference': reference, 'relationshipManager': 'operator'}
            if label == 'primary':
                account['repositorySubmissionId'] = state['sourceId']
            result = call(staff, 'POST', '/api/documents/customers', json=account)
            state[label] = dict(account, id=result['id'])
            save()

    def login(label):
        session = requests.Session()
        result = call(session, 'POST', '/api/customer/auth/token', json={
            k: state[label][k] for k in ('username', 'password')})
        session.headers['Authorization'] = 'Bearer ' + result['accessToken']
        return session

    primary, other = login('primary'), login('other')
    call(requests.Session(), 'GET', '/api/customer/submissions', expected=401)
    call(staff, 'GET', '/api/customer/submissions', expected=401)
    call(primary, 'GET', '/api/documents/submissions', expected=401)
    call(primary, 'POST', '/api/documents/customers', expected=401, json={})
    initial = files(['customer-information.pdf', 'account-application.pdf', 'tax-residency.pdf'])
    key = 'portal-demo-originals'
    previous_ids = {c['id'] for c in call(primary, 'GET', '/api/customer/submissions')}
    # Concurrent retries must converge on one case and one processing attempt.
    with ThreadPoolExecutor(max_workers=2) as pool:
        results = list(pool.map(lambda _: call(primary, 'POST', '/api/customer/submissions',
                       headers={'Idempotency-Key': key}, files=initial), range(2)))
    case = results[0]['submission']['id']
    assert results[1]['submission']['id'] == case
    state['caseId'] = case
    save()
    path = '/api/customer/submissions/' + case
    assert len(call(primary, 'GET', '/api/customer/submissions')) == len(previous_ids) + (case not in previous_ids)
    call(primary, 'POST', '/api/customer/submissions', expected=409,
         headers={'Idempotency-Key': key}, files=files(['tax-residency-corrected.pdf']))
    call(other, 'GET', path, expected=404)
    call(other, 'GET', path + '/corrections', expected=404)
    call(other, 'POST', path + '/documents', expected=404,
         headers={'Idempotency-Key': 'other-customer-attempt'}, files=initial)
    assert call(other, 'GET', '/api/customer/notifications') == []
    data = call(primary, 'GET', path)
    assert set(data) == {'submission', 'documents', 'corrections'}
    assert not any(key in json.dumps(data) for key in ['evidence_json', 'rule_id', 'storage_key', 'recommendations', 'password_hash'])
    internal = call(staff, 'GET', '/api/documents/submissions/' + case)
    assert internal['submission']['submission_source'] == 'CUSTOMER_PORTAL'
    assert internal['submission']['owner_name'] == 'operator'
    assert any(at['status'] == 'VERIFIED' for at in internal['automation'])
    if os.getenv('AFI_REQUIRE_GEMINI', '').lower() == 'true':
        assert all(d['extraction']['method'] == 'GEMINI' for d in internal['documents'])
        assert internal['investigations'][0]['method'] == 'GEMINI'
    if data['submission']['status'] != 'OPERATIONS':
        assert data['submission']['status'] == 'CORRECTION_REQUIRED'
        assert len(data['corrections']) == 2
        call(staff, 'POST', '/api/documents/submissions/' + case + '/route', expected=409)
        tax = next(d for d in data['documents'] if d['type'] == 'TAX_RESIDENCY')
        state['taxId'] = tax['id']
        save()
    replacement = path + '/documents/' + state['taxId'] + '/replacement'
    call(primary, 'POST', path + '/documents/not-owned/replacement', expected=404,
         headers={'Idempotency-Key': 'wrong-document-attempt'}, files=files(['tax-residency-corrected.pdf']))
    for _ in range(2):
        data = call(primary, 'POST', replacement, headers={'Idempotency-Key': 'portal-demo-correction'},
                    files=files(['tax-residency-corrected.pdf']))
    assert data['submission']['status'] == 'OPERATIONS' and data['corrections'] == []
    internal = call(staff, 'GET', '/api/documents/submissions/' + case)
    assert len(internal['routing']) == 1
    assert len(next(d for d in internal['documents'] if d['id'] == state['taxId'])['versions']) == 2
    assert all(t['status'] == 'RESOLVED' for t in internal['tasks'])
    inbox = call(primary, 'GET', '/api/customer/notifications')
    assert {'SUBMISSION_RECEIVED', 'VALIDATION_COMPLETED', 'CORRECTION_REQUIRED',
            'CORRECTION_RECEIVED', 'OPERATIONS_READY'} <= {n['type'] for n in inbox}
    assert len({n['id'] for n in inbox}) == len(inbox)
    assert any(n['submission_id'] == case for n in call(staff, 'GET', '/api/documents/notifications'))
    rows = call(staff, 'GET', '/api/documents/submissions')
    assert next(c for c in rows if c['id'] == case)['verified_automations'] == 1
    for origin, expected in [('http://localhost:5174', 200), ('https://untrusted.invalid', 403)]:
        r = requests.options(BASE + '/api/customer/submissions', headers={
            'Origin': origin, 'Access-Control-Request-Method': 'POST',
            'Access-Control-Request-Headers': 'authorization,idempotency-key,content-type'}, timeout=10)
        assert r.status_code == expected, (origin, r.status_code)
    call(primary, 'POST', '/api/customer/auth/logout', expected=204)
    call(primary, 'GET', '/api/customer/me', expected=401)
    state['verified'] = True
    state['extractionMethods'] = [d['extraction']['method'] for d in internal['documents']]
    state['investigationMethods'] = [i['method'] for i in internal['investigations']]
    state['aiStatus'] = call(staff, 'GET', '/api/documents/ai-status')
    save()
    print('PASS customer intake -> automatic extraction/investigation -> verified attachment -> source correction -> Operations')
    print('Actual extraction/investigation methods: ' + json.dumps({k: state[k] for k in ('extractionMethods', 'investigationMethods')}))
    print('PASS concurrent retry deduplication, replacement idempotency, retained versions, notification inbox and dashboard source')
    print('PASS customer isolation, staff/customer separation, unknown replacement denial, CORS allowlist and logout revocation')
    print('Case: ' + BASE + '/submissions/' + case)
    print('Private demo credentials and IDs: .local/customer-api-demo.json')


if __name__ == '__main__':
    main()
