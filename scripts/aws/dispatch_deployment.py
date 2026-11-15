"""Deploy through the existing scoped SSM document; no general Actions shell access."""
import json
import os
import re
import subprocess
import time
from log_redaction import Redactor

redact = Redactor()


def aws(*args):
    result = subprocess.run(['aws', *args, '--region', 'ap-southeast-1', '--output', 'json',
                             '--no-cli-pager'], capture_output=True, text=True, timeout=45)
    if result.returncode:
        raise RuntimeError(redact(result.stderr)[-6000:])
    return json.loads(result.stdout) if result.stdout.strip() else {}


def dispatch():
    version = os.environ['RELEASE_SHA']
    service = os.environ.get('SELECTED_SERVICES', 'all')
    instance = os.environ['EC2_INSTANCE_ID']
    if version != 'rollback' and not re.fullmatch('[0-9a-f]{40}', version):
        raise RuntimeError('Invalid release SHA.')
    if service not in ('all', 'backend', 'frontend', 'ai-service'):
        raise RuntimeError('Invalid service.')
    if version == 'rollback' and service != 'all':
        raise RuntimeError('Rollback requires services=all.')
    if instance != 'i-0368a57f3e9ccafa7':
        raise RuntimeError('This workflow targets only the existing AFI demo instance.')
    nodes = aws('ssm', 'describe-instance-information', '--filters', 'Key=InstanceIds,Values=' + instance)
    if not any(n.get('PingStatus') == 'Online' for n in nodes.get('InstanceInformationList', [])):
        raise RuntimeError('SSM node is offline; check its Agent, instance role and outbound HTTPS.')
    result = aws('ssm', 'send-command', '--instance-ids', instance,
                 '--document-name', 'AFI-ResolveDeploy', '--document-version', '1',
                 '--parameters', json.dumps({'ImageTag': [version], 'Services': [service]}),
                 '--timeout-seconds', '900')
    command_id = result['Command']['CommandId']
    print('SSM deployment command: ' + command_id, flush=True)
    deadline = time.monotonic() + 1100
    while time.monotonic() < deadline:
        try:
            invocation = aws('ssm', 'get-command-invocation', '--command-id', command_id,
                             '--instance-id', instance)
        except RuntimeError as error:
            if 'InvocationDoesNotExist' not in str(error):
                raise
            time.sleep(5)
            continue
        status = invocation['Status']
        if status not in ('Pending', 'InProgress', 'Delayed'):
            for key in ('StandardOutputContent', 'StandardErrorContent'):
                if invocation.get(key):
                    print(redact(invocation[key]), flush=True)
            if status != 'Success':
                combined = invocation.get('StandardOutputContent', '') + invocation.get('StandardErrorContent', '')
                if ('FAILED: deployment not confirmed' in combined
                        and 'AFI demo deploy tools v2' not in combined and os.environ.get('GITHUB_OUTPUT')):
                    with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
                        output.write('legacy_host_failure=true\n')
                raise RuntimeError(f'SSM command {command_id}: {status}, exit {invocation.get("ResponseCode")}. See redacted logs above.')
            print('PASS: deployment and host health checks completed.', flush=True)
            return
        time.sleep(5)
    raise RuntimeError('Deployment timed out; inspect SSM command ' + command_id + ' before retrying.')


if __name__ == '__main__':
    try:
        dispatch()
    except (RuntimeError, OSError, ValueError, KeyError, subprocess.SubprocessError) as error:
        raise SystemExit(redact(str(error)))
