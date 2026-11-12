# CI/CD activation status ? 12 November 2026

Account `092004973012`, region `ap-southeast-1`, instance `i-0368a57f3e9ccafa7`, host `/opt/afi-resolve`. The dated preparation details below are historical; the current live result follows.

The owner approved manual SSM deployment of commit `d8716c0e57dc2c2585708c24c0203a44c2d2046e`: backend first, then AI service and RM frontend. All three containers are healthy; Caddy remains running. Flyway V1–V4 succeeded with the reviewed checksums, and HTTPS, customer routing and exact-origin CORS checks pass. The previously approved reviewer password rotation and one synthetic customer account are active.

The synthetic customer submitted one PDF through the portal. Case `DOC-5ab7b97f-1134-46d3-a179-37fcd46a5df6` is visible to the reviewer in the RM Dashboard, including its PDF preview. Read-only checks confirmed the linked RDS records, BCrypt customer password hash, an encrypted S3 object and a matching document hash. Extraction used deterministic fallback. The case is `CORRECTION_REQUIRED`, expected for the intentionally incomplete one-document package. The GitHub protected deployment workflow encountered `startup_failure`; the owner explicitly approved the manual SSM path. No credential values are recorded here.

The deployed Gemini credential was subsequently found to be JWT-shaped and rejected by the Gemini Developer API. The owner's existing private project key authenticated successfully; the AI service alone was recreated with that key and `gemini-3.1-flash-lite` after the former configured model returned HTTP 404 for that credential. Fresh synthetic case `DOC-4ce9b6a7-c9b8-4f8e-a431-9e0319701ab1` records `GEMINI` for both extraction and investigation, with six extraction fields and two investigation citations verified against the supplied evidence. Its encrypted S3 PDF hash matches RDS. The existing synthetic account passwords were also rotated privately, and the reviewer-only request limit and customer lockout remain in force; see [synthetic account rotation](synthetic-test-accounts.md).

The host deployment configuration was later simplified without changing passwords or recreating containers. The EC2 private `.env` is the authoritative deployment source for the reviewer password and Gemini settings; the former test-account Compose override and extra test-credentials environment-file reference were removed after the resulting Compose configuration was verified identical. The separate private test file remains a copy for verification and rotation. Existing customer records and sessions were not altered by this configuration change.

## Approval ledger

- Reviewer password rotation: approved and activated through the existing Compose override.
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
- Customer/reviewer logins, one synthetic upload, physical S3/RDS persistence and RM case visibility and PDF preview have since been verified as described above.

## Code review and next gates

The Docker sudo compatibility fix, immutable trust templates and evidence are in [PR #1](https://github.com/25044598ShawnTayYaoTiing/AFI_OCBC_IGNITE/pull/1), on the existing development branch. All six CI checks passed. The tested helper is installed on the host; no main merge is needed to deploy the previously published application images. Local deployment safety tests (including the new fallback) and Compose validation pass; no tests were weakened.

Protected deployment run [43096377053](https://github.com/25044598ShawnTayYaoTiing/AFI_OCBC_IGNITE/actions/runs/43096377053) failed with a GitHub startup error before a job ran. Following the owner's explicit approval, the reviewed SHA was deployed manually via the existing SSM document. The end-to-end checks above succeeded.

The identical deployment workflow later initialized in [run 43162746121](https://github.com/25044598ShawnTayYaoTiing/AFI_OCBC_IGNITE/actions/runs/43162746121), received its required production approval, authenticated via GitHub OIDC, and dispatched SSM command `c84f06ef-1b3e-485a-8a1f-5f3f3925acf7` successfully. The active image SHA was selected; the script reported a no-op, and all four application/edge container IDs were unchanged. HTTPS, health, routing and CORS checks passed. The failure appears to have been transient on GitHub's side, but its precise cause was not disclosed. The required review setting remains in place.
