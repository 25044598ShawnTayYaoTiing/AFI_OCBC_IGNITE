import test from "node:test";
import assert from "node:assert/strict";
import { groupFindings, issueCategory, issueGuidance, issueTitle } from "../src/pages/DocumentFindings.ts";

const finding = (id, check_key, status = "OPEN", overrides = {}) => ({
  id, check_key, status, field_name: "customer_reference", severity: "BLOCKING",
  action_type: "REQUEST_CORRECTION", evidence: [], ...overrides,
});

test("groups only related references with matching status and preserves every finding", () => {
  const findings = [
    finding("1", "REF_CUSTOMER_INFORMATION:file1"),
    finding("2", "REF_ACCOUNT_APPLICATION:file2"),
    finding("3", "REF_TAX_RESIDENCY:file3"),
    finding("4", "REF_TAX_RESIDENCY:old", "RESOLVED"),
    finding("5", "MATCH_MAILING", "OPEN", { field_name: "mailing_address" }),
    finding("6", "REF_SUPPORTING:file4"),
  ];
  const groups = groupFindings(findings);
  assert.equal(groups.length, 4);
  assert.deepEqual(groups[0].items.map((item) => item.id), ["1", "2", "3"]);
  assert.deepEqual(groups.flatMap((group) => group.items.map((item) => item.id)).sort(),
    findings.map((item) => item.id).sort());
  assert.match(issueGuidance(findings[0], "SYN-CASE"), /SYN-CASE/);
  assert.equal(issueTitle(findings[0]), "Customer reference must match submission");
  assert.equal(issueGuidance(findings[3], "SYN-CASE"), null);
});

test("different action or severity is not merged", () => {
  const groups = groupFindings([
    finding("1", "REF_ACCOUNT_APPLICATION:a"),
    finding("2", "REF_TAX_RESIDENCY:b", "OPEN", { severity: "WARNING" }),
    finding("3", "REF_CUSTOMER_INFORMATION:c", "OPEN", { action_type: "OTHER" }),
  ]);
  assert.equal(groups.length, 3);
});


test("separates human actions, automatic actions and verified autonomous resolutions", () => {
  assert.equal(issueCategory(finding("human", "MATCH_MAILING")), "Human-required action");
  assert.equal(issueCategory(finding("auto", "ATTACH_SUPPORTING_DOCUMENT", "OPEN", { action_type: "ATTACH_VERIFIED_DOCUMENT" })), "Policy-controlled automatic action");
  assert.equal(issueCategory(finding("done", "ATTACH_SUPPORTING_DOCUMENT", "RESOLVED", { resolution_method: "AUTOMATIC_VERIFIED_ATTACHMENT" })), "Autonomous resolution - verified");
});
