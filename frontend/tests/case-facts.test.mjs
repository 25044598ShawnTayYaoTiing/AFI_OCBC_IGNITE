import test from "node:test";
import assert from "node:assert/strict";
import { caseFacts, nextCaseAction } from "../src/pages/case-facts.ts";

const detail = overrides => ({
  submission: { status: "PENDING_VALIDATION", revision: 2, validated_revision: 1 },
  exceptions: [], checks: [], documents: [], tasks: [], ...overrides,
});

test("absence of findings does not imply current checks passed or readiness", () => {
  const data = detail();
  const facts = caseFacts(data);
  assert.equal(facts.current, false);
  assert.equal(facts.severity, "Not assessed");
  assert.equal(facts.ready, false);
  assert.equal(nextCaseAction(data.submission, 0), "Check the request");
});

test("recorded policy severity and task ownership stay distinct from a risk score", () => {
  const data = detail({
    exceptions: [{ id: "one", status: "OPEN", severity: "BLOCKING" }, { id: "two", status: "RESOLVED", severity: "CRITICAL" }],
    tasks: [{ exception_id: "one", status: "OPEN", assigned_role: "REVIEWER" }, { exception_id: "two", status: "RESOLVED", assigned_role: "CUSTOMER" }],
  });
  assert.equal(caseFacts(data).severity, "Blocking");
  assert.deepEqual(caseFacts(data).owners, ["reviewer"]);
  assert.equal(data.submission.risk_score, undefined);
});

test("handover and revision freshness determine the next action", () => {
  assert.equal(nextCaseAction({ status: "OPERATIONS", revision: 3, validated_revision: 3 }, 0), "Completed");
  assert.equal(nextCaseAction({ status: "HUMAN_REVIEW", revision: 3, validated_revision: 3 }, 2), "Review open issues");
  assert.equal(nextCaseAction({ status: "DRAFT", revision: 0, validated_revision: null }, 0), "Add documents");
});

test("waiting for customer corrections requires a current validation", () => {
  const submission = { status: "CORRECTION_REQUIRED", revision: 2, validated_revision: 2, next_action: "Obtain a corrected tax declaration." };
  assert.equal(nextCaseAction(submission, 1), "Wait for customer corrections");
  assert.equal(nextCaseAction({ ...submission, revision: 3 }, 1), "Check the request");
});
