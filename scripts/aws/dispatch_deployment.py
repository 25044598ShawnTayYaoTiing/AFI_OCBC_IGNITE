"""Manual workflow transport. Never opens ports, installs agents or modifies IAM."""
import base64
import json
import os
import re
import socket
import subprocess
import tempfile
import time
from pathlib import Path


def aws(*args):
    result = subprocess.run(['aws', *args, '--region', 'ap-southeast-1', '--output', 'json', '--no-cli-pager'],
                            capture_output=True, text=True)
    if result.returncode:
        raise RuntimeError('AWS request failed; verify the scoped deployment role and activation prerequisites.')
    return json.loads(result.stdout) if result.stdout.strip() else {}


def dispatch():
    version = os.environ['RELEASE_SHA']
    service = os.environ['SELECTED_SERVICES']
    transport = os.environ['DEPLOY_TRANSPORT']
    instance = os.environ['EC2_INSTANCE_ID']
    prefix = os.environ.get('ECR_REPOSITORY_PREFIX', 'afi-resolve')
    if version != 'rollback' and not re.fullmatch('[0-9a-f]{40}', version):
        raise RuntimeError('Invalid release SHA.')
    if service not in ('all', 'backend', 'frontend', 'ai-service') or transport not in ('ssm', 'eic'):
        raise RuntimeError('Invalid deployment selection.')
    if version == 'rollback' and service != 'all':
        raise RuntimeError('Rollback restores the previous release selection; choose services=all.')
    if instance != 'i-0368a57f3e9ccafa7':
        raise RuntimeError('This workflow only targets the existing AFI EC2 instance.')
    if not re.fullmatch('[a-z0-9][a-z0-9/_-]*', prefix):
        raise RuntimeError('Invalid repository prefix.')
    if version != 'rollback':
        for name in (('backend', 'frontend', 'ai-service') if service == 'all' else (service,)):
            result = aws('ecr', 'describe-images', '--repository-name', prefix + '/' + name,
                         '--image-ids', 'imageTag=' + version)
            if not result.get('imageDetails'):
                raise RuntimeError('Selected image has not been published.')
    if transport == 'ssm':
        nodes = aws('ssm', 'describe-instance-information', '--filters', 'Key=InstanceIds,Values=' + instance)
        if not any(n.get('PingStatus') == 'Online' for n in nodes.get('InstanceInformationList', [])):
            raise RuntimeError('SSM node is not online. Verify Agent, instance role and outbound connectivity; no changes were made.')
        result = aws('ssm', 'send-command', '--instance-ids', instance,
                     '--document-name', 'AFI-ResolveDeploy', '--document-version', '1',
                     '--parameters', json.dumps({'ImageTag': [version], 'Services': [service]}),
                     '--timeout-seconds', '900')
        command_id = result['Command']['CommandId']
        print('Deployment command submitted: ' + command_id)
        deadline = time.monotonic() + 1000
        while time.monotonic() < deadline:
            # Command invocation records are eventually consistent.
            result = subprocess.run(['aws', 'ssm', 'get-command-invocation', '--region', 'ap-southeast-1',
                                     '--command-id', command_id, '--instance-id', instance,
                                     '--output', 'json', '--no-cli-pager'], capture_output=True, text=True)
            if result.returncode:
                if 'InvocationDoesNotExist' not in result.stderr:
                    raise RuntimeError('Cannot read deployment status; inspect the command in AWS privately.')
                time.sleep(5)
                continue
            status = json.loads(result.stdout)['Status']
            if status == 'Success':
                print('PASS: host deployment completed with health checks.')
                return
            if status not in ('Pending', 'InProgress', 'Delayed'):
                raise RuntimeError('Deployment command did not succeed; inspect AWS command details privately.')
            time.sleep(5)
        raise RuntimeError('Deployment status timed out; inspect the existing command before retrying.')

    # EIC fallback: existing trusted SSH path only. No persistent user key or static AWS credential.
    reservations = aws('ec2', 'describe-instances', '--instance-ids', instance)['Reservations']
    info = reservations[0]['Instances'][0]
    host = info.get('PublicIpAddress')
    host_key = os.environ.get('EC2_SSH_HOST_KEY', '').strip()
    parts = host_key.split()
    if not host or len(parts) < 2 or parts[0] not in ('ssh-ed25519', 'ssh-rsa'):
        raise RuntimeError('EIC requires the verified public SSH host key in EC2_SSH_HOST_KEY.')
    base64.b64decode(parts[1], validate=True)
    try:
        with socket.create_connection((host, 22), timeout=8):
            pass
    except OSError:
        raise RuntimeError('Existing SSH network path is unavailable. Use SSM or the existing Console; no firewall rule was changed.') from None
    with tempfile.TemporaryDirectory(prefix='afi-eic-') as directory:
        key = Path(directory) / 'key'
        known = Path(directory) / 'known_hosts'
        known.write_text(host + ' ' + parts[0] + ' ' + parts[1] + '\n', encoding='utf-8')
        subprocess.run(['ssh-keygen', '-q', '-t', 'ed25519', '-N', '', '-f', str(key)], check=True,
                       capture_output=True)
        result = aws('ec2-instance-connect', 'send-ssh-public-key', '--instance-id', instance,
                     '--instance-os-user', 'ubuntu', '--availability-zone', info['Placement']['AvailabilityZone'],
                     '--ssh-public-key', 'file://' + str(key) + '.pub')
        if not result.get('Success'):
            raise RuntimeError('Temporary EC2 Instance Connect authentication failed.')
        command = f'python3 /opt/afi-resolve/scripts/aws/deploy.py {version} --services {service}'
        result = subprocess.run(['ssh', '-i', str(key), '-o', 'IdentitiesOnly=yes', '-o', 'BatchMode=yes',
                                 '-o', 'StrictHostKeyChecking=yes', '-o', 'UserKnownHostsFile=' + str(known),
                                 '-o', 'ConnectTimeout=10', 'ubuntu@' + host, command],
                                capture_output=True, text=True, timeout=1100)
        if result.returncode:
            raise RuntimeError('Host deployment failed; inspect the host privately. No SSH output was published.')
        print('PASS: EIC deployment completed with host health checks.')


if __name__ == '__main__':
    try:
        dispatch()
    except (RuntimeError, OSError, ValueError, KeyError, subprocess.SubprocessError) as exc:
        # Our RuntimeError messages contain no API payloads or credentials.
        raise SystemExit(str(exc) if isinstance(exc, RuntimeError) else 'Deployment dispatch failed; inspect prerequisites privately.')
