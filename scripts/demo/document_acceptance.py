"""Minimal live acceptance walkthrough against the real local services/database.

Creates an isolated synthetic demo case; preserves every earlier case and audit.
Run: python scripts/demo/document_acceptance.py
"""
import os
from pathlib import Path
import requests
import io
from pypdf import PdfWriter, PdfReader
from dotenv import load_dotenv

ROOT=Path(__file__).resolve().parents[2]
load_dotenv(ROOT/'.env',override=False,encoding='utf-8-sig')
BASE=os.getenv('AFI_API','http://127.0.0.1:8080')+'/api/documents'
client=requests.Session()
client.auth=('reviewer',os.getenv('REVIEWER_PASSWORD','reviewer-demo'))

def call(method,path,**kwargs):
    response=client.request(method,BASE+path,timeout=90,**kwargs)
    if not response.ok:
        raise AssertionError(f'{method} {path}: {response.status_code} {response.text[:500]}')
    return response.json()

def main():
    data=call('POST','/demo/restore')
    case=data['submission']['id']
    path='/submissions/'+case
    assert data['documents']==[]
    handles=[]
    try:
        for name in ['customer-information.pdf','account-application.pdf','tax-residency.pdf']:
            handle=(ROOT/'backend/src/main/resources/synthetic'/name).open('rb');handles.append(handle)
        data=call('POST',path+'/upload',files=[('files',(h.name.split(os.sep)[-1],h,'application/pdf')) for h in handles])
    finally:
        for h in handles:h.close()
    assert len(data['documents'])==4, 'Backend must attach the authorised source automatically during upload validation'
    assert len(data['automation'])==1 and data['automation'][0]['status']=='VERIFIED',data['automation']
    if os.getenv('AFI_REQUIRE_GEMINI','').lower()=='true':
        assert all(d['extraction']['method']=='GEMINI' for d in data['documents']), 'Live Gemini extraction required; fallback occurred'
        assert data['investigations'][0]['method']=='GEMINI','Live Gemini investigation required'
    with (ROOT/'backend/src/main/resources/synthetic/customer-information.pdf').open('rb') as pdf:
        assert client.post(BASE+path+'/upload',files=[('files',('duplicate.pdf',pdf,'application/pdf'))],timeout=30).status_code==409
    assert client.post(BASE+path+'/upload',files=[('files',('invalid.pdf',b'not a PDF','application/pdf'))],timeout=30).status_code==400
    assert requests.post(BASE+'/rules/draft',json={'sourceId':'none','correctiveAction':'test','severity':'WARNING'},timeout=10).status_code==401
    version=data['documents'][0]['version_id']
    private=client.get(BASE+path+'/versions/'+version+'/pdf',timeout=10)
    assert private.status_code==200 and private.content.startswith(b'%PDF-')
    assert requests.get(BASE+path+'/versions/'+version+'/pdf',timeout=10).status_code==401
    # No manual validation or execution click is needed after upload.
    opened=[e for e in data['exceptions'] if e['status']=='OPEN']
    assert len(data['exceptions'])==3 and len(opened)==2,[(e['description'],e['status']) for e in data['exceptions']]
    assert any(e['resolution_method']=='AUTOMATIC_VERIFIED_ATTACHMENT' for e in data['exceptions'])
    assert data['submission']['status']=='CORRECTION_REQUIRED'
    assert client.post(BASE+path+'/route',timeout=30).status_code==409
    before=call('POST',path+'/preflight')
    assert before['blockingIssues']==2 and not before['actualReady']
    assert before['predictedBlockingIssues']==2
    key=data['automation'][0]['attempt_key']
    action=call('POST',path+'/resolve',json={'idempotencyKey':key})
    assert action['outcome']=='ATTACHED_AND_REVALIDATED'
    assert call('POST',path+'/resolve',json={'idempotencyKey':key})==action
    data=call('GET',path)
    assert len(data['documents'])==4
    assert len([e for e in data['exceptions'] if e['status']=='OPEN'])==2
    tax=next(d for d in data['documents'] if d['document_type']=='TAX_RESIDENCY')
    with (ROOT/'backend/src/main/resources/synthetic/tax-residency-corrected.pdf').open('rb') as pdf:
        data=call('POST',path+'/upload?replace='+tax['id'],files=[('files',('tax-residency-corrected.pdf',pdf,'application/pdf'))])
    # Corrected source upload automatically revalidates and routes.
    assert data['submission']['status']=='OPERATIONS',data['submission']
    assert all(e['status']=='RESOLVED' for e in data['exceptions'])
    assert all(t['status']=='RESOLVED' for t in data['tasks'])
    assert len(next(d for d in data['documents'] if d['id']==tax['id'])['versions'])==2
    after=call('POST',path+'/preflight')
    assert after['blockingIssues']==0 and after['checksPassed']==after['totalChecks'] and after['actualReady']
    call('POST',path+'/route')
    assert len(call('GET',path)['routing'])==1
    assert len(call('GET',path)['automation'])==1
    assert call('GET','/analytics')['automaticallyResolved']>=1
    print(f'PASS {case}: automatic attachment, two human correction tasks, corrected upload -> {after["checksPassed"]}/{after["totalChecks"]}; Operations handover persisted.')
    print('PASS private PDF access, routing guard, idempotency, version history, verified execution history and analytics.')
    # Merge two supported form types into an explicitly ambiguous PDF. No new
    # infrastructure or real customer input: this must escalate, never route.
    other=call('POST','/submissions',json={'customerName':'Review Required (Fictional)','customerReference':'SYN-AMBIGUOUS','category':'ACCOUNT_OPENING'})
    other_path='/submissions/'+other['submission']['id']
    writer=PdfWriter()
    for name in ['customer-information.pdf','account-application.pdf']:
        reader=PdfReader(ROOT/'backend/src/main/resources/synthetic'/name)
        for page in reader.pages: writer.add_page(page)
    merged=io.BytesIO();writer.write(merged)
    uncertain=call('POST',other_path+'/upload',files=[('files',('ambiguous.pdf',merged.getvalue(),'application/pdf'))])
    assert uncertain['submission']['status']=='RESOLUTION_MANAGEMENT'
    assert uncertain['documents'][0]['extraction']['requires_review']
    assert not uncertain['routing']
    assert not any(at['status']=='VERIFIED' for at in uncertain['automation'])
    assert any(task['status']=='ESCALATED' for task in uncertain['tasks'])
    attempts=len(uncertain['automation'])
    repeated=call('POST',other_path+'/validate')
    assert len(repeated['automation'])==attempts,'Same evidence must not trigger repeated autonomous retries'
    print('PASS ambiguous extraction escalates to human review, blocks automation/routing, and cannot loop on identical evidence.')

if __name__=='__main__':main()
