"""Deploy immutable application images on the existing EC2 host; never change secrets or TLS."""
import argparse
import json
import os
import re
import subprocess
import tempfile
from functools import lru_cache
from pathlib import Path

SERVICES = ('backend', 'frontend', 'ai-service')
IMAGE_VARS = {'backend': 'BACKEND_IMAGE', 'frontend': 'FRONTEND_IMAGE', 'ai-service': 'AI_SERVICE_IMAGE'}
ROOT = Path(__file__).resolve().parents[2]


@lru_cache(maxsize=1)
def docker_command():
    # Reuse existing access; never add users to groups or change socket permissions.
    candidates = [('docker',), ('sudo', '-n', '--preserve-env=' + ','.join(IMAGE_VARS.values()), 'docker')]
    for candidate in candidates:
        try:
            result = subprocess.run([*candidate, 'info', '--format', '{{.ServerVersion}}'],
                                    capture_output=True, text=True, timeout=15)
        except (OSError, subprocess.SubprocessError):
            continue
        if result.returncode == 0:
            return candidate
    raise RuntimeError('Docker access is unavailable; existing non-interactive sudo is also unavailable.')


def docker_args(args, env=None):
    prefix = list(docker_command())
    config = (os.environ if env is None else env).get('DOCKER_CONFIG')
    if config:
        # sudo does not inherit this private auth-directory variable; pass only its path explicitly.
        prefix += ['--config', config]
    return prefix + args[1:]


def run(args, env=None):
    if args[0] == 'docker':
        args = docker_args(args, env)
    result = subprocess.run(args, capture_output=True, text=True, env=env, timeout=600)
    if result.returncode:
        # Docker/Compose errors can contain expanded configuration. Keep it private.
        raise RuntimeError('A deployment command failed; inspect the host privately. No command output logged.')
    return result.stdout


def save(path, value):
    temporary = path.with_suffix('.tmp')
    temporary.write_text(json.dumps(value, indent=2) + '\n', encoding='utf-8')
    temporary.replace(path)


def compose(config):
    result = ['docker', 'compose', '--project-name', config['project_name']]
    for path in config['env_files']:
        result += ['--env-file', str((ROOT / path).resolve())]
    for path in config['compose_files']:
        result += ['-f', str((ROOT / path).resolve())]
    return result + ['-f', str(ROOT / 'docker-compose.images.yml')]


def execute(version, service):
    import fcntl  # Linux EC2 host only; import here keeps local helper tests portable.
    os.umask(0o077)
    config = json.loads((ROOT / '.deploy.json').read_text(encoding='utf-8'))
    if not re.fullmatch(r'[0-9]{12}\.dkr\.ecr\.[a-z0-9-]+\.amazonaws\.com', config['registry']):
        raise RuntimeError('Invalid ECR registry.')
    if not re.fullmatch(r'[a-z0-9][a-z0-9/_-]*', config['repository_prefix']):
        raise RuntimeError('Invalid ECR repository prefix.')
    if not re.fullmatch(r'[a-z0-9][a-z0-9_-]*', config['project_name']):
        raise RuntimeError('Set the existing Compose project name explicitly.')
    if version != 'rollback' and not re.fullmatch(r'[0-9a-f]{40}', version):
        raise RuntimeError('Use a complete Git SHA or rollback.')
    if version == 'rollback' and service != 'all':
        raise RuntimeError('Rollback restores the saved release selection; choose services=all.')
    state = ROOT / '.local/deployment'
    state.mkdir(parents=True, exist_ok=True)
    command = compose(config)
    # Use the previously recorded selection for Compose interpolation. Never source .env in a shell.
    env = os.environ.copy()
    for name in IMAGE_VARS.values():
        env[name] = 'sha256:' + '0' * 64
    with (state / 'lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        if version != 'rollback' and (state / 'pending.json').exists():
            raise RuntimeError('An unfinished deployment exists. Review it and perform a compatible rollback before retrying.')
        # Pass interpolation via environment rather than printing a rendered Compose configuration.
        current = {}
        for item in SERVICES:
            ids = run(command + ['ps', '--all', '-q', item], env).split()
            if len(ids) != 1:
                raise RuntimeError('Expected one existing container per service in the configured Compose project.')
            container = json.loads(run(['docker', 'inspect', ids[0]], env))[0]
            current[item] = {'ref': container['Config']['Image'], 'id': container['Image']}
        if version == 'rollback':
            prior = json.loads((state / 'previous.json').read_text(encoding='utf-8'))
            changed = prior['services']
            desired = {name: current[name]['id'] for name in SERVICES}
            for name in changed:
                desired[name] = prior['images'][name]['id']
                # Do not pull mutable tags during rollback. Keep prior image IDs on this host.
                run(['docker', 'image', 'inspect', desired[name]], env)
        else:
            selected = SERVICES if service == 'all' else (service,)
            desired = {name: current[name]['id'] for name in SERVICES}
            for name in selected:
                desired[name] = f"{config['registry']}/{config['repository_prefix']}/{name}:{version}"
            changed = [name for name in selected if desired[name] != current[name]['ref']]
        env.update({IMAGE_VARS[name]: image for name, image in desired.items()})
        run(command + ['config', '--quiet'], env)
        if not changed:
            from check_public_endpoints import check
            check(config['public_origin'])
            print('Selected images already active; no services restarted.')
            return
        with tempfile.TemporaryDirectory(prefix='afi-ecr-login-') as auth_dir:
            env['DOCKER_CONFIG'] = auth_dir
            if version != 'rollback':
                # The EC2 host must use its existing instance role, not a saved human login/key.
                auth_env = {k: v for k, v in env.items() if k not in (
                    'AWS_ACCESS_KEY_ID', 'AWS_SECRET_ACCESS_KEY', 'AWS_SESSION_TOKEN', 'AWS_PROFILE',
                    'AWS_WEB_IDENTITY_TOKEN_FILE', 'AWS_ROLE_ARN', 'AWS_CONTAINER_CREDENTIALS_RELATIVE_URI',
                    'AWS_CONTAINER_CREDENTIALS_FULL_URI')}
                auth_env.update(AWS_SHARED_CREDENTIALS_FILE=os.devnull, AWS_CONFIG_FILE=os.devnull,
                                AWS_EC2_METADATA_DISABLED='false')
                token = run(['aws', 'ecr', 'get-login-password', '--region', config['region']], auth_env)
                login = subprocess.run(docker_args(['docker', 'login', '--username', 'AWS', '--password-stdin', config['registry']], env),
                                       input=token, capture_output=True, text=True, env=env, timeout=30)
                del token
                if login.returncode:
                    raise RuntimeError('ECR authentication failed; verify the existing instance role pull policy.')
                run(command + ['pull', *changed], env)
                save(state / 'previous.json', {'images': current, 'services': changed})
            save(state / 'pending.json', {'version': version, 'services': changed})
            # No down, volumes, builds, source checkout, database resets or secret changes.
            run(command + ['up', '-d', '--no-deps', '--no-build', '--pull', 'never',
                           '--wait', '--wait-timeout', '300', *changed], env)
            if any(name in changed for name in ('backend', 'ai-service')):
                # Existing Nginx resolves upstream container addresses on reload.
                run(command + ['exec', '-T', 'frontend', 'nginx', '-s', 'reload'], env)
            from check_public_endpoints import check
            check(config['public_origin'], customer=version != 'rollback')
            save(state / 'current.json', {'version': version, 'images': desired, 'services': changed})
            (state / 'pending.json').unlink()
            print('PASS: selected application images healthy; public readiness passed. TLS and data services preserved.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('version', help='Previously built 40-character SHA, or rollback.')
    parser.add_argument('--services', choices=('all', *SERVICES), default='all')
    args = parser.parse_args()
    try:
        execute(args.version, args.services)
    except (RuntimeError, OSError, ValueError, KeyError, subprocess.SubprocessError):
        raise SystemExit('FAILED: deployment not confirmed. Inspect host privately; any previous-image snapshot is retained. Review schema compatibility before rollback.')
