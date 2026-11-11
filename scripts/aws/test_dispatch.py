"""Check dispatch fails closed; these tests never contact AWS."""
import os
import unittest
from unittest.mock import patch

import dispatch_deployment


class DispatchSafetyTest(unittest.TestCase):
    def environment(self, version):
        return {'RELEASE_SHA': version, 'SELECTED_SERVICES': 'backend', 'DEPLOY_TRANSPORT': 'ssm',
                'EC2_INSTANCE_ID': 'i-0368a57f3e9ccafa7', 'ECR_REPOSITORY_PREFIX': 'afi-resolve'}

    def test_unvalidated_release_cannot_contact_aws(self):
        with patch.dict(os.environ, self.environment('main; invalid'), clear=True), \
                patch.object(dispatch_deployment, 'aws') as aws:
            with self.assertRaises(RuntimeError):
                dispatch_deployment.dispatch()
            aws.assert_not_called()

    def test_unregistered_ssm_node_cannot_receive_a_deployment_command(self):
        def response(*args):
            if args[:2] == ('ecr', 'describe-images'):
                return {'imageDetails': [{'imageDigest': 'unit-test-only'}]}
            if args[:2] == ('ssm', 'describe-instance-information'):
                return {'InstanceInformationList': []}
            self.fail('Offline SSM node must never receive a command.')

        with patch.dict(os.environ, self.environment('a' * 40), clear=True), \
                patch.object(dispatch_deployment, 'aws', side_effect=response) as aws:
            with self.assertRaises(RuntimeError):
                dispatch_deployment.dispatch()
            self.assertFalse(any(call.args[:2] == ('ssm', 'send-command') for call in aws.call_args_list))


if __name__ == '__main__':
    unittest.main()
