import json
import unittest

from configure_smtp import smtp_overlay, non_smtp_changes, masked, validate


class SmtpConfigurationSafetyTest(unittest.TestCase):
    def fixture(self):
        return {'SMTP_ENABLED':'true', 'SMTP_HOST':'smtp-relay.brevo.com', 'SMTP_PORT':'587',
                'SMTP_USERNAME':'fixture-user', 'SMTP_PASSWORD':'fixture-password',
                'SMTP_FROM':'fixture@example.invalid', 'SMTP_STARTTLS':'true'}

    def test_persistent_overlay_has_only_backend_references_and_no_secret_values(self):
        value=smtp_overlay()
        self.assertEqual(list(value['services']), ['backend'])
        self.assertTrue(all(v.startswith('${') for v in value['services']['backend']['environment'].values()))
        self.assertNotIn('fixture-password', json.dumps(value))

    def test_database_or_other_configuration_change_is_detected(self):
        self.assertEqual(non_smtp_changes({'DB_PASSWORD':'old','SMTP_ENABLED':'false'},
                                         {'DB_PASSWORD':'new','SMTP_ENABLED':'true'}), ['DB_PASSWORD'])
        self.assertEqual(non_smtp_changes({'DB_PASSWORD':'old','SMTP_ENABLED':'false'},
                                         {'DB_PASSWORD':'old','SMTP_ENABLED':'true'}), [])

    def test_requires_enabled_brevo_starttls_and_sender_without_printing_credentials(self):
        good=self.fixture();validate(good)
        self.assertNotIn('fixture-password',json.dumps(masked(good)))
        for key,value in [('SMTP_ENABLED','false'),('SMTP_FROM',''),('SMTP_PASSWORD',''),
                          ('SMTP_HOST','unrelated.example'),('SMTP_STARTTLS','false')]:
            bad={**good,key:value}
            with self.assertRaises(ValueError):validate(bad)


if __name__ == '__main__':
    unittest.main()
