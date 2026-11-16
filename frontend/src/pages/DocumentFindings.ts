import type { Finding } from "../document-types";

const referenceRules = new Set([
  "REF_ACCOUNT_APPLICATION", "REF_CUSTOMER_INFORMATION", "REF_TAX_RESIDENCY",
]);

export function issueRuleKey(issue: Finding) {
  return issue.check_key?.split(":", 1)[0] || "";
}

export function issueCategory(issue: Finding) {
  if (issue.status === "RESOLVED")
    return issue.resolution_method === "AUTOMATIC_VERIFIED_ATTACHMENT"
      ? "Autonomous resolution - verified"
      : "Resolved by verified revalidation";
  return issue.action_type === "ATTACH_VERIFIED_DOCUMENT"
    ? "Policy-controlled automatic action"
    : "Human-required action";
}

export function issueTitle(issue: Finding) {
  return referenceRules.has(issueRuleKey(issue))
    ? "Customer reference must match submission"
    : issue.name;
}

export function groupFindings(findings: Finding[]) {
  const groups: { key: string; items: Finding[] }[] = [];
  const byKey = new Map<string, { key: string; items: Finding[] }>();
  for (const issue of findings) {
    const groupedReference = referenceRules.has(issueRuleKey(issue)) && issue.field_name === "customer_reference";
    // Group only equivalent reference checks with the same status and severity.
    const key = groupedReference
      ? `customer-reference:${issue.status}:${issue.severity}:${issue.action_type}`
      : issue.id;
    let group = byKey.get(key);
    if (!group) {
      group = { key, items: [] };
      byKey.set(key, group);
      groups.push(group);
    }
    group.items.push(issue);
  }
  return groups;
}

export function issueGuidance(issue: Finding, caseReference: string): string | null {
  if (issue.status !== "OPEN") return null;
  const rule = issueRuleKey(issue);
  if (referenceRules.has(rule))
    return `The case customer reference is ${caseReference}. Compare it with each cited form. If the case is wrong, create a new submission with the verified synthetic customer reference; if a form is wrong, obtain a corrected PDF. Do not infer the correct reference from majority agreement.`;
  if (rule === "MATCH_MAILING")
    return "Ask the customer to confirm the mailing address and provide corrected forms where needed. A residential address can legitimately differ; do not choose an address by majority vote.";
  if (rule === "ATTACH_SUPPORTING_DOCUMENT")
    return "The required supporting document is missing. Supply the required PDF or arrange an authorised, verified case repository source. Automatic attachment remains subject to preflight and policy checks.";
  if (rule === "ACCOUNT_SELECTION")
    return "The active prototype rule requires an explicit selection indicator of Present. Changing this requirement needs Resolution Management approval. If the form already names an account category, ask a Resolution Management approver whether that evidence should satisfy the rule; otherwise request a corrected form with the required indicator. Do not invent an indicator.";
  return null;
}
