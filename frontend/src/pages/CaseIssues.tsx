import { ArrowRight, FileSearch } from "lucide-react";
import type { Detail, Field } from "../document-types";
import { label } from "../api";
import { Disclosure } from "../ui";
import { groupFindings, issueTitle } from "./DocumentFindings";

export function CaseIssues({ detail, onInspect, selected, onSelect }: {
  detail: Detail;
  onInspect: (field: Field) => void;
  selected: string;
  onSelect: (id: string) => void;
}) {
  const groups = groupFindings(detail.exceptions.filter((issue) => issue.status === "OPEN"));
  const group = groups.find((item) => item.items.some((issue) => issue.id === selected)) || groups[0];
  if (!group) return null;
  const issue = group.items[0];
  const evidence = group.items.flatMap((item) => item.evidence).filter((field, index, all) =>
    all.findIndex((other) => other.version_id === field.version_id && other.field_name === field.field_name &&
      other.field_value === field.field_value) === index);
  const investigation = detail.investigations.find((item) => item.revision === detail.submission.revision);
  const explanation = investigation?.method === "GEMINI"
    ? investigation.recommendations.find((item) => item.exception_id === issue.id)?.explanation
    : undefined;
  return (
    <section className="case-issues" aria-label="Open issues">
      <h2>Open issues</h2>
      {groups.length > 1 && <div className="demo-issue-options" aria-label="Choose an issue">
        {groups.map((item) => <button key={item.key} className="button secondary" aria-pressed={item === group}
          onClick={() => onSelect(item.items[0].id)}>{issueTitle(item.items[0])}</button>)}
      </div>}
      <article className="issue-detail">
        <h3>{issueTitle(issue)}</h3>
        <p>{issue.corrective_action || issue.description}</p>
        {!!evidence.length && <section className="issue-sources" aria-label="Supporting evidence">
          {evidence.map((field, index) => {
            const document = detail.documents.find((doc) => doc.versions.some((version) => version.id === field.version_id));
            return <div key={index}>
              <FileSearch size={17} aria-hidden="true" />
              <div>
                <strong>{field.method === "SYNTHETIC_CUSTOMER_PROFILE" ? "On file" : field.filename || "Recorded evidence"}</strong>
                <p>{label(field.field_name)}: {field.field_value || "Unconfirmed"}</p>
                {document && <button className="text-link" onClick={() => onInspect(field)}>
                  View source{field.page_number ? " on page " + field.page_number : ""} <ArrowRight size={14} />
                </button>}
              </div>
            </div>;
          })}
        </section>}
        {explanation && <Disclosure title="AI recommendation"><p>{explanation}</p></Disclosure>}
      </article>
    </section>
  );
}
