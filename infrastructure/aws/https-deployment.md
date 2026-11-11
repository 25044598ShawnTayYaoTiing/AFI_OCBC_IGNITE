# EC2 HTTPS deployment

This is a deployment plan, not a record of a deployment. Obtain approval before changing AWS, DNS, EC2 services, or Vercel. The actual AWS environment has not been inspected. Confirm whether an existing load balancer, proxy, domain, certificate, firewall, or service already owns ports 80/443 before selecting this direct-to-EC2 setup. If HTTPS is already terminated upstream, do not add this edge without reviewing that topology.

The optional `docker-compose.https.yml` runs Caddy on the existing EC2 instance. Caddy explicitly uses Let's Encrypt to obtain and renew a publicly trusted certificate and redirects HTTP to HTTPS; it proxies to the existing Nginx frontend on the private Compose network. Nginx retains its `/api/` routes, upload limits, timeouts and dashboard, while the backend retains its existing RDS and S3 configuration. Caddy's named volumes persist certificate keys and renewal state; treat them as secrets and do not remove them. A cutover may briefly interrupt traffic. HTTP redirects cannot protect credentials already sent to HTTP, so use the HTTPS URL directly.

## AWS Console and DNS, after approval

1. In **EC2 → Instances**, select the existing instance and inspect its public IPv4 address, attached security groups and networking. Inspect **Load Balancers** as well. Confirm the instance is publicly reachable and the direct EC2 design is appropriate. If its public IP is not stable, use an existing Elastic IP or allocate and associate one in **EC2 → Elastic IPs**; check AWS pricing first. Do not replace an existing address without assessing its users.
2. Sign in to DuckDNS and update the existing `afi-ocbcignite` entry to the EC2 instance's public IPv4 address. No Route 53 zone is required. Verify `afi-ocbcignite.duckdns.org` resolves to that address. Remove or correct any stale IPv6 address if this instance is not reachable over IPv6. If the instance's public IP changes, update DuckDNS before certificate renewal or client access; keep any DuckDNS updater token private and outside this repository. Caddy uses public HTTP/TLS validation, so it does not require a DuckDNS token or DNS plugin.
3. In **EC2 → Security Groups**, select the instance's attached group and choose **Edit inbound rules**. Permit TCP **HTTP 80** and **HTTPS 443** from the intended public IPv4 sources (normally `0.0.0.0/0` for public access and certificate validation). Add `::/0` only for a working public IPv6 path. Keep existing restricted administration access and RDS rules; do not expose 8080, 8000 or 5432. Confirm any network ACL, host firewall and outbound internet access also permit certificate issuance and renewal. Port 80 must stay reachable for redirects and HTTP validation.

## EC2 configuration and cutover, after approval

The EC2 checkout must contain these reviewed, currently local changes, including `docker-compose.https.yml` and `infrastructure/aws/Caddyfile`; transfer them through your approved source process. Do not overwrite the existing private `.env`. Check Docker Compose is at least 2.24.4, and confirm there are no conflicting services or existing TLS proxy on 80/443. Before an application version change, take an RDS backup and review Flyway history as described in the AWS guide.

In the existing checkout, edit `.env` privately (`chmod 600 .env`). Retain all existing RDS, S3, IAM, AI and password settings. Set these values with your real hostname, without a trailing slash:

```dotenv
AFI_HTTPS_DOMAIN=afi-ocbcignite.duckdns.org
AWS_FRONTEND_ORIGIN=https://afi-ocbcignite.duckdns.org
AWS_CUSTOMER_PORTAL_ORIGINS=https://ocbc-customer-portal.vercel.app
```

`AFI_HTTPS_DOMAIN` is a DNS hostname only, without scheme, port, path, wildcard or whitespace. The public DNS record and TLS certificate must match it. Do not place credentials in these variables. If preview deployments need access, explicitly append their exact trusted origins separated by commas.

Run from the repository root on EC2, after verifying the DNS and firewall and obtaining approval:

```bash
# Run inside your existing AFI_OCBC_IGNITE checkout.
chmod 600 .env
nano .env
# Confirm DNS matches the EC2 public address and inspect port owners.
getent ahostsv4 afi-ocbcignite.duckdns.org
sudo ss -ltnp '( sport = :80 or sport = :443 )'
docker ps --format 'table {{.Names}}\t{{.Ports}}'
docker compose version
docker compose -f docker-compose.yml -f docker-compose.aws.yml -f docker-compose.https.yml config --quiet
docker compose -f docker-compose.yml -f docker-compose.aws.yml -f docker-compose.https.yml pull https
# Validate syntax without starting a TLS listener or requesting a certificate.
docker compose -f docker-compose.yml -f docker-compose.aws.yml -f docker-compose.https.yml run --rm --no-deps https caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
# Reuse the running application images; do not rebuild or pull application services.
docker compose -f docker-compose.yml -f docker-compose.aws.yml -f docker-compose.https.yml up -d --no-build --wait --wait-timeout 300 backend frontend https
docker compose -f docker-compose.yml -f docker-compose.aws.yml -f docker-compose.https.yml ps
docker compose -f docker-compose.yml -f docker-compose.aws.yml -f docker-compose.https.yml logs --tail=100 https
```

Use the same Compose project name and repository directory as the existing deployment (if it uses a custom `-p`, include it on every command). The cutover recreates the frontend to release host port 80 before Caddy starts. Do not run a separate host Nginx, Certbot standalone listener, or second Compose project on these ports. If an unrelated service owns either port, resolve that conflict before proceeding.

The named `https` service owns ports 80 and 443; the frontend no longer publishes a host port. Docker `--wait` does not prove certificate issuance. Check the logs and perform the public checks below. Caddy automatically renews Let's Encrypt certificates without a cron job or Certbot while it is running and can reach the certificate authority. Do not run `docker compose down -v` or delete the Caddy volumes.

## Vercel and verification

In Vercel, open the Customer Portal project → **Settings → Environment Variables**. Set the Production value of `VITE_API_BASE_URL` to `https://afi-ocbcignite.duckdns.org/api/customer`, substituting the verified hostname. Redeploy the intended production build after approval: environment changes apply to new deployments. This URL is public configuration, never a credential. The RM dashboard is at `https://afi-ocbcignite.duckdns.org` and calls the same backend through `/api/`.

From an external machine, substitute the hostname and verify the certificate and redirects without `-k`:

```bash
curl -I http://afi-ocbcignite.duckdns.org/
curl -fsS https://afi-ocbcignite.duckdns.org/actuator/health
curl -i -X OPTIONS https://afi-ocbcignite.duckdns.org/api/customer/auth/token \
  -H 'Origin: https://ocbc-customer-portal.vercel.app' \
  -H 'Access-Control-Request-Method: POST' \
  -H 'Access-Control-Request-Headers: authorization,content-type,idempotency-key'
```

Expect an HTTPS redirect, a healthy backend response, and CORS headers allowing the exact Vercel origin and requested headers. Then use an authorised synthetic customer account to test login and one synthetic PDF upload, and verify the corresponding RM case, RDS metadata and S3 object. These checks require access to the deployed environment and have not been performed here. The application is configured for synthetic demonstration data.

If rollback is needed, keep the Caddy volumes and restore the prior reviewed deployment and network restrictions. Reverting to the two-file HTTP configuration exposes port 80, so first restrict it to trusted administrators and do not use customer or staff credentials over public HTTP. Record and restore the previous Vercel environment value as appropriate.


References: [Caddy certificate issuer configuration](https://caddyserver.com/docs/caddyfile/directives/tls), [automatic HTTPS and renewal prerequisites](https://caddyserver.com/docs/automatic-https), [DuckDNS record updates](https://www.duckdns.org/spec.jsp).
