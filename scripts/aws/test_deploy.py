"""Deployment safety tests with simulated Docker commands, never an AWS verification."""
import json
import contextlib
import io
import sys
import tempfile
import types
import unittest
from pathlib import Path
from unittest.mock import patch

import deploy


class DeploySafetyTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)
        self.config = {'region': 'ap-southeast-1', 'registry': '092004973012.dkr.ecr.ap-southeast-1.amazonaws.com',
                       'repository_prefix': 'afi-resolve', 'project_name': 'afi-resolve',
                       'compose_files': ['docker-compose.yml', 'docker-compose.aws.yml', 'docker-compose.https.yml'],
                       'env_files': ['.env'], 'public_origin': 'https://example.invalid'}
        (self.root / '.deploy.json').write_text(json.dumps(self.config))
        (self.root / '.env').write_text('PRESERVE_EXISTING_CONFIGURATION=1\n')
        self.before = {name: {'ref': 'existing-' + name, 'id': 'sha256:' + str(i) * 64}
                       for i, name in enumerate(deploy.SERVICES, 1)}
        self.commands = []
        self.fail_up = False
        self.fake_fcntl = types.SimpleNamespace(flock=lambda *args: None, LOCK_EX=1, LOCK_NB=2)

    def tearDown(self):
        self.directory.cleanup()

    def command(self, args, env=None):
        self.commands.append(args)
        if args[:2] == ['docker', 'inspect']:
            name = args[2].removeprefix('container-')
            return json.dumps([{'Config': {'Image': self.before[name]['ref']}, 'Image': self.before[name]['id']}])
        if 'ps' in args:
            return 'container-' + args[-1]
        if args[:3] == ['aws', 'ecr', 'get-login-password']:
            return 'unit-test-only'
        if self.fail_up and 'up' in args:
            raise RuntimeError('Simulated health failure')
        return ''

    def execute(self, version, services='backend'):
        with patch.object(deploy, 'ROOT', self.root), patch.object(deploy, 'run', side_effect=self.command), \
                patch.object(deploy.subprocess, 'run', return_value=types.SimpleNamespace(returncode=0)), \
                patch.dict(sys.modules, {'fcntl': self.fake_fcntl}), \
                patch('check_public_endpoints.check') as readiness, contextlib.redirect_stdout(io.StringIO()):
            deploy.execute(version, services)
            return readiness

    def test_backend_update_preserves_secrets_tls_and_prior_images(self):
        self.execute('a' * 40)
        up = next(c for c in self.commands if 'up' in c)
        self.assertEqual(up[-1], 'backend')
        self.assertIn('--no-deps', up)
        self.assertNotIn('down', [p for c in self.commands for p in c])
        self.assertTrue(any(c[-3:] == ['nginx', '-s', 'reload'] for c in self.commands))
        previous = json.loads((self.root / '.local/deployment/previous.json').read_text())
        self.assertEqual(previous['images'], self.before)
        self.assertEqual((self.root / '.env').read_text(), 'PRESERVE_EXISTING_CONFIGURATION=1\n')

    def test_failed_update_retains_snapshot_and_blocks_another_deploy(self):
        self.fail_up = True
        with self.assertRaises(RuntimeError):
            self.execute('a' * 40)
        state = self.root / '.local/deployment'
        previous = (state / 'previous.json').read_text()
        self.assertTrue((state / 'pending.json').exists())
        with self.assertRaises(RuntimeError):
            self.execute('b' * 40)
        self.assertEqual((state / 'previous.json').read_text(), previous)

    def test_rollback_uses_saved_local_image_ids_without_pulling_or_authenticating(self):
        state = self.root / '.local/deployment'
        state.mkdir(parents=True)
        (state / 'previous.json').write_text(json.dumps({'images': self.before, 'services': ['backend']}))
        (state / 'pending.json').write_text('{}')
        readiness = self.execute('rollback', 'all')
        self.assertFalse(any(c[:2] == ['aws', 'ecr'] or 'pull' in c for c in self.commands))
        readiness.assert_called_once_with('https://example.invalid', customer=False)
        self.assertFalse((state / 'pending.json').exists())

    def test_invalid_release_cannot_execute_commands(self):
        with self.assertRaises(RuntimeError):
            self.execute('main; invalid')
        self.assertEqual(self.commands, [])


if __name__ == '__main__':
    unittest.main()
