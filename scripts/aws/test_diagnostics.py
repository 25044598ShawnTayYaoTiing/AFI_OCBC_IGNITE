import unittest

from read_deployment_diagnostics import summarise


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


if __name__ == '__main__':
    unittest.main()
