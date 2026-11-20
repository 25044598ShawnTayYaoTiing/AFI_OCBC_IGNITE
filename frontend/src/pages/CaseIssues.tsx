import type { Detail, Field } from "../document-types";
import { label } from "../api";
import { Disclosure } from "../ui";
import { groupFindings, issueTitle } from "./DocumentFindings";
import { reviewState } from "./review-state";

export function CaseIssues({ detail, onInspect }: {
  detail: Detail;
  onInspect: (field: Field) => void;
}) {
  const groups = groupFindings(reviewState(detail).open);
  const investigation = detail.investigations.find((item) => item.revision === detail.submission.revision &&
    (item.rule_set == null || item.rule_set === detail.ruleSet));
  if (!groups.length) return null;
  return (
    <section className="case-findings" aria-label="What needs attention">
      <h2>What needs attention</h2>
      {groups.map(({ key, items }) => {
        const issue = items[0];
        const evidence = items.flatMap((item) => item.evidence).filter((field, index, all) =>
          all.findIndex((other) => other.document_id === field.document_id &&
            other.version_id === field.version_id && other.field_name === field.field_name &&
            other.field_value === field.field_value && other.page_number === field.page_number) === index);
        const explanation = investigation?.method === "GEMINI"
          ? investigation.recommendations.find((item) => item.exception_id === issue.id)?.explanation
          : undefined;
        const action = issue.corrective_action || issue.description;
        const title = issueTitle(issue);
        return (
          <article className="case-finding" key={key}>
            <h3>{title}</h3>
            {action && action !== title && <p>{action}</p>}
            {!!evidence.length && <Disclosure title="Supporting evidence"><dl className="case-source-list">
              {evidence.map((field, index) => {
                const linked = detail.documents.some((doc) =>
                  doc.id === field.document_id && doc.versions.some((v) => v.id === field.version_id));
                return <div key={index}>
                  <dt>{label(field.field_name)}</dt>
                  <dd>{field.field_value || "Unconfirmed"}
                    {linked ? <button className="text-link" onClick={() => onInspect(field)}>
                      {field.filename || "View document"}{field.page_number ? ", page " + field.page_number : ""}
                    </button> : field.method === "SYNTHETIC_CUSTOMER_PROFILE" && <small>Bank record</small>}
                  </dd>
                </div>;
              })}
            </dl></Disclosure>}
            {explanation && explanation !== action && (
              explanation.length <= 200 && !/\b[A-Z]{2,}-[a-z0-9-]{8,}\b/i.test(explanation)
                ? <p><strong>AI found</strong> {explanation}</p>
                : <Disclosure title="AI findings"><p>{explanation}</p></Disclosure>
            )}
          </article>
        );
      })}
    </section>
  );
}
