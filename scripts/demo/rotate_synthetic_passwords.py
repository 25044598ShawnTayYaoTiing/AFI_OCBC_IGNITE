"""Rotate only the existing synthetic customer and reviewer using private EC2 files.

Run with sudo on the existing host after preparing the private pending env file.
No passwords or hashes are printed, passed in command arguments, or sent to SSM.
"""
import ctypes,ctypes.util,crypt,json,os,re,shutil,stat,subprocess,warnings
from datetime import datetime,timezone
from pathlib import Path
from urllib.parse import parse_qs,urlsplit
warnings.filterwarnings('ignore',category=DeprecationWarning)
root=Path('/opt/afi-resolve'); stage='preflight'; db=None; swapped=False; primary_changed=False; committed=False

def run(args,**kwargs):
    p=subprocess.run(args,capture_output=True,text=True,timeout=360,**kwargs)
    if p.returncode:raise RuntimeError('Private command failed')
    return p.stdout
def read_env(path):
    return dict((k.strip(),v.strip().strip("'\"")) for line in path.read_text().splitlines()
                if line.strip() and not line.lstrip().startswith('#') and '=' in line
                for k,v in [line.split('=',1)])

try:
    original=Path('/home/ubuntu/afi-resolve-test-credentials.env')
    staged=Path('/home/ubuntu/afi-demo-credentials.pending.env')
    assert original.stat().st_mode & 0o777==0o600 and staged.stat().st_mode & 0o777==0o600
    old,new=read_env(original),read_env(staged)
    passwords={'AFI_TEST_RM_PASSWORD','AFI_TEST_CUSTOMER_PASSWORD'}
    assert old.keys()==new.keys() and all(old[k]==new[k] for k in old.keys()-passwords)
    assert new['AFI_TEST_RM_USERNAME']=='reviewer'
    user=new['AFI_TEST_CUSTOMER_EMAIL']; assert re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]{2,79}',user)
    assert all(8<=len(new[k].encode())<=72 for k in passwords)
    cfg=json.loads((root/'.deploy.json').read_text()); assert cfg['project_name']=='afi-resolve'
    assert cfg['env_files']==['.env'] and 'docker-compose.test-accounts.yml' not in cfg['compose_files']
    assert 'docker-compose.demo-reviewer-rate.yml' in cfg['compose_files']
    names=('backend','frontend','ai-service','https')
    before={n:json.loads(run(['docker','inspect','afi-resolve-'+n+'-1']))[0] for n in names}
    env=dict(x.split('=',1) for x in before['backend']['Config']['Env'] if '=' in x)
    assert env['REVIEWER_PASSWORD']==old['AFI_TEST_RM_PASSWORD']
    url=urlsplit(env['DB_URL'].removeprefix('jdbc:')); params=parse_qs(url.query)
    assert params['sslmode']==['verify-full'] and url.hostname=='afi-resolve-db.c3m460sie8eu.ap-southeast-1.rds.amazonaws.com'
    ca=next(m['Source'] for m in before['backend']['Mounts'] if m['Destination']==params['sslrootcert'][0])
    os.environ.update(PGHOST=url.hostname,PGPORT=str(url.port or 5432),PGDATABASE=url.path.lstrip('/'),
      PGUSER=env['DB_USERNAME'],PGPASSWORD=env['DB_PASSWORD'],PGSSLMODE='verify-full',PGSSLROOTCERT=ca,PGCONNECT_TIMEOUT='10')
    pq=ctypes.CDLL(ctypes.util.find_library('pq'))
    pq.PQconnectdb.argtypes=[ctypes.c_char_p];pq.PQconnectdb.restype=ctypes.c_void_p
    pq.PQstatus.argtypes=[ctypes.c_void_p];pq.PQstatus.restype=ctypes.c_int
    pq.PQexecParams.argtypes=[ctypes.c_void_p,ctypes.c_char_p,ctypes.c_int,ctypes.c_void_p,
      ctypes.POINTER(ctypes.c_char_p),ctypes.c_void_p,ctypes.c_void_p,ctypes.c_int];pq.PQexecParams.restype=ctypes.c_void_p
    pq.PQresultStatus.argtypes=[ctypes.c_void_p];pq.PQresultStatus.restype=ctypes.c_int
    pq.PQntuples.argtypes=[ctypes.c_void_p];pq.PQntuples.restype=ctypes.c_int
    pq.PQgetvalue.argtypes=[ctypes.c_void_p,ctypes.c_int,ctypes.c_int];pq.PQgetvalue.restype=ctypes.c_char_p
    pq.PQclear.argtypes=[ctypes.c_void_p];pq.PQfinish.argtypes=[ctypes.c_void_p]
    db=pq.PQconnectdb(b'');assert db and pq.PQstatus(db)==0
    def query(sql,params=()):
        encoded=[str(x).encode() for x in params]
        array=(ctypes.c_char_p*len(encoded))(*encoded) if encoded else None
        result=pq.PQexecParams(db,sql.encode(),len(encoded),None,array,None,None,0)
        if not result:raise RuntimeError('Database request failed')
        try:
            status=pq.PQresultStatus(result)
            if status not in (1,2):raise RuntimeError('Database request failed')
            return [pq.PQgetvalue(result,i,0).decode() for i in range(pq.PQntuples(result))]
        finally:pq.PQclear(result)
    stage='synthetic account check'
    rows=query("SELECT json_build_object('id',id,'reference',customer_reference,'rm',relationship_manager,"
      "'caseCount',(SELECT count(*) FROM document_submissions WHERE customer_id=customers.id))::text "
      "FROM customers WHERE username=$1 AND customer_reference='SYN-ALEX-001' AND relationship_manager='reviewer'",[user])
    assert len(rows)==1
    customer=json.loads(rows[0]);assert customer['caseCount']>=1
    hashed=crypt.crypt(new['AFI_TEST_CUSTOMER_PASSWORD'],crypt.mksalt(crypt.METHOD_BLOWFISH,rounds=1024))
    assert hashed and re.fullmatch(r'\$2[aby]\$10\$[./A-Za-z0-9]{53}',hashed)
    backup=root/'.local/deployment'/('before-demo-credentials-'+datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')+'.env')
    assert not backup.exists()
    original_stat=original.stat()
    shutil.copy2(original,backup);backup.chmod(0o600)
    primary=root/'.env';primary_data=primary.read_bytes();primary_stat=primary.stat()
    primary_backup=Path('/root')/('afi-resolve-env-before-demo-'+datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ'))
    fd=os.open(primary_backup,os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o600)
    with os.fdopen(fd,'wb') as f:f.write(primary_data)
    reviewer=new['AFI_TEST_RM_PASSWORD']
    assert "'" not in reviewer and '\n' not in reviewer and '\r' not in reviewer
    lines=primary_data.decode('utf-8').splitlines(keepends=True);updated=[];count=0
    for line in lines:
        name,sep,_=line.partition('=')
        if sep and name.strip()=='AWS_REVIEWER_PASSWORD':
            updated.append("AWS_REVIEWER_PASSWORD='"+reviewer+"'\n");count+=1
        else:updated.append(line)
    assert count==1
    stage='prepare effective reviewer password'
    os.replace(staged,original);swapped=True
    temp_primary=primary.with_name('.env.demo-rotation-tmp');assert not temp_primary.exists()
    fd=os.open(temp_primary,os.O_CREAT|os.O_EXCL|os.O_WRONLY,stat.S_IMODE(primary_stat.st_mode))
    with os.fdopen(fd,'wb') as f:f.write(''.join(updated).encode('utf-8'))
    os.chown(temp_primary,primary_stat.st_uid,primary_stat.st_gid);os.replace(temp_primary,primary);primary_changed=True
    refs={'backend':'BACKEND_IMAGE','frontend':'FRONTEND_IMAGE','ai-service':'AI_SERVICE_IMAGE'}
    denv=os.environ.copy();denv.update({refs[n]:before[n]['Config']['Image'] for n in refs})
    command=['docker','compose','--project-name',cfg['project_name']]
    for p in cfg['env_files']:command+=['--env-file',str(root/p)]
    for p in cfg['compose_files']+['docker-compose.images.yml']:command+=['-f',str(root/p)]
    rendered=json.loads(run(command+['config','--format','json'],env=denv))['services']['backend']['environment']
    assert rendered['REVIEWER_PASSWORD']==new['AFI_TEST_RM_PASSWORD']
    assert all(str(v)==env.get(k) for k,v in rendered.items() if k!='REVIEWER_PASSWORD')
    ai_before=dict(x.split('=',1) for x in before['ai-service']['Config']['Env'] if '=' in x)
    rendered_ai=json.loads(run(command+['config','--format','json'],env=denv))['services']['ai-service']['environment']
    assert rendered_ai['GEMINI_API_KEY']==ai_before['GEMINI_API_KEY'] and rendered_ai['GEMINI_MODEL']==ai_before['GEMINI_MODEL']
    stage='rotate synthetic customer password'
    query('BEGIN')
    changed=query("UPDATE customers SET password_hash=$1 WHERE id=$2 AND username=$3 AND customer_reference='SYN-ALEX-001' "
                  "AND relationship_manager='reviewer' RETURNING id",[hashed,customer['id'],user])
    assert changed==[customer['id']]
    query("UPDATE customer_sessions SET revoked_at=NOW() WHERE customer_id=$1 AND revoked_at IS NULL",[customer['id']])
    query('COMMIT');committed=True
    stage='recreate backend with existing image'
    run(command+['up','-d','--no-deps','--no-build','--pull','never','--force-recreate',
                 '--wait','--wait-timeout','300','backend'],env=denv)
    run(command+['exec','-T','frontend','nginx','-s','reload'],env=denv)
    stage='verify preserved configuration'
    after={n:json.loads(run(['docker','inspect','afi-resolve-'+n+'-1']))[0] for n in names}
    assert after['backend']['Image']==before['backend']['Image'] and after['backend']['State']['Health']['Status']=='healthy'
    assert all(after[n]['Id']==before[n]['Id'] for n in ('frontend','ai-service','https'))
    actual=dict(x.split('=',1) for x in after['backend']['Config']['Env'] if '=' in x)
    assert actual['REVIEWER_PASSWORD']==new['AFI_TEST_RM_PASSWORD']
    assert all(v==actual.get(k) for k,v in env.items() if k!='REVIEWER_PASSWORD')
    afterrows=query("SELECT json_build_object('id',id,'reference',customer_reference,'rm',relationship_manager,"
      "'caseCount',(SELECT count(*) FROM document_submissions WHERE customer_id=customers.id))::text FROM customers WHERE id=$1",[customer['id']])
    assert len(afterrows)==1 and json.loads(afterrows[0])==customer
    print(json.dumps({'reviewerRotated':True,'customerRotated':True,'customerUsername':user,
      'customerIdentityAndCasesPreserved':True,'backendImageUnchanged':True,'otherContainersUnchanged':True,
      'persistentOverrideValidated':True,'previousCredentialsBackedUpPrivately':True}))
except Exception as e:
    if db:
        try:query('ROLLBACK')
        except Exception:pass
    restored=False
    if swapped and not committed:
        try:
            temp=original.with_name('afi-demo-credentials.restore.env')
            shutil.copy2(backup,temp);os.chown(temp,original_stat.st_uid,original_stat.st_gid)
            os.chmod(temp,stat.S_IMODE(original_stat.st_mode));os.replace(temp,original);restored=True
        except Exception:pass
    if primary_changed and not committed:
        try:
            temp_primary=primary.with_name('.env.demo-restore-tmp');assert not temp_primary.exists()
            fd=os.open(temp_primary,os.O_CREAT|os.O_EXCL|os.O_WRONLY,stat.S_IMODE(primary_stat.st_mode))
            with os.fdopen(fd,'wb') as f:f.write(primary_data)
            os.chown(temp_primary,primary_stat.st_uid,primary_stat.st_gid);os.replace(temp_primary,primary)
        except Exception:restored=False
    status='; original private file restored' if restored else ''
    raise SystemExit('FAILED: '+stage+' ('+type(e).__name__+')'+status+'; private details suppressed. Inspect before retry.')
finally:
    if db:pq.PQfinish(db)
