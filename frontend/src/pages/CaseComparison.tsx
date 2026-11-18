import type { Detail, Field } from "../document-types";
import { label } from "../api";
import { Disclosure } from "../ui";

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
  const category = detail.submission.category;
  const addressKind =
    category === "ADDRESS_CHANGE" ? "mailing"
      : category === "RESIDENTIAL_ADDRESS_CHANGE" ? "residential" : null;
  function comparisonKey(field: Field) {
    const key = field.field_name;
    if (!addressKind) return key;
    if (key === "new_mailing_address" || key === "new_residential_address") {
      return key.slice(4);
    }
    return key === `${addressKind}_postal_code` || key === `new_${addressKind}_postal_code`
      ? "postal_code" : key;
  }
  const priorities: Record<string, string[]> = {
    ADDRESS_CHANGE: ["mailing_address", "postal_code"],
    RESIDENTIAL_ADDRESS_CHANGE: ["residential_address", "postal_code"],
    CONTACT_UPDATE: ["email", "mobile"],
    TAX_RESIDENCY_UPDATE: [
      "tax_residency",
      "foreign_tax_country",
      "tax_identifier",
    ],
    ADDITIONAL_ACCOUNT: ["account_type", "currency", "source_of_funds"],
  };
  const primary = [
    ...new Set([
      ...(priorities[category] || []),
      "customer_name",
      "identity_number",
    ]),
  ];
  const rest = [
    ...new Set(
      [...profile, ...declarations, ...supporting].map(comparisonKey),
    ),
  ].filter((key) => !primary.includes(key));
  function cell(fields: Field[], key: string) {
    const found = fields
      .filter((field) => comparisonKey(field) === key)
      .filter(
        (field, index, all) =>
          all.findIndex((other) => other.field_value === field.field_value &&
            other.document_id === field.document_id &&
            other.version_id === field.version_id &&
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
      <span className="comparison-missing">Not returned</span>
    );
  }
  function rows(keys: string[]) {
    return (
      <div className="comparison-table-scroll">
        <table className="evidence-table">
          <thead>
            <tr>
              <th>Information</th>
              <th>Stored bank record</th>
              <th>Customer declaration</th>
              <th>Supporting evidence</th>
            </tr>
          </thead>
          <tbody>
            {keys.map((key) => (
              <tr key={key}>
                <th scope="row">{label(key)}</th>
                <td>{cell(profile, key)}</td>
                <td>{cell(declarations, key)}</td>
                <td>{cell(supporting, key)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    );
  }
  return (
    <section className="source-review" aria-label="Evidence comparison">
      {rows(primary)}
      <p className="comparison-note">
        Only evidence returned for this case is shown. A requested change may
        differ from the stored profile; policy checks determine whether the
        evidence is sufficient.
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
