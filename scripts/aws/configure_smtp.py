"""Run on existing EC2 through an authorised Session Manager session.
Checks/reloads SMTP only, pins current images, and never prints secret values.
"""
import argparse
import json
import os
import re
import smtplib
import socket
import ssl
import stat
import sys
import tempfile
from datetime import datetime, timezone
from email.utils import parseaddr
from pathlib import Path

ROOT = Path('/opt/afi-resolve')
SMTP_KEYS = ('SMTP_ENABLED', 'SMTP_HOST', 'SMTP_PORT', 'SMTP_USERNAME', 'SMTP_PASSWORD',
             'SMTP_FROM', 'SMTP_STARTTLS', 'SMTP_TEST_RECIPIENTS')
OVERLAY_NAME = 'docker-compose.smtp.json'


def smtp_overlay():
    defaults = {'SMTP_ENABLED': 'false', 'SMTP_PORT': '587', 'SMTP_STARTTLS': 'true'}
    values = {key: '${' + key + ':-' + defaults.get(key, '') + '}' for key in SMTP_KEYS}
    values['SMTP_FROM'] = '${SMTP_FROM:-${MAIL_FROM:-}}'
    return {'services': {'backend': {'environment': values}}}


def non_smtp_changes(old, rendered):
    return sorted(key for key, value in rendered.items()
                  if key not in SMTP_KEYS and str(value) != old.get(key))


def masked(values):
    return {'enabled': values.get('SMTP_ENABLED', '').lower() == 'true',
            'senderConfigured': bool(values.get('SMTP_FROM', '').strip()),
            'hostConfigured': bool(values.get('SMTP_HOST', '').strip()),
            'port587': values.get('SMTP_PORT') == '587',
            'usernameConfigured': bool(values.get('SMTP_USERNAME')),
            'passwordConfigured': bool(values.get('SMTP_PASSWORD')),
            'starttls': values.get('SMTP_STARTTLS', 'true').lower() == 'true'}


def validate(values):
    state = masked(values)
    if not all(state.values()):
        raise ValueError('SMTP_FIELDS_INCOMPLETE_OR_DISABLED')
    if not values['SMTP_HOST'].lower().endswith('.brevo.com'):
        raise ValueError('EXPECTED_CONFIGURED_BREVO_HOST')
    if '\n' in values['SMTP_FROM'] or '\r' in values['SMTP_FROM']:
        raise ValueError('INVALID_SENDER')
    if not re.fullmatch(r'[^\s@<>]+@[^\s@<>]+\.[^\s@<>]+', parseaddr(values['SMTP_FROM'])[1]):
        raise ValueError('INVALID_SENDER')


def write_private(path, value):
    path.write_text(json.dumps(value, indent=2) + '\n', encoding='utf-8')
    path.chmod(0o600)


def main(apply=False):
    if ROOT.resolve() != ROOT or not (ROOT / '.deploy.json').is_file():
        raise ValueError('EXISTING_DEPLOYMENT_NOT_FOUND')
    os.umask(0o077)
    sys.path.insert(0, str(ROOT / 'scripts/aws'))
    import deploy
    from check_public_endpoints import check
    config_path = ROOT / '.deploy.json'
    config_raw = config_path.read_bytes()
    config = json.loads(config_raw)
    environment_before = {name: (ROOT / name).read_bytes() for name in config['env_files']}
    deploy.load_secret_filters(config)
    command = deploy.compose(config)
    env = os.environ.copy()
    env.update({value: 'sha256:' + '0' * 64 for value in deploy.IMAGE_VARS.values()})
    containers = {}
    for service in deploy.SERVICES:
        ids = deploy.run(command + ['ps', '--all', '-q', service], env).split()
        if len(ids) != 1:
            raise ValueError('EXPECTED_ONE_EXISTING_CONTAINER_PER_SERVICE')
        containers[service] = json.loads(deploy.run(['docker', 'inspect', ids[0]], env))[0]
        env[deploy.IMAGE_VARS[service]] = containers[service]['Config']['Image']
    old = dict(value.split('=', 1) for value in containers['backend']['Config']['Env'] if '=' in value)
    deploy.redact.add(old)
    result = {'currentBackendSmtp': masked(old), 'changesApplied': False,
              'imagesChanged': False, 'externalEmailSent': False}
    print(json.dumps(result), flush=True)
    overlay_path = ROOT / OVERLAY_NAME
    overlay_before = overlay_path.read_bytes() if overlay_path.exists() else None
    if overlay_before and json.loads(overlay_before) != smtp_overlay():
        raise ValueError('EXISTING_SMTP_OVERLAY_DIFFERS_STOPPED_TO_AVOID_CONFLICT')
    with tempfile.TemporaryDirectory(prefix='afi-smtp-check-') as temporary:
        candidate = Path(temporary) / 'smtp.json'
        write_private(candidate, smtp_overlay())
        rendered = json.loads(deploy.run(command + ['-f', str(candidate), 'config', '--format', 'json'], env))
        desired = {key: str(value) for key, value in rendered['services']['backend']['environment'].items()}
        deploy.redact.add(desired)
        result['desiredSmtp'] = masked(desired)
        print(json.dumps({'desiredSmtp': result['desiredSmtp']}), flush=True)
        differences = non_smtp_changes(old, desired)
        if differences:
            print(json.dumps({'nonSmtpVariableNamesDiffer': differences}), flush=True)
            raise ValueError('NON_SMTP_CONFIGURATION_DIFFERS_NO_CHANGES_MADE')
        validate(desired)
        socket.getaddrinfo(desired['SMTP_HOST'], 587, type=socket.SOCK_STREAM)
        result['dns'] = 'PASS'
        with smtplib.SMTP(desired['SMTP_HOST'], 587, timeout=15) as smtp:
            result['ec2Port587'] = 'PASS'
            smtp.ehlo()
            smtp.starttls(context=ssl.create_default_context())
            smtp.ehlo()
            result['starttls'] = 'PASS'
            code, _ = smtp.login(desired['SMTP_USERNAME'], desired['SMTP_PASSWORD'])
            if code != 235:
                raise ValueError('SMTP_AUTHENTICATION_NOT_ACCEPTED')
            result['authentication'] = 'PASS'
            result['smtpAuthCode'] = code
        print(json.dumps(result), flush=True)
        if not apply:
            return result
        # Do not race a deployment, an operator edit or another configuration process.
        import fcntl
        state = ROOT / '.local/deployment'
        state.mkdir(parents=True, exist_ok=True)
        with (state / 'lock').open('a') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            if (state / 'pending.json').exists() or config_path.read_bytes() != config_raw:
                raise ValueError('DEPLOYMENT_OR_CONFIGURATION_CHANGED_RETRY_AFTER_IT_COMPLETES')
            if any((ROOT / name).read_bytes() != value for name, value in environment_before.items()):
                raise ValueError('PRIVATE_ENVIRONMENT_CHANGED_RETRY_PREFLIGHT')
            for service, before in containers.items():
                current = deploy.run(command + ['ps', '--all', '-q', service], env).strip()
                if current != before['Id']:
                    raise ValueError('CONTAINER_CHANGED_RETRY_PREFLIGHT')
            backup = ROOT / '.local/smtp-backups' / datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')
            backup.mkdir(parents=True, mode=0o700)
            (backup / 'deploy.before.json').write_bytes(config_raw)
            (backup / 'deploy.before.json').chmod(0o600)
            rollback = backup / 'backend-environment.before.json'
            write_private(rollback, {'services': {'backend': {'environment': old}}})
            if overlay_before is not None:
                (backup / 'overlay.before.json').write_bytes(overlay_before)
            try:
                # This file contains variable references only; secrets stay in existing env files.
                write_private(overlay_path, smtp_overlay())
                updated = dict(config)
                updated['compose_files'] = list(config['compose_files'])
                if OVERLAY_NAME not in updated['compose_files']:
                    updated['compose_files'].append(OVERLAY_NAME)
                config_path.write_text(json.dumps(updated, indent=2) + '\n', encoding='utf-8')
                active = deploy.compose(updated)
                deploy.run(active + ['up', '-d', '--no-deps', '--no-build', '--pull', 'never',
                                     '--force-recreate', '--wait', '--wait-timeout', '180', 'backend'], env)
                # Reload the existing proxy's DNS target; do not recreate its container.
                deploy.run(active + ['exec', '-T', 'frontend', 'nginx', '-s', 'reload'], env)
                check(config['public_origin'])
                for service in ('frontend', 'ai-service'):
                    after = deploy.run(active + ['ps', '--all', '-q', service], env).strip()
                    if after != containers[service]['Id']:
                        raise ValueError('UNRELATED_CONTAINER_CHANGED')
                backend_id = deploy.run(active + ['ps', '--all', '-q', 'backend'], env).strip()
                new = json.loads(deploy.run(['docker', 'inspect', backend_id], env))[0]
                values = dict(v.split('=', 1) for v in new['Config']['Env'] if '=' in v)
                if not all(masked(values).values()):
                    raise ValueError('RUNNING_BACKEND_SMTP_NOT_ENABLED')
                result.update(changesApplied=True, backendOnlyRecreated=True,
                              unrelatedContainersUnchanged=True, runningBackendSmtp=masked(values),
                              backendHealth='PASS', configurationBackup=str(backup))
            except Exception:
                # Restore original configuration and actual old backend environment, same image.
                config_path.write_bytes(config_raw)
                if overlay_before is None:
                    overlay_path.unlink(missing_ok=True)
                else:
                    overlay_path.write_bytes(overlay_before)
                deploy.run(command + ['-f', str(rollback), 'up', '-d', '--no-deps', '--no-build',
                                     '--pull', 'never', '--force-recreate', '--wait',
                                     '--wait-timeout', '180', 'backend'], env)
                deploy.run(command + ['exec', '-T', 'frontend', 'nginx', '-s', 'reload'], env)
                check(config['public_origin'])
                print(json.dumps({'rollback': 'PASS', 'configurationBackup': str(backup)}), flush=True)
                raise
        write_private(backup / 'result.json', result)
        print(json.dumps(result, indent=2), flush=True)
        return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply', action='store_true', help='Apply SMTP overlay and recreate only the backend after all checks pass')
    args = parser.parse_args()
    try:
        main(args.apply)
    except Exception as error:
        value = {'status': 'FAIL', 'errorType': type(error).__name__}
        if isinstance(error, smtplib.SMTPResponseException):
            value['smtpResponseCode'] = error.smtp_code
        elif isinstance(error, ValueError):
            value['reason'] = str(error)
        print(json.dumps(value), flush=True)
        raise SystemExit(1)
