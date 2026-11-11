# AWS integration and CI/CD activation

All files are prepared locally; this is not a record of deployment. Only the existing reviewer password rotation has been approved. IAM/OIDC/ECR/SSM setup, host bootstrap, backend/CORS changes and Vercel deployment below require approval before execution. No new EC2 instance, load balancer, database, bucket or production runner is needed. Keep the existing region `ap-southeast-1`, instance `i-0368a57f3e9ccafa7`, application directory `/opt/afi-resolve` and private configuration.

## Observed state and remaining blockers

Read-only AWS/public checks on 11 November 2026 confirmed:

- Instance running, x86_64, public IP `56.10.140.166`; trusted HTTPS and backend health work.
- Existing RDS `afi-resolve-db` is private PostgreSQL; its endpoint matches the AWS example. Existing bucket `afi-resolve-documents-uniqueid` exists. This does not prove the running backend uses those targets.
- An empty JSON request to `/api/customer/auth/token` returns staff `UNAUTHENTICATED`, not the customer API's `INVALID_CREDENTIALS`. The protected `/api/customer/me` returning 401 is expected and, on its own, is not evidence of a routing failure.
- Preflight from `https://ocbc-customer-portal.vercel.app` returns 403. The deployed portal bundle still contains the old bare-IP URL without `/api/customer`.
- No ECR repositories or GitHub OIDC provider exist in this AWS account. The instance is not registered with Systems Manager. Its role has only S3 permissions; no SSM/ECR policy is attached.
- EC2 Instance Connect accepted temporary authentication, but direct SSH timed out from this workstation. No Instance Connect Endpoint exists. Do not widen SSH ingress automatically. The owner uses browser EC2 Instance Connect.
- Current IAM grants S3 ListBucket/GetObject/PutObject but not DeleteObject required for upload rollback cleanup. `s3-cleanup-policy.json` is the narrow additive correction for the current `documents/` prefix; it has not been applied.

The owner's four healthy containers and correct runtime origins are supplied observations. Container image IDs, active proxy configuration and Flyway history have not been independently inspected because shell access is unavailable. Do not replace infrastructure based on the public checks.

## Code and workflow design

`SecurityConfig` explicitly supplies the existing exact-origin CORS source to both security chains. The higher-priority customer chain and existing customer APIs are retained; staff Basic auth, customer Bearer auth, permissions and migrations are unchanged. The AWS profile defaults to the exact Vercel origin. Regression coverage checks the customer token route, preflight headers and denial of untrusted origins. Nginx `/api/` already preserves the path, so no proxy rewrite or wildcard CORS is added.

CI runs on PRs and pushes to main. It packages the backend with two focused customer/security workflow tests, builds the RM frontend, checks essential AI health/authentication/extraction behavior, validates Compose, and runs deployment/account-helper safety tests. All three Docker images build for the instance's `linux/amd64` architecture. Main pushes publish full-SHA tags only after validation, once the publish role variable is configured. PRs cannot assume the main-only AWS role. Actions are pinned to verified upstream commit SHAs. The prior broad CI suite is replaced by these explicitly selected checks to keep the deployment gate small; full existing suites remain available for deeper work.

ECR repositories use immutable tags. Re-running a successful main build reuses an existing SHA tag rather than overwriting it. Main CI fails if the publish role/repositories are unconfigured; configure the approved AWS prerequisites and variable before expecting a green main publishing run. PRs build without publishing. Deploying also fails if a selected image is absent. No `latest` tag is used.

`AFI Resolve Deploy` is workflow-dispatch only, main-only, serialised and protected by GitHub environment `production`. It checks that the selected commit has successful main CI and published images. It never builds on EC2 or checks out application code during deployment. SSM is preferred. A short-lived EC2 Instance Connect SSH fallback is implemented with strict pinned host-key verification; it requires an already-approved reachable SSH path from the chosen runner. Neither transport opens ports, installs agents, modifies IAM, sends application credentials or changes secrets.

The host `scripts/aws/deploy.py` uses the existing Compose files/environment files and adds only image selection. It snapshots current image IDs, pulls only selected application services, and restarts changed selections with `--no-deps --no-build`. It reloads Nginx after backend/AI replacement to refresh upstream IP resolution. It never starts PostgreSQL, recreates Caddy or deletes volumes. Failed changes retain their previous image snapshot and block another forward deployment until reviewed/rolled back. No automatic rollback hides a migration failure.

## GitHub configuration

In repository Settings -> Environments, create `production`, permit only main, and configure a required trusted reviewer for deployment approval. Protect main and workflow/deployment files with the repository's review rules. Do not run fork PR code on production or add a self-hosted runner to this instance.

Repository variables (not secrets):

| Variable | Value |
| --- | --- |
| `AWS_ECR_PUBLISH_ROLE_ARN` | `arn:aws:iam::092004973012:role/afi-resolve-github-publish` |
| `ECR_REPOSITORY_PREFIX` | `afi-resolve` |

Variables scoped to the protected `production` environment:

| Variable | Value |
| --- | --- |
| `AWS_DEPLOY_ROLE_ARN` | `arn:aws:iam::092004973012:role/afi-resolve-github-deploy` |
| `DEPLOY_RUNNER` | Optional trusted GitHub-hosted runner label; defaults to `ubuntu-latest` |
| `EC2_SSH_HOST_KEY` | EIC only: verified single-line public host key, obtained in the existing Console session with `cat /etc/ssh/ssh_host_ed25519_key.pub` |

No AWS access key, database password, staff/customer password, Gemini key or SSH private key belongs in GitHub. The normal `GITHUB_TOKEN` is supplied automatically with read-only source/actions permissions. OIDC grants temporary AWS credentials. The host retains its private `.env`, certificates and optional synthetic credential file.

## AWS IAM/OIDC/ECR setup, after approval

In IAM Console -> Identity providers -> Add provider, choose OpenID Connect, URL `https://token.actions.githubusercontent.com`, audience `sts.amazonaws.com`. Verify the account ID is `092004973012` before using these scoped templates. Publish trust allows only this repository's main ref. Deploy trust allows only this repository's `production` environment; GitHub environment branch restrictions must also be enabled. Do not reuse the EC2 instance role as the GitHub role or replace its existing trust/policy.

From an authorised administration shell at the reviewed repository root, only after approval:

```bash
for service in backend frontend ai-service; do
  aws ecr create-repository --region ap-southeast-1 \
    --repository-name "afi-resolve/$service" --image-tag-mutability IMMUTABLE \
    --image-scanning-configuration scanOnPush=true
done
aws iam create-role --role-name afi-resolve-github-publish \
  --assume-role-policy-document file://infrastructure/aws/github-publish-trust.json
aws iam put-role-policy --role-name afi-resolve-github-publish --policy-name afi-resolve-ecr-publish \
  --policy-document file://infrastructure/aws/ecr-publish-policy.json
aws iam create-role --role-name afi-resolve-github-deploy \
  --assume-role-policy-document file://infrastructure/aws/github-deploy-trust.json
aws iam put-role-policy --role-name afi-resolve-github-deploy --policy-name afi-resolve-deploy \
  --policy-document file://infrastructure/aws/github-deploy-policy.json
aws iam put-role-policy --role-name afi-resolve-ec2-role --policy-name afi-resolve-ecr-pull \
  --policy-document file://infrastructure/aws/ecr-pull-policy.json
# Separate approved S3 cleanup permission; retain the existing S3 policy.
aws iam put-role-policy --role-name afi-resolve-ec2-role --policy-name afi-resolve-s3-cleanup \
  --policy-document file://infrastructure/aws/s3-cleanup-policy.json
```

These are initial-creation commands. If a resource already exists by setup time, inspect/reuse it rather than recreating it. Do not configure ECR lifecycle pruning that deletes release tags needed for rollback. `GetAuthorizationToken` and selected AWS describe/status operations require wildcard resources; image read/write permissions are scoped to the three repositories, and deployment SendCommand is limited to the named document and existing instance. Publish cannot deploy; deploy cannot publish images, change IAM or edit the SSM document.

## Transport activation

SSM is currently unavailable. In the existing browser Instance Connect session, first inspect Agent installation and connectivity:

```bash
snap services amazon-ssm-agent
systemctl status amazon-ssm-agent --no-pager
```

One of those service layouts may be absent. If the Agent is present, do not install a second copy. After approval, add the existing role's SSM permissions, start/update its existing Agent and ensure outbound HTTPS reaches regional SSM/ssmmessages endpoints. Keep RDS private and SSH rules unchanged. Agent version 3.3.2746.0 or newer is required for the document's environment-variable parameter interpolation. If absent, an administrator may install the official Agent after approval; see the AWS instructions linked below.

```bash
aws iam attach-role-policy --role-name afi-resolve-ec2-role \
  --policy-arn arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore
aws ssm describe-instance-information --region ap-southeast-1 \
  --filters Key=InstanceIds,Values=i-0368a57f3e9ccafa7 \
  --query 'InstanceInformationList[].{Status:PingStatus,Version:AgentVersion}'
aws ssm create-document --region ap-southeast-1 --name AFI-ResolveDeploy --document-type Command \
  --document-format JSON --content file://infrastructure/aws/ssm-deploy-document.json
```

Wait for `Online`; verify the Agent version and host bootstrap before dispatch. The workflow uses document version 1. Any future document/script change needs an independent review; the GitHub deployment role cannot update it.

If SSM cannot be enabled, EIC can be used only if an approved runner network already reaches the existing SSH rule and the pinned host key is configured. Standard GitHub-hosted runner IPs are not stable; do not solve this by opening SSH to the internet. A trusted GitHub-hosted larger runner with approved static egress can be selected using `DEPLOY_RUNNER`, if already available. EIC is currently not reachable from this workstation and is not claimed operational. Its optional role permission is:

```bash
aws iam put-role-policy --role-name afi-resolve-github-deploy --policy-name afi-resolve-eic \
  --policy-document file://infrastructure/aws/github-eic-policy.json
```

Until either transport is approved and working, use the existing browser Console with the same host deployment command below. This is a manual fallback, not functioning continuous deployment. No additional AWS service or runner is created automatically.

## Host bootstrap in /opt/afi-resolve, after approval

Use the existing browser Instance Connect session. Deliver only the reviewed source/configuration files through the approved source process; do not overwrite `.env` or change running Compose files blindly. Confirm `docker compose ls` and container labels show the actual project name and Compose file list. Preserve that name to keep Caddy certificate volumes attached. Do not clone another checkout and start another Compose project. If the active deployment differs from the prepared files, reconcile it before proceeding.

```bash
cd /opt/afi-resolve
docker compose ls
docker ps --format 'table {{.Names}}\t{{.Image}}\t{{.Status}}'
docker compose version
aws --version
python3 --version
test ! -e .deploy.json && cp infrastructure/aws/deployment.example.json .deploy.json
chmod 600 .deploy.json
nano .deploy.json
```

Compose must support `!override` (2.24.4+). The deployment command runs as ubuntu; confirm ubuntu already has Docker access. If AWS CLI v2/Python/Docker access is missing, an administrator must install/configure it after approval; reuse existing installations. The AWS CLI on EC2 must use the instance role, without static key files. `.deploy.json` contains public registry/configuration paths only. Keep its `env_files` and `compose_files` exactly aligned with the currently approved host configuration. The reviewed deployment script/config should not be editable by unrelated users.

After the approved reviewer rotation, retain `docker-compose.test-accounts.yml` in `.deploy.json`'s `compose_files`, and add the private `/home/ubuntu/afi-resolve-test-credentials.env` after `.env` in `env_files`. Otherwise a later backend deployment would restore the prior reviewer password. No password value is put in this JSON. Before activation keep that override absent. Do not enable the `local-database-only` profile.

The host deployment does not update source, config, secrets or certificates when selecting an image SHA. Changes to host-side deployment scripts or Compose configuration require separate reviewed delivery. `.local/deployment` holds image snapshots and pending status; retain it and the previous local Docker image IDs. Do not run Docker image pruning, Compose down, or volume deletion during deployment/rollback.

## First deployment, trigger and rollback

Configure the approved publish prerequisites, then publish/merge the reviewed files through the normal GitHub process and wait for main CI to build/publish all three SHA tags. Pushing source starts CI, not the manual deployment workflow. Main publishing intentionally fails before AWS setup; the deployment workflow additionally verifies the selected tags exist.

Before a backend version change, privately review `flyway_schema_history` in the existing RDS database and take an RDS backup. In particular, resolve any version-4 migration mismatch through a reviewed plan. Do not run Flyway repair, edit applied migrations or recreate the database. Container rollback is safe only when the schema remains compatible with the previous backend; it never reverts database migrations.

In the authorised private database session, the migration review query is:

```sql
SELECT installed_rank, version, description, script, checksum, success
FROM flyway_schema_history ORDER BY installed_rank;
```

For an approved pre-deployment backup, use RDS Console -> Databases -> `afi-resolve-db` -> Actions -> Take snapshot, and wait for it to become Available. A snapshot does not roll schema changes back automatically.

After deployment approval, Actions -> AFI Resolve Deploy -> Run workflow -> main -> paste the full successful commit SHA, choose service(s) and `ssm` (or the approved EIC path). With GitHub CLI, equivalent commands are:

```bash
gh workflow run deploy.yml --ref main -f image_sha=FULL_40_CHARACTER_SHA -f services=all -f transport=ssm
# Previous local image snapshot, only after schema compatibility review:
gh workflow run deploy.yml --ref main -f image_sha=rollback -f services=all -f transport=ssm
```

Until the transport is ready, equivalent approved Console commands on the instance are:

```bash
cd /opt/afi-resolve
python3 scripts/aws/deploy.py FULL_40_CHARACTER_SHA --services all
python3 scripts/aws/check_public_endpoints.py
# Only if rollback has been reviewed and is necessary:
python3 scripts/aws/deploy.py rollback
```

Only an existing image is deployed; no host builds or resource creation occur. A forward deployment must pass container health and public HTTPS/customer routing/CORS checks. A rollback checks restored container health and public backend health, without assuming an older version supports the new customer APIs. Failed deployments keep `.local/deployment/pending.json` and `previous.json`; inspect privately and perform a compatible rollback before retrying forward. Do not delete pending state to hide a partial change. First rollout snapshots even the pre-ECR local images by image ID, so retain those Docker layers. Application rollback does not remove synthetic records or revert a reviewer secret rotation.

## Synthetic accounts and end-to-end qualification

Fix and verify deployment/routing/CORS before accounts are activated. `python3 scripts/aws/check_public_endpoints.py` distinguishes an expected protected-endpoint 401 from an unavailable customer token route. Explicitly wired Spring CORS preserves authentication and rejects untrusted origins; do not add Nginx wildcard headers or bypass staff authorization.

Use [synthetic-test-accounts.md](../../docs/synthetic-test-accounts.md) for exact private-file handling, approved reviewer rotation, one-customer provisioning, login/role checks and the single-PDF submission command. Only the current reviewer password rotation is approved; do not treat that as blanket permission for other production setup or deployment. No credentials have been sent to the live application by this work. Duplicate customer usernames or `SYN-ALEX-001` references cause verification/stop, never replacement.

Roles are existing Spring authorities: `operator` has OPERATOR (RM operations); `reviewer` and `rules-admin` have OPERATOR plus REVIEWER. There is no separate RM role or RDS staff account table. Customer accounts use the existing BCrypt-backed `customers` table and customer Bearer sessions. The generated RM username is the existing reviewer, not a new staff record.

Vercel Production must set `VITE_API_BASE_URL=https://afi-ocbcignite.duckdns.org/api/customer` and rebuild the reviewed portal. This complete prefix must appear once; the portal appends `/auth/token`, `/submissions`, etc. An existing Vercel variable overrides the checked-in public `.env.production`. EC2 keeps `AWS_FRONTEND_ORIGIN=https://afi-ocbcignite.duckdns.org` and `AWS_CUSTOMER_PORTAL_ORIGINS=https://ocbc-customer-portal.vercel.app`. Application secrets remain private. Vercel redeployment requires approval.

After approved activation, run the helper's `--verify --submit-fixture` once and record its case ID. Verify actual portal login, dashboard login and the same case in the dashboard UI. Open its documents/findings with the reviewer account; this uses existing RM review functionality. API role checks alone do not verify a browser flow.

With authorised private RDS access, inspect only that synthetic case. For example, in a private `psql` session connected to the existing RDS endpoint with `sslmode=verify-full` and its trusted CA (prompt for the password; never place it on a command line):

```sql
\set case_id 'DOC-RETURNED-SYNTHETIC-CASE-ID'
SELECT s.id, s.customer_reference, s.submission_source, s.owner_name,
       d.id AS document_id, v.id AS version_id, v.storage_key, v.size_bytes, v.sha256
FROM document_submissions s
JOIN submission_documents d ON d.submission_id=s.id
JOIN document_versions v ON v.document_id=d.id
WHERE s.id=:'case_id';
```

For each returned immutable storage key, use the existing S3 prefix (currently `documents/`) and an authorised read identity:

```bash
aws s3api head-object --region ap-southeast-1 --bucket afi-resolve-documents-uniqueid \
  --key documents/RETURNED_STORAGE_KEY \
  --query '{Bytes:ContentLength,Encryption:ServerSideEncryption}'
```

Confirm size/encryption and retrieve the PDF through the existing authorised RM preview. Do not make the bucket public or create presigned links for testing. Record only the synthetic case ID, observed state and nonsecret evidence; do not publish credentials, tokens or document contents. A successful API upload/preview is useful evidence but is not, by itself, proof that the physical targets are the intended RDS instance and S3 bucket.

## Operational status

Code/configuration is prepared; live Phase 6 and CI/CD are not yet operationally qualified. Publishing, OIDC roles, ECR repositories, managed-instance registration/SSH reachability, host bootstrap, migrations review, backend deployment, Vercel rebuild and authenticated workflow evidence remain approval/setup dependent. The final delivery message lists checks actually executed locally; tests that simulate Docker do not prove a live rollout.

Primary references: [ECR push permissions](https://docs.aws.amazon.com/AmazonECR/latest/userguide/image-push-iam.html), [GitHub OIDC in AWS](https://docs.github.com/en/actions/how-tos/secure-your-work/security-harden-deployments/oidc-in-aws), [SSM Agent on Ubuntu](https://docs.aws.amazon.com/systems-manager/latest/userguide/agent-install-ubuntu.html), [SSM document parameter security](https://docs.aws.amazon.com/systems-manager/latest/userguide/documents-creating-content.html), [EC2 Instance Connect IAM](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/ec2-instance-connect-configure-IAM-role.html).
