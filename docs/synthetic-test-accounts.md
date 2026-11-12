# Synthetic test account activation

> **Current demo configuration:** The existing `reviewer` and the previously provisioned synthetic customer are active. `/opt/afi-resolve/.env` supplies the effective `AWS_REVIEWER_PASSWORD`, `GEMINI_API_KEY` and `GEMINI_MODEL` to deployments. The separate private test-credentials file retains a copy for account verification and future rotation; it is not a Compose environment source. The customer remains linked to its existing reference, RM and cases. The reviewer is accessible from any IP, as requested; a reviewer-only per-client request limit is active, and the existing five-attempt customer lockout is unchanged.

To rotate both accounts after the demo, use the existing EC2 session. Edit the two password variables only in a private file; do not place values in commands or source:

```bash
cd /opt/afi-resolve
cp ~/afi-resolve-test-credentials.env ~/afi-demo-credentials.pending.env
chmod 600 ~/afi-demo-credentials.pending.env
nano ~/afi-demo-credentials.pending.env
sudo python3 -W ignore::DeprecationWarning scripts/demo/rotate_synthetic_passwords.py
```

Set `AFI_TEST_RM_PASSWORD` and `AFI_TEST_CUSTOMER_PASSWORD` in the editor. Keep the usernames unchanged; use passwords without a single quote or newline. The helper checks the exact synthetic customer/reference/RM, updates the authoritative `AWS_REVIEWER_PASSWORD` in the private `.env`, uses BCrypt, revokes that customer's active sessions, preserves its case records, saves the previous private files, and recreates only the backend with its existing image. It preserves the Gemini settings. Update the separate workstation credentials file privately afterward. If any step fails, inspect the reported stage and private backups before retrying.

The host `.deploy.json` uses `.env` as its sole environment file and retains `docker-compose.demo-reviewer-rate.yml`, so subsequent image deployments retain the reviewer password and its limit. The former `docker-compose.test-accounts.yml` and test-credentials file are no longer referenced by the deployment. Caddy supplies the proxy's client-IP header; the frontend proxy applies the limit only to the reviewer identity. The temporary frontend configuration is mounted from `infrastructure/aws/nginx.demo-reviewer.conf`; reconcile it if a future frontend image changes the proxy configuration. These controls do not prevent access by someone who already knows the temporary credentials, so rotate them after the demonstration.

## Authentication and persistence

The existing `reviewer` identity uses the environment password `REVIEWER_PASSWORD` (`AWS_REVIEWER_PASSWORD` in the private EC2 `.env`). Spring BCrypt-encodes it in memory and assigns OPERATOR and REVIEWER roles. There is no staff-account RDS table. The legacy `docker-compose.test-accounts.yml` contains no password override. Rotating this identity does not add a staff identity or change operator/rules-admin passwords.

The customer is created using the existing reviewer-only `POST /api/documents/customers`. The existing `customers` table stores its BCrypt hash, synthetic identity and assigned reviewer; `customer_sessions` stores only token digests. No migration or alternate authentication is added. The customer reference is `SYN-ALEX-001`, matching the existing synthetic PDF fixtures, and the assigned RM is `reviewer`. The helper accepts a duplicate response only as a reason to verify the original credentials; it never resets existing passwords, changes references or creates another identity to bypass a conflict. If that reference belongs to another account, stop and reconcile the existing synthetic identity rather than deleting it.

## Verify existing accounts

From the workstation, use the private credentials file to verify the existing logins, reviewer roles, customer submissions and role separation without provisioning an account or uploading another document:

```powershell
python scripts/demo/provision_test_accounts.py --credentials-file 'C:\Users\goati\Downloads\afi-resolve-test-credentials.env' --verify
```

The helper revokes only the temporary customer session it creates for this check. It does not change pre-existing sessions, passwords, assignments or cases. The reviewer and customer can also inspect their existing cases in their respective browser interfaces. Never print credentials or tokens.

## Deployment configuration

The EC2 private `.env` supplies the reviewer password and the Gemini API key and model. The test-credentials file is retained privately for account verification and future explicit rotation, but is not a Compose input. The deployment helper reads `.deploy.json`, which retains the temporary reviewer rate-limit override. The frontend proxy configuration is mounted from `infrastructure/aws/nginx.demo-reviewer.conf`, so reconcile this file if a future frontend image changes its proxy configuration.

Live Gemini has been verified using the configured model and the existing synthetic case. Changing reviewer deployment references does not modify the Gemini key, customer password hash, existing customer sessions, RDS case data or S3 documents.
