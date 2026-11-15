# Demo deployment and debugging

## Push to main

1. Push application changes to `main`.
2. **AFI Resolve CI** tests and publishes the three SHA-tagged ECR images.
3. A successful push CI run triggers **AFI Resolve Deploy** for the same SHA. Failed CI and pull-request CI do not deploy.
4. The existing GitHub OIDC role invokes the existing `AFI-ResolveDeploy` SSM document on `i-0368a57f3e9ccafa7`.
5. Container health and public readiness checks run. A failure captures redacted startup logs **before** restoring the previous images. The failed deployment remains red in Actions even if rollback succeeds.

The environment retains its historical `production` name because the AWS trust policy and variables reference it. Its reviewer approval was removed through the GitHub API for this dedicated synthetic demo. Branch restrictions, OIDC, private RDS/S3, secrets and health checks remain. There are no new custom diagnostic workflows or policies. No general `AWS-RunShellScript` permission is granted to Actions.

Manual deployment and rollback remain available under Actions → AFI Resolve Deploy → Run workflow. Use the full CI-passed image SHA, or `rollback` with services `all`. Deployments are serialised so recovery is not interrupted by a new push. The workflow also requests rollback if a deployment command fails, supporting the older helper until the one-time update below is installed.

## One-time existing-host helper update

The SSM document runs `/opt/afi-resolve/scripts/aws/deploy.py` on EC2. Source pushes update container images but cannot replace that installed file under the existing scoped role. Install the updated helper once through an **authorised human Session Manager session**. Do not give Actions unrestricted shell access to do this.

Open AWS Console → EC2 → `i-0368a57f3e9ccafa7` → Connect → Session Manager. Download `scripts/aws/install_demo_tools.py` from the reviewed full main SHA to `/tmp/afi-install-demo-tools.py`, then run:

```sh
sudo -u ubuntu python3 /tmp/afi-install-demo-tools.py --revision FULL_REVIEWED_MAIN_SHA
```

The installer downloads only `deploy.py`, `log_redaction.py` and `check_public_endpoints.py` from that immutable commit, compiles all three before installation, and backs up old helpers under `.local/tool-backups/`. It changes no service, configuration, IAM permission, secret, RDS record or S3 object. Existing Compose files are preserved. Future application pushes deploy automatically; deployment-helper changes themselves need the same installer to refresh the host copy.

If the authorised human account cannot connect, the smallest access configuration is Session Manager access to **this instance only**: `ssm:StartSession` for that instance and the approved default shell session document, plus data-channel and resume/terminate access for that operator's own sessions. The instance already executes SSM deployments; reuse its Agent, instance role and outbound HTTPS. No public SSH port, public RDS, shared access key or new diagnostic document is required. Existing administrative console access can also perform this one-time install.

## Useful logs

After installation, an authorised Session Manager operator can capture current health and redacted application logs without restarting anything:

```sh
sudo -u ubuntu python3 /opt/afi-resolve/scripts/aws/deploy.py --logs
```

Startup failures automatically save redacted reports under `/opt/afi-resolve/.local/deployment/failure-*.log` with mode 0600. Reports contain Docker exit/OOM state, recent health-check output and Spring/FastAPI/Nginx logs. Known private environment/container secrets and common credential patterns are redacted. Do not publish raw `.env`, Docker inspect/config output or unredacted application logs. Standard Session Manager also permits private inspection with `sudo docker ps -a` and `sudo docker logs --tail 180 CONTAINER_ID` by the authorised operator when deeper investigation is necessary.

Actions now displays the returned redacted SSM stdout/stderr instead of replacing it with a generic failure message. AWS SSM limits returned output, so use the saved host report for the full captured logs. No log archive is uploaded publicly.

## Current release investigation

### Confirmed SMTP health-check defect

With the updated host helper installed, automatic deployment [43722618548](https://github.com/25044598ShawnTayYaoTiing/AFI_OCBC_IGNITE/actions/runs/43722618548) captured a running backend (exit 0, OOMKilled=false) whose health endpoint returned 503. The stack ended in `SMTPTransport.openServer` and `java.net.ConnectException: Connection refused`. FastAPI health checks returned 200. Automatic rollback restored the previous healthy images and retained the redacted report under `.local/deployment/failure-80e0779c215e1baf5269b81a2b8b1e13b6706beb-20261115T141445Z.log`.

A focused Spring regression test reproduced the exact mechanism:

```text
MailHealthIndicator: Mail health check failed
MailConnectException: Couldn't connect to host, port: localhost, 587
Expected /actuator/health 200; received 503
```

Adding the mail starter enabled Spring's automatic mail health contributor even while the application's optional SMTP dispatcher was disabled. An empty configured mail host led its health probe to localhost. This made Compose reject a running application and prevented the frontend dependency from starting. The earlier original failure's logs are unavailable; this reproduced release failure provides the concrete diagnosis.

The minimal fix sets `management.health.mail.enabled=false`. Database/disk health checks remain active; email sending is unchanged and actual transport outcomes remain in the durable outbox/audit. The focused readiness test failed before this change and passed afterward; both existing SMTP transport tests also passed. New-release EC2 health and end-to-end verification must still be checked after CI deploys the fix.

### Earlier investigation history

The original failure's retained SSM output contains only exit 1 and the old helper's generic error. A read-only host-log attempt was denied by IAM. No evidence establishes a Flyway, seeder, SMTP, risk-engine or eForm defect. The healthy previous release remains the recovery target.

If rollback removed the failed containers, their original local logs may be gone. Install the new logging helper, then perform a controlled deployment: it will preserve the next failed startup evidence before automatic rollback. Diagnose that captured error before patching application code. The new helper, auto-deployment and complete new-release end-to-end workflow must be verified on EC2 separately from local tests.
