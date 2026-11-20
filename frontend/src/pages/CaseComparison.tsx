import type { Detail, Field } from "../document-types";
import { label } from "../api";
import { Disclosure } from "../ui";
import { requestedFields } from "./case-facts";

export function CaseComparison({
  detail,
  onInspect,
}: {
  detail: Detail;
  onInspect?: (field: Field) => void;
}) {
  const profile = detail.checks
    .flatMap((check) => check.evidence)
    .filter((field) => field.method === "SYNTHETIC_CUSTOMER_PROFILE");
  const declarations = detail.documents
    .filter(
      (doc) =>
        doc.document_role === "DECLARATION_RECEIPT" ||
        doc.document_role === "REQUEST_DOCUMENT" ||
        (!doc.document_role &&
          doc.document_type !== "SUPPORTING_DOCUMENT" &&
          doc.document_type !== "UNKNOWN"),
    )
    .flatMap((doc) =>
      doc.fields.map((field) => ({
        ...field,
        document_id: doc.id,
        version_id: field.version_id || doc.version_id,
        filename: field.filename || doc.filename,
      })),
    );
  const supporting = detail.documents
    .filter(
      (doc) =>
        doc.document_role === "SUPPORTING_EVIDENCE" ||
        doc.document_role === "REPOSITORY_EVIDENCE" ||
        (!doc.document_role && doc.document_type === "SUPPORTING_DOCUMENT"),
    )
    .flatMap((doc) =>
      doc.fields.map((field) => ({
        ...field,
        document_id: doc.id,
        version_id: field.version_id || doc.version_id,
        filename: field.filename || doc.filename,
      })),
    );
  const category = detail.submission.category;
  const addressKind = category === "ADDRESS_CHANGE" ? "mailing"
    : category === "RESIDENTIAL_ADDRESS_CHANGE" ? "residential" : null;
  const comparisonKey = (key: string) => {
    if (!addressKind) return key;
    if (key === "new_mailing_address" || key === "new_residential_address") return key.slice(4);
    return key === `${addressKind}_postal_code` || key === `new_${addressKind}_postal_code`
      ? "postal_code" : key;
  };
  const primary = [
    ...new Set(requestedFields[detail.submission.category] || ["customer_name", "customer_reference"]),
  ];
  const rest = [
    ...new Set(
      [...profile, ...declarations, ...supporting].map(
        (field) => comparisonKey(field.field_name),
      ),
    ),
  ].filter((key) => !primary.includes(key));
  function cell(fields: Field[], key: string) {
    const found = fields
      .filter((field) => comparisonKey(field.field_name) === key)
      .filter(
        (field, index, all) =>
          all.findIndex((other) => other.field_value === field.field_value &&
            other.document_id === field.document_id && other.version_id === field.version_id &&
            other.page_number === field.page_number) ===
          index,
      );
    return found.length ? (
      found.map((field, index) => (
        <div className="comparison-value" key={index}>
          <strong>{field.field_value || "Not confirmed"}</strong>
          {onInspect &&
          detail.documents.some(
            (doc) =>
              doc.id === field.document_id &&
              doc.versions.some((version) => version.id === field.version_id),
          ) ? (
            <button className="text-link" onClick={() => onInspect(field)}>
              View source{field.page_number ? ` · p${field.page_number}` : ""}
            </button>
          ) : (
            <small>
              {field.method === "SYNTHETIC_CUSTOMER_PROFILE"
                ? "Stored profile"
                : "Source not linked"}
            </small>
          )}
        </div>
      ))
    ) : (
      <span className="comparison-missing">No value shown</span>
    );
  }
  const primaryWithValues = primary.filter((key) => [...declarations, ...profile, ...supporting].some((field) => comparisonKey(field.field_name) === key));
  const current = detail.submission.validated_revision === detail.submission.revision &&
    detail.submission.status !== "CANCELLED";
  const findings = current && detail.submission.status !== "OPERATIONS"
    ? detail.exceptions.filter((item) => item.status === "OPEN") : [];
  function rows(keys: string[]) {
    return (
      <div className="comparison-table-scroll">
        <table className="evidence-table">
          <thead>
            <tr>
              <th>Information</th>

              <th>Requested</th>
              <th>Supporting evidence</th>
              {findings.length > 0 && <th>Finding</th>}
            </tr>
          </thead>
          <tbody>
            {keys.map((key) => {
              const related = findings.filter((item) =>
                (item.field_name ? [item.field_name] : item.evidence.map((field) => field.field_name))
                  .some((field) => field && comparisonKey(field) === key));
              const uncertain = related.length > 0 && detail.documents.some((doc) =>
                doc.extraction.observations?.some((item) =>
                  item.issue_type === "UNCERTAIN" && comparisonKey(item.field) === key));
              const outcome = uncertain ? "Uncertain" : related.length ? "Needs review" : "";
              return (
              <tr key={key}>
                <th scope="row">{label(key)}</th>

                <td>{cell(declarations, key)}</td>
                <td>{cell(supporting, key)}</td>
                {findings.length > 0 && <td><strong>{outcome}</strong></td>}
              </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    );
  }
  return (
    <section className="source-review" aria-label="Evidence comparison">
      <h2>Compare request and evidence</h2>
      {primaryWithValues.length ? rows(primaryWithValues) : <p>No request details are available to compare yet.</p>}

      {(rest.length > 0 || profile.length > 0) && (
        <Disclosure
          title="Other fields and bank records"
        >
          {!!rest.length && rows(rest)}
          {!!profile.length && <dl className="case-source-list">
            {profile.filter((field, i) => profile.findIndex((other) =>
              other.field_name === field.field_name && other.field_value === field.field_value) === i)
              .map((field, i) => <div key={i}><dt>{label(field.field_name)} on file</dt><dd>{field.field_value}</dd></div>)}
          </dl>}
        </Disclosure>
      )}
    </section>
  );
}
