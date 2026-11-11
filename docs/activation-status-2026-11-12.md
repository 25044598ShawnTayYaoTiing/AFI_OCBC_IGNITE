# CI/CD activation status ? 12 November 2026

Account `092004973012`, region `ap-southeast-1`, instance `i-0368a57f3e9ccafa7`, host `/opt/afi-resolve`. This is verified activation evidence, not a claim of application deployment or end-to-end success.

## Approval ledger

- Reviewer password rotation: previously approved, still pending application readiness.
- Scoped Phase 2 IAM/SSM additions: approved and completed.
- Reviewed host helper bootstrap and existing-commit image publishing: approved and completed.
- The owner's subsequent Demo-First instruction authorizes routine demo configuration, snapshots, application deployments, Vercel integration and synthetic activation. Do not repeat those approval requests. The existing GitHub production environment review remains required and must not be bypassed. Main has not been merged or pushed by this activation work.

## AWS, GitHub and ECR

- CLI authentication/account/region verified. Existing private ECR repositories reused without recreation; all three have immutable tags and scan-on-push enabled.
- Existing OIDC provider reused with `sts.amazonaws.com` audience. Created and verified the two scoped publishing/deployment roles, EC2 ECR-pull and prefix-scoped S3 cleanup policies, and `AFI-ResolveDeploy` version 1. Existing EC2 role trust, S3 policy and managed SSM permissions were retained.
- GitHub production environment now requires owner approval and allows only main. Public repository/environment role ARN variables and repository prefix were configured. Workflow token defaults remain read-only. Main has no branch protection/ruleset; none was bypassed or silently changed.
- Latest main CI [42996703980](https://github.com/25044598ShawnTayYaoTiing/AFI_OCBC_IGNITE/actions/runs/42996703980), attempt 3, succeeded for source validation and all three image jobs. Full immutable tag: `d8716c0e57dc2c2585708c24c0203a44c2d2046e`.

| Repository | Verified image/index digest |
| --- | --- |
| `afi-resolve/backend` | `sha256:52fe56f55595f5cb5a80dbe8d7b943e414c5d5e4af8271c1215d6693796b25ac` |
| `afi-resolve/frontend` | `sha256:0f84930006e841a2c96960e67a3926b0c4480042a4f6b8bd02074bcb68bb1d12` |
| `afi-resolve/ai-service` | `sha256:26099574990dab722b0bb08476b1dd1ace67b209e6a8419e49ff0c69d7353780` |

The OIDC failure was an exact-subject mismatch: the repository reports `use_immutable_subject=true` and prefix `repo:25044598ShawnTayYaoTiing@281175981/AFI_OCBC_IGNITE@1441632967`. Both trusts now use that exact prefix plus main or production context. Immutable identity was retained, not disabled; no wildcard subject was granted. Successful publishing verifies the main role exchange. The production-environment role is configured but has not yet been exercised by an application deployment.

## SSM and host bootstrap

- SSM is Online. Snap Agent 3.3.4793.0 is enabled/active, meets the document minimum and was not reinstalled or restarted.
- The actual attached profile maps to `afi-resolve-ec2-role`, with `AmazonSSMManagedInstanceCore`. IMDSv2 returns the correct instance/region/role and valid credential availability; no credential values were printed. Current structured Agent warning/error classification is clean. An initial keyword-only scan matched diagnostic payload text and was discarded; it is not evidence of registration failures.
- Read-only SSM inspection works. Docker Compose 5.6.1, Python, AWS CLI, psql and the actual project `afi-resolve` were verified. The running project uses the expected base/AWS/HTTPS files.
- Installed only `scripts/aws/deploy.py`, `scripts/aws/check_public_endpoints.py`, `docker-compose.images.yml` and public `.deploy.json`, with owned helper/state directories. Existing `.env` and Compose contents, Caddy configuration/volumes and running container IDs were preserved.
- Ubuntu cannot access the Docker socket directly but already has passwordless sudo Docker access. The tested compatibility fix reuses that access without changing users, groups, sudo policy or socket permissions. Compose/image-variable propagation and ephemeral ECR login were verified as ubuntu; Docker credentials were not left in the global configuration.
- No application deployment document was executed and no application container was restarted. Host deployment helper SHA256: `44d12fe223c4dc1d40777b7021bead84b11f62949ab190a6cd168a86c5b9cab4`.

## RDS and migration readiness

Read the actual existing RDS migration history through psql with `sslmode=verify-full`, using credentials only in process memory/environment and a read-only database session. No records or schema were changed.

| Migration | Existing RDS state | Selected image checksum |
| --- | --- | ---: |
| V1 operational schema | Applied successfully; checksum matches | `1654438613` |
| V2 document operations | Applied successfully; checksum matches | `-1215672925` |
| V3 document autonomy | Pending | `1531962338` |
| V4 customer portal | Pending | `-831434754` |

Customer, legacy portal-customer and customer-session tables are absent. There is no applied V4 conflict in this RDS database. The backed-up legacy local V4 checksum `1324730588` is not present and is not a proposed migration. Never introduce it into the image or use repair to hide a mismatch.

The actual published backend image was downloaded for read-only inspection without starting it. Its revision label matches the selected SHA; customer classes and exactly the expected V1?V4 migration resources are present. Image migration checksums were computed with Flyway 11.7.2 and match the table above. The old running image also uses Flyway 11.7.2. An isolated H2 audit applied V1?V4, validated using only old V1/V2 resources, and verified the old staff-insert contract still works with V4 defaults. This is schema/Flyway-level rollback evidence, not a live workload rollback test.

V3 creates investigation/automation tables and indexes. V4 adds customer tables and nullable/defaulted links to existing document tables. Existing applied scripts are unchanged. Current backend/frontend/AI image IDs remain on the host for rollback; the deployment script will snapshot those IDs before replacing anything. Rollback retains additive schema and cannot offer new customer features on an old image; do not drop tables or reset data.

Fresh pre-rollout snapshot `afi-resolve-pre-demo-20261112` is Available (100%). The database remains private. A read-only check also confirmed CREATE permission in public and ownership/inherited ownership of the two tables altered by V4. No schema or account records have been changed yet.

## Application root cause and remaining deployment work

- HTTPS and backend health are working. Nginx routes `/api/` to `backend:8080` without stripping the path; runtime origins are correct.
- The running backend JAR contains only V1/V2 and lacks the customer API classes. Direct backend requests reproduce the staff response and CORS rejection. The root cause is the old deployed artifact, not a missing public proxy rewrite. The reviewed backend image supplies the missing implementation.
- The old AI service lacks `/documents/status` and `/documents/investigate`, required by the reviewed document workflow. The current RM bundle also lacks Customer Portal source fields. Those image updates should follow backend health/routing verification if approved; do not rebuild or change infrastructure to work around missing endpoints.
- AWS and Vercel CLI sessions were renewed and verified without printing credentials. The existing Vercel project `sleepy-shawn/ocbc-customer-portal` was reused, its Production `VITE_API_BASE_URL` corrected, and one production redeploy completed: `dpl_GaRMX3x9ioT3gBTUEgiyZ88JAorp`. The public login returns HTTPS 200; deployed asset `index-Cb4t7T5p.js` contains the correct full customer API and no old IP URL. No duplicate project was created.
- The private activation file was transferred over SCP through an SSM port-forward using an ephemeral EIC key and a pinned host key. It is outside the checkout with mode 600; no secret was included in RunCommand parameters. The tunnel was terminated afterwards. The reviewed account Compose override and `.deploy.json` are prepared for the next backend deployment, with only REVIEWER_PASSWORD differing from the running backend configuration. Original `.env` and other staff settings are preserved. No rotation takes effect until the backend is recreated.
- Customer/reviewer logins, a real synthetic upload, physical S3/RDS persistence and RM case review remain unverified. No account or reviewer password was activated, and no PDF was submitted.

## Code review and next gates

The Docker sudo compatibility fix, immutable trust templates and evidence are in [PR #1](https://github.com/25044598ShawnTayYaoTiing/AFI_OCBC_IGNITE/pull/1), on the existing development branch. All six CI checks passed. The tested helper is installed on the host; no main merge is needed to deploy the previously published application images. Local deployment safety tests (including the new fallback) and Compose validation pass; no tests were weakened.

Protected deployment run [43096377053](https://github.com/25044598ShawnTayYaoTiing/AFI_OCBC_IGNITE/actions/runs/43096377053) was dispatched once for the already verified SHA, selecting all three application images because each old artifact lacks a required part of the workflow. It is queued; do not dispatch another run or use a direct host deployment to bypass GitHub review. Caddy, RDS and S3 resources are preserved. After successful rollout, run public readiness, provision the single existing synthetic identity via the existing API, submit one PDF through the browser, and verify the same case in RM plus physical RDS/S3 persistence. These end-to-end checks remain pending.
