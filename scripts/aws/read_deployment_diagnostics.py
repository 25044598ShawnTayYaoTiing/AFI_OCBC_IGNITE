"""Read an existing SSM invocation. Never execute a command or publish raw logs."""
import json
import os
import re
import subprocess

INSTANCE = 'i-0368a57f3e9ccafa7'


def summarise(result):
    text = result.get('StandardOutputContent', '') + '\n' + result.get('StandardErrorContent', '')
    signatures = {
        'host_deployer_reported_failure': 'FAILED: deployment not confirmed',
        'docker_unhealthy': 'unhealthy',
        'docker_health_timeout': 'timed out waiting for',
        'flyway_migration_failure': 'Migration failed',
        'flyway_validation_failure': 'Validate failed',
        'database_connection_refused': 'Connection refused',
        'database_authentication_failure': 'password authentication failed',
        'database_tls_failure': 'PKIX path building failed',
        'missing_configuration': 'Could not resolve placeholder',
        'out_of_memory': 'OutOfMemoryError',
        'container_oom_killed': 'OOMKilled',
        'disk_full': 'No space left on device',
        'nginx_upstream_unavailable': 'host not found in upstream',
    }
    exceptions = ['FlywayMigrateException', 'FlywayValidateException', 'PSQLException',
                  'BeanCreationException', 'UnsatisfiedDependencyException',
                  'ApplicationContextException', 'ConnectException']
    return {
        'instance': INSTANCE,
        'commandId': result.get('CommandId'),
        'plugin': result.get('PluginName'),
        'status': result.get('Status'),
        'statusDetails': result.get('StatusDetails'),
        'responseCode': result.get('ResponseCode'),
        'started': result.get('ExecutionStartDateTime'),
        'ended': result.get('ExecutionEndDateTime'),
        'stdoutCharacters': len(result.get('StandardOutputContent', '')),
        'stderrCharacters': len(result.get('StandardErrorContent', '')),
        'matchedErrorIndicators': [name for name, value in signatures.items() if value.lower() in text.lower()],
        'exceptionTypes': [name for name in exceptions if name in text],
        'sqlStates': sorted(set(re.findall(r'SQL State\s*:\s*([0-9A-Z]{5})\b', text))),
        'rootCauseConfirmed': False,
        'note': 'Only returned command output was examined. Missing indicators do not prove absence of an error. Raw logs and output URLs are deliberately withheld.',
    }


def main():
    command = os.environ['FAILED_COMMAND_ID']
    if not re.fullmatch(r'[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}', command):
        raise SystemExit('A complete SSM command UUID is required.')
    response = subprocess.run([
        'aws', 'ssm', 'get-command-invocation', '--region', 'ap-southeast-1',
        '--instance-id', INSTANCE, '--command-id', command, '--output', 'json', '--no-cli-pager',
    ], capture_output=True, text=True, timeout=45)
    if response.returncode:
        known = next((code for code in ['AccessDeniedException', 'AccessDenied',
                     'InvocationDoesNotExist', 'ExpiredTokenException', 'InvalidInstanceId']
                     if code in response.stderr), 'AWS_REQUEST_FAILED')
        print(json.dumps({'error': known, 'requiredAction': 'ssm:GetCommandInvocation',
                          'instance': INSTANCE, 'commandId': command}))
        raise SystemExit(1)
    print(json.dumps(summarise(json.loads(response.stdout)), indent=2))


if __name__ == '__main__':
    main()
