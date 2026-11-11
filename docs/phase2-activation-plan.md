# Phase 2 approval plan

Discovery on 12 November 2026, Asia/Singapore, account `092004973012`, region `ap-southeast-1`.

The owner approved this scoped plan, and the listed IAM roles/policies and SSM document have been created and verified. SSM subsequently registered Online at Agent version 3.3.4793.0 without reinstallation. Role trusts were corrected to the repository's verified immutable OIDC prefix (owner/repository IDs), retaining exact main/production restrictions; no wildcard trust was added. The discovery and requested scope below are retained as the approval record. See [the current activation report](activation-status-2026-11-12.md) for outcomes and later approvals.

## Reuse without modification

- The three private `afi-resolve/backend`, `afi-resolve/frontend`, `afi-resolve/ai-service` repositories exist with immutable tags and scan-on-push enabled.
- The GitHub OIDC provider exists with audience `sts.amazonaws.com`.
- Existing instance `i-0368a57f3e9ccafa7` retains instance profile/role `afi-resolve-ec2-role` and valid EC2 trust. Its `AmazonSSMManagedInstanceCore` policy is already attached.
- The owner confirms that the Snap SSM Agent is installed and active. No reinstall is proposed. The node is not currently visible in SSM; installed version and Agent connectivity cannot yet be checked remotely.
- Preserve the existing inline S3 policy, application configuration, database, bucket, HTTPS, security groups and all other resources.

## Requested changes, only after explicit Phase 2 approval

1. Create `afi-resolve-github-publish` with `github-publish-trust.json` and `ecr-publish-policy.json`. Trust only this repository's main OIDC subject; image permissions only for its three ECR repositories, plus required authorization-token access.
2. Create `afi-resolve-github-deploy` with `github-deploy-trust.json` and `github-deploy-policy.json`. Trust only this repository's protected production environment; SendCommand only to `AFI-ResolveDeploy` on the existing EC2 instance. Do not grant generic RunShellScript, IAM modification or ECR publishing rights.
3. Add `afi-resolve-ecr-pull` to the existing EC2 role using `ecr-pull-policy.json`. Retain its existing S3 and managed SSM permissions.
4. Add `afi-resolve-s3-cleanup` using `s3-cleanup-policy.json`: only `s3:DeleteObject` on `afi-resolve-documents-uniqueid/documents/*`, needed for the existing failed-upload rollback cleanup. No bucket-wide deletion, version deletion or bucket administration is granted.
5. Create the missing `AFI-ResolveDeploy` SSM Command document from `ssm-deploy-document.json`, using restricted version/service parameters and ubuntu execution. Creating the document does not execute it.
6. Verify the resulting role policies/trust and document; recheck SSM Online status. No SSM Agent reinstall, host/application restart, network change or deployment is included in this approval.

The reviewed JSON templates are under `infrastructure/aws/`. If a named resource appears before execution, compare/reuse it; do not overwrite unrelated policies or trust. The EIC fallback policy is not required for the preferred SSM path and is excluded.

## Separate later approvals

Host bootstrap/delivery, production service restarts or image deployments, database snapshot or schema changes, Vercel production changes, new customer-account activation, and push/merge to main remain separately gated. Reviewer password rotation remains approved, subject to its readiness prerequisites. GitHub environment/variable setup is part of the requested GitHub configuration and does not dispatch a deployment.

## SSM diagnosis if it remains unregistered

No SSH opening is proposed. In the existing browser Instance Connect session, read-only inspection should include the Snap Agent version/status and a redacted error summary from its logs, plus connectivity to regional SSM and ssmmessages HTTPS endpoints. Do not paste complete Agent logs or environments, which may contain sensitive metadata. Record only the version, HTTP/connectivity result and error category. Registration/version cannot be claimed verified until the node is Online or this evidence is supplied.
