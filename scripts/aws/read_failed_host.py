"""Fixed read-only host probe. Never deploy, restart, read environments or print raw logs."""
import json
import subprocess
import time

from read_deployment_diagnostics import INSTANCE, summarise

DOCUMENT = 'AFI-ResolveDiagnostics'

HOST_PROBE = r'''
import subprocess,json,re
SHA='a576e58f8dcfef416037f74e6f090bebda122dfb'
def docker(*args):
 p=subprocess.run(['docker',*args],capture_output=True,text=True,timeout=30)
 if p.returncode: return None
 return p.stdout
ids=(docker('ps','-aq') or '').split()
items=[]
for cid in ids:
 raw=docker('inspect',cid)
 if not raw: continue
 c=json.loads(raw)[0]; config=c.get('Config',{}); state=c.get('State',{})
 service=config.get('Labels',{}).get('com.docker.compose.service')
 if service not in ['backend','frontend','ai-service']: continue
 image=config.get('Image',''); failed=SHA in image
 item={'container':cid,'service':service,'failedRelease':failed,'status':state.get('Status'),
       'exitCode':state.get('ExitCode'),'oomKilled':state.get('OOMKilled'),
       'health':state.get('Health',{}).get('Status'),
       'logDriver':c.get('HostConfig',{}).get('LogConfig',{}).get('Type')}
 if failed:
  # docker logs sends application stderr to subprocess stderr; capture that privately too.
  p=subprocess.run(['docker','logs','--tail','250',cid],capture_output=True,text=True,timeout=30)
  text=p.stdout+'\n'+p.stderr
  signatures={'migration_failure':'Migration failed','flyway_validation':'Validate failed',
    'database_auth':'password authentication failed','connection_refused':'Connection refused',
    'missing_env':'Could not resolve placeholder','out_of_memory':'OutOfMemoryError',
    'disk_full':'No space left on device','nginx_upstream':'host not found in upstream',
    'spring_start_failure':'APPLICATION FAILED TO START'}
  item['errorIndicators']=[k for k,v in signatures.items() if v.lower() in text.lower()]
  item['sqlStates']=sorted(set(re.findall(r'SQL State\s*:\s*([0-9A-Z]{5})\b',text)))
  item['exceptionTypes']=[v for v in ['FlywayMigrateException','FlywayValidateException','PSQLException',
     'BeanCreationException','UnsatisfiedDependencyException','ApplicationContextException'] if v in text]
 items.append(item)
events=[]
raw=docker('events','--since','2026-11-14T16:37:00Z','--until','2026-11-14T16:43:00Z',
           '--filter','type=container','--format','{{json .}}') or ''
for line in raw.splitlines():
 try: e=json.loads(line)
 except ValueError: continue
 a=e.get('Actor',{}).get('Attributes',{}); service=a.get('com.docker.compose.service')
 if service not in ['backend','frontend','ai-service']: continue
 action=e.get('Action','')
 if action in ['die','oom','destroy','kill'] or action.startswith('health_status:'):
  events.append({'service':service,'container':e.get('Actor',{}).get('ID'),'action':action,
    'exitCode':a.get('exitCode'),'time':e.get('time'),'failedRelease':SHA in a.get('image','')})
print(json.dumps({'containers':items,'failedWindowEvents':events,
 'failedReleaseContainersRetained':sum(x['failedRelease'] for x in items),
 'note':'Removed containers may have lost their local logs. No current-release application logs or environment values were read.'}))
'''


def aws(*args):
    result = subprocess.run(['aws', *args, '--region', 'ap-southeast-1', '--output', 'json',
                             '--no-cli-pager'], capture_output=True, text=True, timeout=45)
    if result.returncode:
        code = 'AccessDeniedException' if 'AccessDenied' in result.stderr else 'AWS_REQUEST_FAILED'
        print(json.dumps({'error': code, 'requestedAction': 'ssm:SendCommand',
                          'document': 'arn:aws:ssm:ap-southeast-1:092004973012:document/' + DOCUMENT,
                          'target': 'arn:aws:ec2:ap-southeast-1:092004973012:instance/' + INSTANCE}))
        raise SystemExit(1)
    return json.loads(result.stdout)


def main():
    result = aws('ssm', 'send-command', '--instance-ids', INSTANCE,
                 '--document-name', DOCUMENT, '--document-version', '1',
                 '--timeout-seconds', '180', '--comment', 'Read-only failed AFI release diagnostics')
    command_id = result['Command']['CommandId']
    print('Read-only diagnostic command: ' + command_id, flush=True)
    for _ in range(40):
        time.sleep(5)
        response = subprocess.run(['aws', 'ssm', 'get-command-invocation', '--region', 'ap-southeast-1',
                                  '--command-id', command_id, '--instance-id', INSTANCE,
                                  '--output', 'json', '--no-cli-pager'], capture_output=True, text=True, timeout=30)
        if response.returncode:
            continue
        value = json.loads(response.stdout)
        if value['Status'] in ('Pending', 'InProgress', 'Delayed'):
            continue
        if value['Status'] != 'Success':
            print(json.dumps(summarise(value)))
            raise SystemExit(1)
        # The fixed host code emits structured diagnostics only, never raw application logs.
        report = json.loads(value['StandardOutputContent'])
        print(json.dumps(report, indent=2))
        return
    raise SystemExit('Read-only diagnostics timed out; inspect this command before retrying.')


if __name__ == '__main__':
    main()
