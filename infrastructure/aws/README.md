# AWS EC2 deployment preparation

This deployment uses Ubuntu 24.04, Docker Compose, private RDS PostgreSQL and the private S3 bucket. No deployment commands have been executed against AWS. The application continues to accept synthetic documents only.

## Gemini and autonomous document resolution

The same AWS environment injection now supports live Gemini extraction and investigation, with `gemini-3.1-flash-lite` as the configurable default. Backend document automation defaults to enabled and bounded attempts; see [the autonomous workflow guide](../../docs/autonomous-document-resolution.md). RDS TLS, EC2-role S3 storage and network boundaries are unchanged. No AWS deployment was performed.

## Configuration

Use **both** Compose files for every AWS build, start, stop and log command:

```bash
docker compose -f docker-compose.yml -f docker-compose.aws.yml ...
```

Docker Compose **2.24.4 or later** is required for `!override`. The AWS file replaces the frontend port and backend mounts/dependencies rather than appending local settings. Normal AWS startup enables only frontend, backend and ai-service. PostgreSQL is put behind an inactive `local-database-only` profile and the backend no longer depends on it. Do not enable that profile or explicitly target `postgres` on EC2. The unused volume declarations inherited from the base file do not attach to the AWS backend.

| Setting | AWS value / source |
| --- | --- |
| `RDS_HOST` | `afi-resolve-db.c3m460sie8eu.ap-southeast-1.rds.amazonaws.com` |
| `RDS_PORT` / `RDS_DATABASE` | `5432` / `afi_resolve` |
| `DB_USERNAME` / `DB_PASSWORD` | Your RDS application database credentials |
| RDS certificate | Official regional CA bundle mounted read-only at `/etc/afi/certs/rds-ca-bundle.pem` |
| `S3_BUCKET` | `afi-resolve-documents-uniqueid` |
| `AWS_REGION` | `ap-southeast-1` |
| `S3_DOCUMENT_PREFIX` | `documents/` |
| `AWS_OPERATOR_PASSWORD`, `AWS_REVIEWER_PASSWORD`, `AWS_RULES_ADMIN_PASSWORD` | Separate strong application passwords you set on EC2 |
| `AWS_AI_SERVICE_KEY` | One long random internal key, injected identically into backend and AI containers |
| `GEMINI_API_KEY` / `GEMINI_MODEL` | Optional existing Gemini account configuration; blank key keeps deterministic extraction |
| `AWS_FRONTEND_ORIGIN` | Exact public origin, initially `http://EC2_PUBLIC_IP`, later `https://your-domain` |

The AWS credential variables are separate from local demo defaults to prevent accidental reuse. Compose refuses empty required credentials. `.env` is ignored and excluded from all service build contexts. Protect it with mode `600`; avoid sharing the rendered output of `docker compose config`, which includes expanded secrets. Use `config --quiet` for syntax checks.

The AWS Spring profile requires environment-supplied credentials and selects S3. The JDBC URL uses **`sslmode=verify-full`** and `sslrootcert`: both the trusted CA chain and the actual RDS endpoint hostname are verified. Do not substitute an IP address, disable verification, or use a non-validating SSL factory. Flyway runs the existing additive migrations on first start; the supplied database user needs the required schema DDL permissions and normal application DML permissions. Database `afi_resolve` must already exist.

## Network and IAM prerequisites

- EC2 must resolve and reach the private RDS endpoint. Permit PostgreSQL port 5432 from the EC2 security group to the RDS security group; keep RDS private.
- EC2 needs outbound HTTPS to S3 and Gemini when enabled, plus registry/package access during image builds. Use the existing internet/NAT path or a configured S3 endpoint as appropriate to the VPC.
- Only Nginx publishes a host port: **80**. Do not open host ports 8080, 8000 or 5432. Spring Boot and FastAPI communicate by Docker service name and have no published ports. This Docker bridge still permits outbound RDS/S3/metadata traffic.
- Attach the existing EC2 IAM role. `s3-policy.json` shows the application permissions: GetObject and PutObject, plus DeleteObject for failed database-transaction cleanup, limited to `documents/*`. Match this resource if you change the configured prefix. The application neither lists the bucket nor changes ACLs or bucket settings.
- Keep S3 Block Public Access enabled. Uploads use HTTPS and SSE-S3 AES256 encryption with no public ACL. No bucket URL or presigned URL is returned to the browser. All previews still pass case authorisation in Spring before object bytes are fetched.
- The Java S3 adapter explicitly uses `InstanceProfileCredentialsProvider`, obtaining temporary role credentials from IMDS. Do not configure static AWS access keys or mount an AWS credentials file.
- Require **IMDSv2**, enable the metadata endpoint and set its response hop limit to **2** so the Docker bridge can receive token responses. Set this in the EC2 console or run the following from an authorised AWS administration shell, substituting the existing instance ID:

```bash
aws ec2 modify-instance-metadata-options \
  --region ap-southeast-1 --instance-id i-YOUR_INSTANCE_ID \
  --http-endpoint enabled --http-tokens required --http-put-response-hop-limit 2
```

That administration action is separate from the application's S3-only role. The AWS backend also disables IMDSv1 fallback.

## EC2 commands, when ready to deploy

These are instructions for later execution. Docker/Compose are already installed on the stated EC2 instance.

```bash
git clone https://github.com/25044598ShawnTayYaoTiing/AFI_OCBC_IGNITE.git
cd AFI_OCBC_IGNITE
# The checkout must include the AWS-preparation changes described here.
docker compose version
umask 077
cp .env.example .env
chmod 600 .env
nano .env
```

Set all AWS variables listed above; leave the local-only PostgreSQL defaults unused. Use single quotes around `.env` secrets containing `$` or `#` so Compose preserves the intended value. Generate values with `openssl rand -hex 32` if desired and paste them into the private file. Do not commit it. For a synthetic demonstration with the existing demo controls, explicitly set **both** `AWS_DEMO_ENABLED=true` and `AWS_EXTRA_PROFILES=,demo`; otherwise demo mutations remain disabled by default.

Download the official **public** regional RDS CA bundle, which contains no secrets, and ensure the non-root backend can read it:

```bash
mkdir -p infrastructure/aws/certs
curl --fail --show-error --silent --location \
  https://truststore.pki.rds.amazonaws.com/ap-southeast-1/ap-southeast-1-bundle.pem \
  --output infrastructure/aws/certs/rds-ca-bundle.pem
chmod 644 infrastructure/aws/certs/rds-ca-bundle.pem

# Essential preparation checks: no services started.
docker compose -f docker-compose.yml -f docker-compose.aws.yml config --quiet
docker compose -f docker-compose.yml -f docker-compose.aws.yml config --services
docker compose -f docker-compose.yml -f docker-compose.aws.yml build
```

`config --services` should list **ai-service, backend and frontend**, with no postgres. The CA bind mount has `create_host_path: false`: startup fails instead of silently creating an empty directory if the CA file is missing.

Only when deployment is authorised:

```bash
docker compose -f docker-compose.yml -f docker-compose.aws.yml up -d --wait --wait-timeout 300
docker compose -f docker-compose.yml -f docker-compose.aws.yml ps
docker compose -f docker-compose.yml -f docker-compose.aws.yml logs --tail=100 backend ai-service frontend
curl --fail http://127.0.0.1/actuator/health
```

Visit `http://EC2_PUBLIC_IP`. Port 80 provides the requested initial HTTP deployment; application credentials travel over HTTP until HTTPS is configured, so restrict the port 80 security-group source to trusted presenter/developer IPs for that stage.

## HTTPS deployment path

The cleanest managed option is an Application Load Balancer with an ACM certificate for your domain. Point DNS at the ALB, terminate HTTPS on listener 443, redirect its HTTP listener to HTTPS, and forward to the existing EC2 Nginx port 80. Restrict the EC2 inbound port 80 source to the ALB security group. Set `AWS_FRONTEND_ORIGIN=https://your-domain` and recreate the backend. Nginx currently derives the forwarded protocol from its own connection; if application logic later needs the original ALB protocol, configure trusted forwarding specifically for the ALB rather than accepting arbitrary public forwarding headers.

For TLS directly on the existing EC2 instance without an ALB: issue a certificate for a DNS name, mount the certificate and key read-only into Nginx, provide an additional Compose override replacing the frontend ports with `80:80` and `443:443` and mounting an HTTPS Nginx server config, then add a 443 SSL server and redirect port 80 to HTTPS. Preserve the AWS proxy routes, upload-size limit, timeout and health check, automate renewal/reload, open 443 in the security group, and update `AWS_FRONTEND_ORIGIN`. Certificates and keys must never enter Git or the image. No TLS listener is enabled by this preparation work.

## Existing data and storage behaviour

Local development still uses the original filesystem directory and `document-files` volume. AWS uses `documents/<immutable-version-uuid>.pdf` in S3; PostgreSQL retains the existing opaque storage key, hashes, document versions and case relationships. The new adapter preserves the no-overwrite policy with conditional S3 puts and deletes newly uploaded objects on transaction rollback. If cleanup itself fails, a warning is logged and an orphan object can remain; reconcile against `document_versions` before removing any object. S3 object-store writes cannot share a database transaction.

Selecting S3 does **not** automatically migrate existing local PDFs or the local database. For a fresh RDS database, new uploads use S3 immediately. If moving existing metadata, first back up and migrate the database, then copy each referenced local UUID PDF to the configured S3 prefix with the exact same filename, using a separately authorised migration identity. Confirm every stored reference can be read before switching traffic; do not reset existing cases. Local development database/volume data is preserved by the override.

To stop AWS services without deleting data:

```bash
docker compose -f docker-compose.yml -f docker-compose.aws.yml down
```

RDS and S3 persist independently of container lifecycles. AWS connectivity, IAM policy effectiveness, real RDS TLS negotiation and image startup must be verified on EC2 when deployment is authorised; local build/config checks cannot prove those external conditions.

## Preparation checks completed locally

- Java backend compilation with the pinned AWS SDK passed (`mvn -DskipTests compile`).
- Frontend TypeScript and Vite production build passed (`npm run build`).
- Both local and merged AWS Compose configurations passed validation. The merged AWS model was checked for RDS certificate verification, S3 selection, removed local database/dependency, CA-only mount, private API ports and public port 80.
- An empty required AWS internal-service key was confirmed to reject configuration.
- No container image build/start, AWS API operation, RDS connection or S3 upload was executed. Docker image/runtime validation remains for EC2; this preparation environment provides the Compose parser but no Docker engine CLI.

## References

- [Compose merge and override behaviour](https://docs.docker.com/reference/compose-file/merge/)
- [RDS PostgreSQL TLS verification](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/PostgreSQL.Concepts.General.SSL.html)
- [AWS SDK Java instance-profile credentials](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/credentials-chain.html)
- [EC2 metadata options and container hop limits](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/configuring-instance-metadata-options.html)
