import unittest
from log_redaction import Redactor


class RedactionTest(unittest.TestCase):
    def test_known_secrets_and_generic_credentials_are_removed_but_errors_survive(self):
        redact = Redactor({'DB_PASSWORD': 'private fixture secret', 'SMTP_PASSWORD': 'smtp-fixture'})
        result = redact('PSQLException: password=not-known jdbc:postgresql://user:pass@db/afi private fixture secret smtp-fixture Authorization: Bearer abc123 user@example.com')
        for secret in ['not-known', 'user:pass', 'private fixture secret', 'smtp-fixture', 'abc123', 'user@example.com']:
            self.assertNotIn(secret, result)
        self.assertIn('PSQLException', result)

    def test_container_secrets_can_be_added_before_log_capture(self):
        redact = Redactor({})
        redact.add({'GEMINI_API_KEY': 'provider-private-fixture'})
        redact.add({'AWS_AI_SERVICE_KEY': 'internal-ai-private-fixture'})
        self.assertEqual(redact('Error: provider-private-fixture'), 'Error: [REDACTED]')
        self.assertEqual(redact('Error: internal-ai-private-fixture'), 'Error: [REDACTED]')
