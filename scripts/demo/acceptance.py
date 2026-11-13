"""Exercise the real HTTP stack; standard library only, no fabricated responses.

WARNING: resets the application's synthetic demo data. Run against demo instances.
"""

import argparse
import base64
from concurrent.futures import ThreadPoolExecutor
import json
import os
import time
import urllib.error
import urllib.request
import uuid


class Client:
    def __init__(self, base_url, username="reviewer", password="reviewer-demo"):
        self.base_url = base_url.rstrip("/")
        self.authorization = "Basic " + base64.b64encode(f"{username}:{password}".encode()).decode()

    def request(self, method, path, body=None, expected=(200, 201, 202, 204), auth=True):
        headers = {"Content-Type": "application/json"}
        if auth:
            headers["Authorization"] = self.authorization
        data = None if body is None else json.dumps(body).encode()
        request = urllib.request.Request(self.base_url + path, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(request, timeout=65) as response:
                status, raw = response.status, response.read()
        except urllib.error.HTTPError as error:
            status, raw = error.code, error.read()
        assert status in expected, f"{method} {path}: expected {expected}, got {status}: {raw.decode()[:1000]}"
        return json.loads(raw) if raw else None


def wait_investigation(client, case_id):
    deadline = time.monotonic() + 75
    while time.monotonic() < deadline:
        result = client.request("GET", f"/api/exceptions/{case_id}/investigation")
        if result and result.get("completedAt"):
            return result
        time.sleep(0.35)
    raise AssertionError("Investigation did not reach a persisted terminal result in 75 seconds")


def cases(client):
    return client.request("GET", "/api/exceptions?size=100")["items"]


def run_scenario(staff, reviewer, total, processed):
    reviewer.request("POST", "/api/demo/reset", {})
    reviewer.request("POST", "/api/demo/trigger-failure", {"totalRecords": total, "processedCount": processed})
    staff.request("POST", "/api/exceptions/detect", {})
    current = cases(staff)
    partial = [case for case in current if case["type"] == "PARTIAL_BATCH_FAILURE"]
    assert len(partial) == 1, f"Expected exactly one partial case, got {len(partial)}"
    case_id = partial[0]["id"]
    prefix = f"/api/exceptions/{case_id}"
    before_ids = {case["id"] for case in current}
    staff.request("POST", "/api/exceptions/detect", {})
    assert {case["id"] for case in cases(staff)} == before_ids, "Repeated detection duplicated cases"
    staff.request("POST", prefix + "/investigate", {})
    investigation = wait_investigation(staff, case_id)
    assert investigation["method"] in ("DETERMINISTIC_FALLBACK", "GEMINI"), investigation
    assert "RESUME_PENDING_ONLY" in investigation["candidateActions"]
    assert not investigation["requiresAdditionalEvidence"]
    before = staff.request("GET", prefix)
    simulations = staff.request("POST", prefix + "/simulate", {})
    after = staff.request("GET", prefix)
    assert before["evidence"] == after["evidence"], "Simulation changed operational evidence"
    full = next(s for s in simulations if s["strategy"] == "RETRY_FULL_BATCH")
    selective = next(s for s in simulations if s["strategy"] == "RESUME_PENDING_ONLY")
    assert full["decision"] == "REJECTED" and not full["safe"]
    assert full["duplicateAttemptRisk"] == processed
    assert full["totalRecords"] == total and full["pendingRecords"] == total - processed
    assert selective["decision"] == "ELIGIBLE" and selective["safe"]
    assert selective["affectedRecords"] == total - processed
    assert selective["expectedSuccessfulRecords"] == total and selective["duplicateAttemptRisk"] == 0
    assert full["sourceHash"] == selective["sourceHash"]
    staff.request("POST", prefix + "/resolve", {
        "actionType": "RETRY_FULL_BATCH", "simulationId": full["id"], "idempotencyKey": str(uuid.uuid4())
    }, expected=(400, 409, 422))
    payload = {"actionType": "RESUME_PENDING_ONLY", "simulationId": selective["id"], "idempotencyKey": str(uuid.uuid4())}
    with ThreadPoolExecutor(max_workers=2) as pool:
        responses = list(pool.map(lambda _: staff.request("POST", prefix + "/resolve", payload), range(2)))
    assert responses[0]["id"] == responses[1]["id"], "Concurrent retry created two actions"
    repeated = staff.request("POST", prefix + "/resolve", payload)
    assert repeated["id"] == responses[0]["id"], "Idempotent retry created another action"
    resolved = staff.request("GET", prefix)
    assert resolved["status"] == "RESOLVED", resolved
    actions = staff.request("GET", prefix + "/resolution")
    successful = [action for action in actions if action["status"] in ("VERIFIED", "COMPLETED", "SUCCEEDED")]
    assert len(successful) == 1, actions
    verification = successful[0]["verification"]
    assert verification and verification.get("passed") is True, verification
    events = staff.request("GET", prefix + "/audit")
    assert len(events) >= 8, "Missing lifecycle audit events"
    staff.request("POST", "/api/exceptions/detect", {})
    assert len([case for case in cases(staff) if case["type"] == "PARTIAL_BATCH_FAILURE"]) == 1
    print(f"PASS {total:,} records: {processed:,} preserved, {total - processed:,} recovered; verification passed; {len(events)} audit events")
    return investigation["method"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://localhost:5173")
    args = parser.parse_args()
    staff = Client(args.base_url, password=os.getenv("REVIEWER_PASSWORD", "reviewer-demo"))
    reviewer = Client(args.base_url, "reviewer", os.getenv("REVIEWER_PASSWORD", "reviewer-demo"))
    staff.request("GET", "/api/dashboard/summary", expected=(401,), auth=False)
    methods = [run_scenario(staff, reviewer, total, processed) for total, processed in ((1000, 800), (17, 6))]
    ambiguous = next(case for case in cases(staff) if case["type"] != "PARTIAL_BATCH_FAILURE")
    prefix = f'/api/exceptions/{ambiguous["id"]}'
    staff.request("POST", prefix + "/investigate", {})
    investigation = wait_investigation(staff, ambiguous["id"])
    assert investigation["requiresAdditionalEvidence"]
    simulations = staff.request("POST", prefix + "/simulate", {})
    assert all(s["decision"] != "ELIGIBLE" for s in simulations)
    reviewer.request("POST", prefix + "/approval", {
        "decision": "APPROVED", "actionType": "REQUEST_MANUAL_REVIEW",
        "comments": "Accept manual investigation; evidence does not authorize changing completed records."
    })
    assert staff.request("GET", prefix)["status"] != "RESOLVED"
    print("PASS ambiguous case remains unresolved after reviewer handoff; Resolution Management retains the policy guard")
    # Leave the primary, verified demonstration available for inspection.
    run_scenario(staff, reviewer, 1000, 800)
    print("Acceptance complete. Investigation methods:", ", ".join(sorted(set(methods))))


if __name__ == "__main__":
    main()
