"""Exercise the authorised AWS demo using real HTTP services and new synthetic cases.
Never resets records. Credentials stay in memory. Run only after the expected release is live.
"""
import argparse
import contextlib
import io
import json
import secrets
import uuid
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo

import httpx
from dotenv import dotenv_values
from service_request_e2e import pdf

ROOT = Path(__file__).resolve().parents[2]


def execute(expected_sha, mailbox=None):
    report = {"expectedRelease": expected_sha, "database": "AWS RDS only", "scenarios": {}, "passed": False}
    with contextlib.redirect_stderr(io.StringIO()):
        config = dotenv_values(ROOT / ".env", encoding="utf-8-sig")
    password = config.get("REVIEWER_PASSWORD")
    if not password:
        report.update(status="BLOCKED", reason="Authorised demo staff credentials unavailable")
        return report
    staff = httpx.Client(base_url="https://afi-ocbcignite.duckdns.org", auth=("reviewer", password), timeout=600)

    def call(client, method, path, expected=200, **kwargs):
        response = client.request(method, path, **kwargs)
        if response.status_code != expected:
            raise RuntimeError(f"{method} {path.split('?')[0]} returned HTTP {response.status_code}; expected {expected}")
        return response.json()

    try:
        release = staff.get("/api/version")
        if release.status_code != 200 or release.json().get("version") != expected_sha:
            report.update(status="BLOCKED", reason="Expected backend release is not active on AWS")
            return report
        call(staff, "GET", "/actuator/health")
        print("Verified expected AWS release and backend health", flush=True)
        seeded = call(staff, "POST", "/api/documents/demo/seed-customers", json={"password": secrets.token_urlsafe(24)})
        assert seeded["seededProfiles"] == 100
        report["syntheticProfiles"] = seeded
        reference = "SYN-MENTOR-" + uuid.uuid4().hex[:12]
        username = "mentor-" + uuid.uuid4().hex[:12]
        customer_password = secrets.token_urlsafe(24)
        provisioned = call(staff, "POST", "/api/documents/customers", json={"username": username, "password": customer_password, "customerName": "Fictional Mentor Demo", "customerReference": reference, "relationshipManager": "reviewer"})
        customer = httpx.Client(base_url=str(staff.base_url).rstrip("/") + "/api/customer", timeout=600)
        session = call(customer, "POST", "/auth/token", json={"username": username, "password": customer_password})
        customer.headers["Authorization"] = "Bearer " + session["accessToken"]
        profile = call(customer, "GET", "/me")["bankingProfile"]
        if mailbox:
            call(staff, "POST", f"/api/documents/customers/{provisioned['id']}/email", json={"email": mailbox})
        today = datetime.now(ZoneInfo("Asia/Singapore")).date().isoformat()
        base_form = {"requestType": "ADDRESS_CHANGE", "mailingAddress": "22 Fictional Mentor Road", "declarationDate": today, "authorisation": True, "taxDeclaration": False}
        ids = []

        def create(values):
            result = call(customer, "POST", "/eforms", json={**base_form, **values}, headers={"Idempotency-Key": str(uuid.uuid4())})
            ids.append(result["submission"]["id"])
            return result

        def detail(case_id):
            return call(staff, "GET", f"/api/documents/submissions/{case_id}")

        def upload(case_id, address, replacement=None):
            content = pdf(["Supporting Document", "SYNTHETIC - NOT AN OFFICIAL BANK FORM", "Customer Name: Fictional Mentor Demo", "Customer Reference: " + reference, "Mailing Address: " + address])
            path = f"/submissions/{case_id}/documents" + (f"/{replacement}/replacement" if replacement else "")
            return call(customer, "POST", path, files={"files": ("synthetic-support.pdf", content, "application/pdf")}, headers={"Idempotency-Key": str(uuid.uuid4())})

        def approve(case_id, revision):
            return call(staff, "POST", f"/api/documents/submissions/{case_id}/approve", json={"revision": revision})

        clean = create({"requestType": "ADDITIONAL_ACCOUNT", "accountType": "SAVINGS", "currency": "SGD"})
        case_id = clean["submission"]["id"]
        assert detail(case_id)["riskAssessment"]["score"] == 0
        assert approve(case_id, clean["submission"]["revision"])["submission"]["status"] == "OPERATIONS"
        report["scenarios"]["A"] = {"passed": True, "case": case_id, "outcome": "OPERATIONS"}
        print("Scenario A passed / clean additional account approved", flush=True)

        low = create({})
        case_id = low["submission"]["id"]
        assert detail(case_id)["riskAssessment"]["score"] == 15
        assert low["corrections"]
        corrected = upload(case_id, base_form["mailingAddress"])
        assert detail(case_id)["riskAssessment"]["score"] == 0
        assert corrected["submission"]["validatedAt"]
        notices = call(customer, "GET", "/notifications")
        report["scenarios"]["B"] = {"passed": True, "case": case_id, "emailStates": list({notice.get("emailDeliveryStatus") for notice in notices if notice["submissionId"] == case_id and notice.get("emailDeliveryStatus")}), "revalidated": True}
        print("Scenario B passed / missing support corrected and revalidated", flush=True)

        medium = create({})
        case_id = medium["submission"]["id"]
        wrong = upload(case_id, "99 Conflicting Fictional Road")
        assessment = detail(case_id)
        assert assessment["riskAssessment"]["score"] == 35
        assert not wrong["corrections"]
        call(staff, "POST", f"/api/documents/submissions/{case_id}/authorize-correction", json={"revision": wrong["submission"]["revision"], "note": "Reviewed the conflicting support and authorised a customer correction"})
        authorised = call(customer, "GET", f"/submissions/{case_id}")
        assert authorised["corrections"]
        document = next(item for item in wrong["documents"] if item["type"] == "SUPPORTING_DOCUMENT")
        fixed = upload(case_id, base_form["mailingAddress"], document["id"])
        assert detail(case_id)["riskAssessment"]["score"] == 0
        report["scenarios"]["C"] = {"passed": True, "case": case_id, "resolverAuthorisation": True, "revalidatedRevision": fixed["submission"]["revision"]}
        print("Scenario C passed / medium-risk correction required resolver authorisation", flush=True)

        conflict_pdf = pdf(["Banking Service Request", "SYNTHETIC - NOT AN OFFICIAL BANK FORM", "Customer Name: Fictional Mentor Demo", "Customer Reference: " + reference, "Identity Number: DEMO-CONFLICT-ONLY", "Request Type: ADDRESS_CHANGE", "Mailing Address: 22 Fictional Mentor Road", "Signature: Present", "Declaration Date: " + today])
        high = call(customer, "POST", "/submissions?requestType=ADDRESS_CHANGE", files={"files": ("synthetic-identity-conflict.pdf", conflict_pdf, "application/pdf")}, headers={"Idempotency-Key": str(uuid.uuid4())})
        case_id = high["submission"]["id"]
        ids.append(case_id)
        held = detail(case_id)
        assert held["submission"]["status"] == "ON_HOLD" and held["riskAssessment"]["score"] >= 80
        assert not high["corrections"] and "riskAssessment" not in high and high["submission"]["status"] == "RESOLUTION_MANAGEMENT"
        call(staff, "POST", f"/api/documents/submissions/{case_id}/approve", expected=409, json={"revision": high["submission"]["revision"]})
        call(staff, "POST", f"/api/documents/submissions/{case_id}/clear-hold", expected=409, json={"revision": high["submission"]["revision"], "note": "This note cannot waive an unresolved identity conflict"})
        report["scenarios"]["D"] = {"passed": True, "case": case_id, "onHold": True, "approvalBlocked": True}
        print("Scenario D passed / high identity risk held with approval blocked", flush=True)

        tax = create({"requestType": "TAX_RESIDENCY_UPDATE", "taxResidency": "Foreign", "foreignTaxCountry": "Fictional Jurisdiction", "taxIdentifier": "", "taxDeclaration": True})
        case_id = tax["submission"]["id"]
        assert detail(case_id)["riskAssessment"]["score"] == 30
        call(staff, "POST", f"/api/documents/submissions/{case_id}/authorize-correction", json={"revision": tax["submission"]["revision"], "note": "Reviewed the missing TIN and supporting evidence before customer contact"})
        revised = call(customer, "POST", f"/submissions/{case_id}/eform", json={**base_form, "requestType": "TAX_RESIDENCY_UPDATE", "expectedRevision": tax["submission"]["revision"], "taxResidency": "Singapore", "taxDeclaration": True}, headers={"Idempotency-Key": str(uuid.uuid4())})
        assert detail(case_id)["riskAssessment"]["score"] == 0
        report["scenarios"]["E"] = {"passed": True, "case": case_id, "revalidatedRevision": revised["submission"]["revision"]}
        print("Scenario E passed / conditional TIN issue reviewed and corrected", flush=True)

        legacy = call(staff, "POST", "/api/documents/submissions", json={"customerName":"Alex Tan (Fictional)","customerReference":"SYN-ALEX-001","category":"ACCOUNT_OPENING"})
        case_id = legacy["submission"]["id"]
        ids.append(case_id)
        fixture_root = ROOT / "backend/src/main/resources/synthetic"
        files = [("files", (name, (fixture_root / name).read_bytes(), "application/pdf")) for name in ["customer-information.pdf", "account-application.pdf", "tax-residency.pdf"]]
        legacy = call(staff, "POST", f"/api/documents/submissions/{case_id}/upload", files=files)
        preflight = call(staff, "POST", f"/api/documents/submissions/{case_id}/preflight")
        report["scenarios"]["F"] = {"passed": bool(legacy["checks"]), "guardedPreflight": preflight["safeAdministrativeAttachment"], "case": case_id, "checksPassed": sum(check["passed"] for check in legacy["checks"]), "checksTotal": len(legacy["checks"]), "status": legacy["submission"]["status"]}
        live = [document["extraction"]["method"] for id in ids for document in detail(id)["documents"] if document["extraction"]["method"] != "DIGITAL_EFORM"]
        report["liveAiVerified"] = bool(live) and all(method == "GEMINI" for method in live)
        report.update(passed=all(result["passed"] for result in report["scenarios"].values()), status="SUCCEEDED")
        return report
    except Exception as failure:
        report.update(status="FAILED", reason=str(failure))
        return report
    finally:
        staff.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--expected-sha", required=True)
    parser.add_argument("--test-mailbox")
    args = parser.parse_args()
    result = execute(args.expected_sha, args.test_mailbox)
    output = ROOT / ".local/mentor-live-e2e.json"
    output.parent.mkdir(exist_ok=True)
    output.write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps(result, indent=2))
    raise SystemExit(0 if result["passed"] else 1)
