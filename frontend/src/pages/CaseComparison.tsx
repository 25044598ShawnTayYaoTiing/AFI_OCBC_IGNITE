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
    .filter((doc) => doc.document_type !== "SUPPORTING_DOCUMENT")
    .flatMap((doc) =>
      doc.fields.map((field) => ({
        ...field,
        document_id: doc.id,
        version_id: field.version_id || doc.version_id,
        filename: field.filename || doc.filename,
      })),
    );
  const supporting = detail.documents
    .filter((doc) => doc.document_type === "SUPPORTING_DOCUMENT")
    .flatMap((doc) =>
      doc.fields.map((field) => ({
        ...field,
        document_id: doc.id,
        version_id: field.version_id || doc.version_id,
        filename: field.filename || doc.filename,
      })),
    );
  const primary = [
    ...new Set(requestedFields[detail.submission.category] || ["customer_name", "customer_reference"]),
  ];
  const rest = [
    ...new Set(
      [...profile, ...declarations, ...supporting].map(
        (field) => field.field_name,
      ),
    ),
  ].filter((key) => !primary.includes(key));
  function cell(fields: Field[], key: string) {
    const found = fields
      .filter((field) => field.field_name === key)
      .filter(
        (field, index, all) =>
          all.findIndex((other) => other.field_value === field.field_value) ===
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
      <span className="comparison-missing">Not available</span>
    );
  }
  const primaryWithValues = primary.filter((key) => [...declarations, ...profile, ...supporting].some((field) => field.field_name === key));
  const supportNotRequired = detail.checks.some((check) => check.check_key === "SERVICE_SUPPORT" && check.outcome === "NOT_APPLICABLE");
  function rows(keys: string[]) {
    return (
      <div className="comparison-table-scroll">
        <table className="evidence-table">
          <thead>
            <tr>
              <th>Information</th>
              <th>On file</th>
              <th>Requested</th>
              <th>In supporting document</th>
              <th>Comparison</th>
            </tr>
          </thead>
          <tbody>
            {keys.map((key) => {
              const requested = declarations.filter((field) => field.field_name === key);
              const provided = supporting.filter((field) => field.field_name === key);
              const uncertain = detail.documents.some((doc) => doc.document_type === "SUPPORTING_DOCUMENT" && doc.extraction.observations?.some((item) => item.field === key));
              const compare = (value: string) => value.trim().replace(/\s+/g, " ").toLowerCase();
              const outcome = uncertain ? "Uncertain" : !provided.length ? (supportNotRequired ? "Not required" : "Missing") : !requested.length ? "No request value" :
                provided.every((field) => requested.some((other) => compare(field.field_value) === compare(other.field_value))) ? "Exact match" : "Values differ";
              return (
              <tr key={key}>
                <th scope="row">{label(key)}</th>
                <td>{cell(profile, key)}</td>
                <td>{cell(declarations, key)}</td>
                <td>{cell(supporting, key)}</td>
                <td><strong>{outcome}</strong></td>
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
      {primaryWithValues.length ? rows(primaryWithValues) : <p>No request details are available to compare yet.</p>}
      <p className="comparison-note">
        This compares the text as shown. The case outcome accounts for address formats and the rules for this service.
      </p>
      {!!rest.length && (
        <Disclosure
          title={`Other declared and extracted fields (${rest.length})`}
        >
          {rows(rest)}
        </Disclosure>
      )}
    </section>
  );
}
