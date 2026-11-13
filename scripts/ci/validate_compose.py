"""Validate deployment configuration using public placeholders; never print rendered secrets."""
import json
import os
import subprocess
from pathlib import Path

root = Path(__file__).resolve().parents[2]
env = os.environ.copy()
env.update({
    'DB_USERNAME': 'validation', 'DB_PASSWORD': 'validation-only',
    'AWS_AI_SERVICE_KEY': 'validation-only',
    'AWS_REVIEWER_PASSWORD': 'validation-only', 'AWS_RULES_ADMIN_PASSWORD': 'validation-only',
    'AWS_FRONTEND_ORIGIN': 'https://afi-ocbcignite.duckdns.org',
    'AWS_CUSTOMER_PORTAL_ORIGINS': 'https://ocbc-customer-portal.vercel.app',
    'AFI_HTTPS_DOMAIN': 'afi-ocbcignite.duckdns.org',
    'BACKEND_IMAGE': 'example.invalid/afi/backend:' + 'a' * 40,
    'FRONTEND_IMAGE': 'example.invalid/afi/frontend:' + 'a' * 40,
    'AI_SERVICE_IMAGE': 'example.invalid/afi/ai-service:' + 'a' * 40,
})
command = ['docker', 'compose', '--env-file', '.env.example']
for file in ('docker-compose.yml', 'docker-compose.aws.yml', 'docker-compose.https.yml',
             'docker-compose.test-accounts.yml', 'docker-compose.demo-reviewer-rate.yml', 'docker-compose.images.yml'):
    command += ['-f', file]
result = subprocess.run(command + ['config', '--format', 'json'], cwd=root, env=env,
                        capture_output=True, text=True)
if result.returncode:
    raise SystemExit('Compose validation failed; rendered environment was not printed.')
services = json.loads(result.stdout)['services']
assert all(not services[name].get('ports') for name in ('backend', 'ai-service', 'frontend'))
assert {str(p['published']) for p in services['https']['ports']} == {'80', '443'}
assert services['backend']['environment']['DOCUMENT_STORAGE_PROVIDER'] == 's3'
assert services['backend']['environment']['REVIEWER_PASSWORD'] == env['AWS_REVIEWER_PASSWORD']
assert 'sslmode=verify-full' in services['backend']['environment']['DB_URL']
assert 'postgres' not in services  # Disabled local-only profile is excluded from this AWS render.
assert 'postgres' not in services['backend'].get('depends_on', {})
assert services['backend']['environment'].get('CUSTOMER_DEMO_RETRY_ENABLED') is None
demo = subprocess.run(command + ['-f', 'docker-compose.demo-auth.yml', 'config', '--format', 'json'],
                      cwd=root, env=env, capture_output=True, text=True)
if demo.returncode:
    raise SystemExit('Demo Compose validation failed; rendered environment was not printed.')
assert json.loads(demo.stdout)['services']['backend']['environment']['CUSTOMER_DEMO_RETRY_ENABLED'] == 'true'
print('PASS: Compose merge, private application ports, HTTPS edge, RDS TLS and S3 configuration.')
