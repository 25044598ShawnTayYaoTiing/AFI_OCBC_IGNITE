# Failed release startup: evidence and access blocker

## Confirmed evidence

The failed release was `a576e58f8dcfef416037f74e6f090bebda122dfb`. Its [deployment run](https://github.com/25044598ShawnTayYaoTiing/AFI_OCBC_IGNITE/actions/runs/43614949573) failed on EC2. The [rollback run](https://github.com/25044598ShawnTayYaoTiing/AFI_OCBC_IGNITE/actions/runs/43617117199) succeeded.

The approved [read-only SSM result inspection](https://github.com/25044598ShawnTayYaoTiing/AFI_OCBC_IGNITE/actions/runs/43719615176) retrieved the original failed command, rather than inspecting the rolled-back application's logs:

```text
Command: 9facf8e8-8b3e-4e67-b715-0db749c21392
Plugin: deployReviewedImages
Status: Failed
Exit code: 1
Stdout: 0 characters
Stderr: 181 characters
Sanitised indicator: FAILED: deployment not confirmed
No exception type or SQL state was present in the returned output.
```

The host deployment helper deliberately replaces command errors with a generic error. This is a confirmed diagnostic limitation, **not an established cause of the startup failure**. No evidence currently establishes that SMTP, Flyway, RDS, the seeder, risk rules or eForms caused it. Patching one speculatively would be unsafe.

After rollback, real HTTPS checks returned backend health `200 / UP` and staff authentication `200`. All nine pre-deployment baseline cases remained accessible. The AI status endpoint reported configured=true, liveStatus=SUCCEEDING and no last failure. These qualify the restored release only; they do not qualify the new release or constitute fresh S3/Gemini document processing tests.

## Exact access denial

The approved [host diagnostic probe](https://github.com/25044598ShawnTayYaoTiing/AFI_OCBC_IGNITE/actions/runs/43719859894) attempted a fixed read-only Docker inspection and failed before any host script executed:

```text
AccessDeniedException
Action: ssm:SendCommand
Document: arn:aws:ssm:ap-southeast-1::document/AWS-RunShellScript
Target: arn:aws:ec2:ap-southeast-1:092004973012:instance/i-0368a57f3e9ccafa7
Role: arn:aws:iam::092004973012:role/afi-resolve-github-deploy
```

The existing role can read SSM invocation results and invoke the deployment document. It cannot invoke the attempted diagnostic document. No IAM permissions, network settings, credentials, services or database records were changed by these probes.

## Narrow access needed

Prefer a fixed diagnostic document over granting general `AWS-RunShellScript` access:

1. An existing AWS administrator reviews and creates **version 1** of `AFI-ResolveDiagnostics` in account `092004973012`, region `ap-southeast-1`, using [ssm-diagnostics-document.json](../infrastructure/aws/ssm-diagnostics-document.json).
2. Attach the additional statement in [github-diagnostics-policy.json](../infrastructure/aws/github-diagnostics-policy.json) to `afi-resolve-github-deploy`. It permits `ssm:SendCommand` only on this document and this EC2 instance. Keep existing `ssm:GetCommandInvocation` access. No broad shell, IAM-management, database-reset or credential-export permission is requested.
3. Run **AFI Read Failed Deployment** with `read_host_logs=true` and approve its existing production environment gate. The helper now invokes only `AFI-ResolveDiagnostics` version 1.

The fixed script accepts no parameters. It reads Docker container states, log-driver names, failure-window events and selected startup-error indicators only for containers whose image contains the failed SHA. It never prints raw application logs, environment values or output URLs, and never restarts, deletes or deploys anything. These are reviewable templates, **not resources already installed in AWS**.

An administrator can alternatively run the reviewed fixed script through an existing authorised EC2 terminal and provide its sanitised JSON result. If rollback removed the failed containers and their logs, the evidence may no longer exist locally. Docker events are also bounded; their absence does not prove a successful startup. CloudWatch/central log access has not been established. Further controlled reproduction must capture failure diagnostics before another rollback, while retaining the known healthy images.

## Changes and verification

Added a protected, read-only diagnostics workflow and sanitising helpers. Deployment-tool tests cover secret suppression, generic-error interpretation, fixed probe execution and consistency of the parameterless SSM document with its scoped IAM template. No application fix or new release deployment has been claimed. Root-cause remediation and real RDS/S3/Gemini/customer-correction qualification remain blocked on host evidence.
