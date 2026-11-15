import unittest
import json
from pathlib import Path
from unittest.mock import patch

from read_deployment_diagnostics import summarise
import read_failed_host


class DiagnosticsTest(unittest.TestCase):
    def test_secret_text_is_not_published(self):
        raw = {'StandardErrorContent': 'password=private-test-value\nFlywayMigrateException\nSQL State : 23505',
               'StandardOutputContent': 'FAILED: deployment not confirmed', 'Status': 'Failed'}
        result = summarise(raw)
        self.assertNotIn('private-test-value', str(result))
        self.assertEqual(result['exceptionTypes'], ['FlywayMigrateException'])
        self.assertEqual(result['sqlStates'], ['23505'])
        self.assertFalse(result['rootCauseConfirmed'])

    def test_generic_host_error_is_not_a_database_diagnosis(self):
        result = summarise({'StandardErrorContent': 'FAILED: deployment not confirmed'})
        self.assertEqual(result['matchedErrorIndicators'], ['host_deployer_reported_failure'])
        self.assertEqual(result['exceptionTypes'], [])

    def test_host_probe_is_read_only_and_targets_failed_release(self):
        compile(read_failed_host.HOST_PROBE, '<fixed host probe>', 'exec')
        self.assertIn("failed=SHA in image", read_failed_host.HOST_PROBE)
        self.assertNotIn("config.get('Env'", read_failed_host.HOST_PROBE)
        commands = []
        def unavailable(args, **kwargs):
            commands.append(args)
            return type('Result', (), {'returncode': 1, 'stdout': '', 'stderr': ''})()
        with patch('subprocess.run', side_effect=unavailable):
            with patch('builtins.print'):
                exec(read_failed_host.HOST_PROBE, {})
        self.assertEqual([x[1] for x in commands], ['ps', 'events'])

    def test_fixed_ssm_document_matches_reviewed_probe(self):
        root = Path(__file__).resolve().parents[2]
        document = json.loads((root / 'infrastructure/aws/ssm-diagnostics-document.json').read_text())
        self.assertNotIn('parameters', document)
        lines = document['mainSteps'][0]['inputs']['runCommand']
        self.assertEqual('\n'.join(lines[1:-1]), read_failed_host.HOST_PROBE.strip())
        policy = json.loads((root / 'infrastructure/aws/github-diagnostics-policy.json').read_text())
        self.assertEqual(policy['Statement'][0]['Action'], 'ssm:SendCommand')
        self.assertTrue(all('*' not in arn for arn in policy['Statement'][0]['Resource']))


if __name__ == '__main__':
    unittest.main()
