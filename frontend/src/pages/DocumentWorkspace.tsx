import { RiskInvestigation } from "./RiskInvestigation";
import { CaseComparison } from "./CaseComparison";
import { useEffect, useRef, useState } from "react";
import { Link, useParams, useSearchParams } from "react-router-dom";
import { ChevronRight, UploadCloud } from "lucide-react";
import { label, post, uploadPdfs, useResource } from "../api";
import type { Detail, Field } from "../document-types";
import { Empty, ErrorBox, Loading, Notice, Panel } from "../components";
import { Disclosure, Tabs, TabPanel } from "../ui";
import { BASE, message, PdfPreview } from "./DocumentShared";
import {
  AutonomyEvidence,
  HandoverHistory,
  PreflightView,
} from "./DocumentResolution";
import { AddressVerificationPanel } from "./AddressVerificationPanel";
import { CaseSummary } from "./CaseSummary";
import { CaseDecisionPanel, type DecisionInput } from "./CaseDecisionPanel";
import { CaseIssues } from "./CaseIssues";
import { DecisionAssessmentPanel } from "./DecisionAssessmentPanel";
import { reviewState } from "./review-state";
import { useAuth, isReviewer } from "../auth";
const serviceNames: Record<string, string> = {
  ADDRESS_CHANGE: "Change mailing address",
  ADDITIONAL_ACCOUNT: "Open another account",
  CONTACT_UPDATE: "Change email address",
  TAX_RESIDENCY_UPDATE: "Update tax residency",
  RESIDENTIAL_ADDRESS_CHANGE: "Change residential address",
};

export function DocumentWorkspace() {
  const { id = "" } = useParams();
  const [params, setParams] = useSearchParams();
  const requestedTab = params.get("tab");
  const tab = requestedTab === "history" ? "history" : requestedTab === "review" ? "review" : "overview";
  const openDetails = requestedTab === "preflight" || requestedTab === "automation" || requestedTab === "exceptions" || requestedTab === "corrections";
  const { session } = useAuth();
  const mutation = useRef(false);
  const result = useResource<Detail>(`${BASE}/submissions/${id}`, 15000);
  const [busy, setBusy] = useState("");
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [evidenceReviewNote, setEvidenceReviewNote] = useState("");
  const [progress, setProgress] = useState(0);
  const [selected, setSelected] = useState("");
  const [version, setVersion] = useState("");
  const [previewPage, setPreviewPage] = useState(1);
  const preview = useRef<HTMLDivElement>(null);
  const [previewRequest, setPreviewRequest] = useState(0);
  useEffect(() => {
    if (!previewRequest || !preview.current) return;
    const top = preview.current.getBoundingClientRect().top + window.scrollY;
    window.scrollTo({
      top: Math.max(0, top - 16),
      behavior: "instant",
    });
    preview.current.querySelector("select")?.focus({ preventScroll: true });
  }, [previewRequest]);
  const fileInput = useRef<HTMLInputElement>(null);
  const [replacement, setReplacement] = useState<string>();
  useEffect(() => {
    setSelected("");
    setVersion("");
    setError("");
    setNotice("");
    setPreviewPage(1);
  }, [id]);
  const data = result.data;
  const doc = data?.documents.find((item) => item.id === selected) ||
    data?.documents.find((item) => item.document_role !== "DECLARATION_RECEIPT" &&
      item.extraction.method !== "DIGITAL_EFORM") || data?.documents[0];
  const locked = !!data?.submission.staff_suspended || ["OPERATIONS", "CANCELLED"].includes(
    data?.submission.status || "",
  );
  const currentVersion = doc?.versions.find((item) => item.id === doc.version_id);
  useEffect(() => {
    setEvidenceReviewNote("");
  }, [id, doc?.version_id, version]);

  async function confirmEvidence() {
    if (!doc || !data || mutation.current) return;
    mutation.current = true;
    setBusy("review-evidence");
    setError("");
    setNotice("");
    try {
      await post(`${BASE}/submissions/${id}/versions/${doc.version_id}/review-evidence`, {
        revision: data.submission.revision,
        note: evidenceReviewNote.trim(),
      });
      await result.refresh();
      setEvidenceReviewNote("");
      setNotice("Evidence review recorded.");
    } catch (failure) {
      setError(message(failure));
    } finally {
      mutation.current = false;
      setBusy("");
    }
  }

  async function run(action: string, input: DecisionInput): Promise<boolean> {
    if (mutation.current) return false;
    mutation.current = true;
    setBusy(action);
    setError("");
    setNotice("");
    try {
      await post(`${BASE}/submissions/${id}/${action}`, input);
      await result.refresh();
      setNotice({
        approve: "Case completed.",
        hold: "Case suspended.",
        "request-correction": "Correction request sent.",
        cancel: "Case cancelled.",
      }[action] || "Decision saved.");
      return true;
    } catch (error) {
      setError(message(error));
      return false;
    } finally {
      mutation.current = false;
      setBusy("");
    }
  }
  async function upload(files: File[], replace?: string) {
    if (!files.length || mutation.current) return;
    setError("");
    setNotice("");
    if (
      files.length > 10 ||
      files.some(
        (file) =>
          !/\.(pdf|jpe?g|png)$/i.test(file.name) ||
          file.size > 10 * 1024 * 1024 ||
          file.size === 0,
      )
    ) {
      setError("Choose up to 10 nonempty PDF, JPG or PNG files, no larger than 10 MB each.");
      return;
    }
    mutation.current = true;
    setBusy("upload");
    setProgress(0);
    try {
      await uploadPdfs(
        `${BASE}/submissions/${id}/upload${replace ? `?replace=${encodeURIComponent(replace)}` : ""}`,
        files,
        setProgress,
      );
      setVersion("");
      await result.refresh();
      setNotice(
        "Documents received. Open Case Overview to see the results.",
      );
    } catch (error) {
      setError(message(error));
    } finally {
      setBusy("");
      setReplacement(undefined);
      mutation.current = false;
    }
  }
  function inspect(field: Field) {
    setPreviewRequest((value) => value + 1);
    setSelected(field.document_id);
    setVersion(field.version_id || "");
    setPreviewPage(field.page_number || 1);
    setParams({ tab: "review" });
  }
  function chooseReplacement() {
    if (doc?.extraction.method === "DIGITAL_EFORM") {
      setNotice(
        "Ask the customer to update their request details. You can add supporting files here.",
      );
      return;
    }
    if (doc) {
      setReplacement(doc.id);
      fileInput.current?.click();
    }
  }
  if (result.loading) return <Loading />;
  if (result.error || !data)
    return (
      <ErrorBox
        message={result.error || "Submission unavailable"}
        retry={() => void result.refresh()}
      />
    );
  const state = reviewState(data);
  return (
    <div className="rm-workspace">
      <header className="case-context">
        <div>
          <Link className="back-link" to="/submissions">
            Cases <ChevronRight size={14} aria-hidden="true" />
          </Link>
          <span className="case-reference">
            {data.submission.customer_reference}
          </span>
        </div>
        <div className="case-title-row">
          <div>
            <h1>{data.submission.customer_name}</h1>
            <p className="case-service">{serviceNames[data.submission.category] || label(data.submission.category)}</p>
          </div>
        </div>
      </header>
      <div className="demo-case-layout">
        <div className="case-workspace-main">
          {error && <ErrorBox message={error} />}{" "}
          {notice && <Notice success>{notice}</Notice>}
          {busy && (
            <div className="operation-progress" role="status">
              {busy === "upload" ? (
                <>
                  <progress value={progress} max={100} />
                  <span>
                    {progress < 100
                      ? `Uploading documents (${progress}%)`
                      : "Reading documents and checking the package..."}
                  </span>
                </>
              ) : (
                <span>Checking the package...</span>
              )}
            </div>
          )}
          <Tabs
            id="submission-workspace"
            className="section-nav workspace-tabs"
            label="Submission workspace"
            value={tab}
            items={[
              { value: "overview", label: "Case Overview" },
              { value: "review", label: "Documents & Evidence" },
              { value: "history", label: "Audit Trail" },
            ]}
            onChange={(key) => {
              setParams({ tab: key });
            }}
          />
          <input
            ref={fileInput}
            hidden
            type="file"
            aria-label="Upload replacement document"
            accept="application/pdf,image/jpeg,image/png,.pdf,.jpg,.jpeg,.png"
            disabled={!!busy || locked}
            onChange={(event) => {
              void upload(Array.from(event.target.files || []), replacement);
              event.target.value = "";
            }}
          />
          <TabPanel
            id="submission-workspace"
            value={tab}
            className="page-stack workspace-panel"
          >
            {tab === "overview" && (
              <div className="overview-sections">
                <CaseSummary detail={data} />
                <CaseIssues detail={data} onInspect={inspect} />
                <AutonomyEvidence detail={data} />
                <CaseDecisionPanel key={id} detail={data} busy={!!busy} error={error} onAction={run} />
                {!state.cancelled && <Disclosure title="Advanced case details" open={openDetails ? true : undefined}>
                  {state.current && data.riskAssessment && (
                    <RiskInvestigation
                      detail={data}
                      onUpdate={() => void result.refresh()}
                      showActions={false}
                    />
                  )}
                  {!!data.simulations.length && <PreflightView detail={data} busy={!!busy} />}
                </Disclosure>}
              </div>
            )}
            {tab === "review" && (
              <>
                {!locked && (
                  <div
                    className="upload-area"
                    onDragOver={(event) => event.preventDefault()}
                    onDrop={(event) => {
                      event.preventDefault();
                      if (!busy)
                        void upload(Array.from(event.dataTransfer.files));
                    }}
                  >
                    <UploadCloud size={22} />
                    <div>
                      <strong>
                        {data.documents.length
                          ? "Add documents"
                          : "Upload the customer forms"}
                      </strong>
                      <p>
                        PDF, JPG or PNG. Up to 10 MB per file.
                      </p>
                    </div>
                    <label
                      className={`button ${data.documents.length ? "secondary" : "primary"}`}
                    >
                      Choose files
                      <input
                        className="sr-only"
                        type="file"
                        multiple
                        accept="application/pdf,image/jpeg,image/png,.pdf,.jpg,.jpeg,.png"
                        disabled={!!busy}
                        onChange={(event) => {
                          void upload(Array.from(event.target.files || []));
                          event.target.value = "";
                        }}
                      />
                    </label>
                  </div>
                )}
                {!doc ? (
                  <Empty
                    title="Ready for the document package"
                    description={
                      data.submission.category === "ACCOUNT_OPENING"
                        ? "Add the customer information form, account application and tax declaration."
                        : "Submitted declarations and supporting PDFs appear here when available. Check the request status and findings before taking action."
                    }
                  />
                ) : (
                  <div className="review-layout">
                    <div className="preview-column" ref={preview}>
                      <Panel className="preview-panel">
                        <div className="document-toolbar">
                          <label>
                            <span className="sr-only">Document to inspect</span>
                            <select
                              aria-label="Document to inspect"
                              value={doc.id}
                              onChange={(event) => {
                                setSelected(event.target.value);
                                setVersion("");
                                setPreviewPage(1);
                              }}
                            >
                              {data.documents.map((item) => (
                                <option key={item.id} value={item.id}>
                                  {label(item.document_type)}
                                  {item.document_role === "UNSUITABLE_SUPPORT_ATTACHMENT"
                                    ? " (Not supporting proof)"
                                    : ""} | {item.filename}
                                </option>
                              ))}
                            </select>
                          </label>
                          {doc.versions.length > 1 && <select
                            aria-label="Document version"
                            value={version || doc.version_id}
                            onChange={(event) => setVersion(event.target.value)}
                          >
                            {doc.versions.map((item) => (
                              <option key={item.id} value={item.id}>
                                Version {item.version}
                                {item.id === doc.version_id ? " (current)" : ""}
                              </option>
                            ))}
                          </select>}
                          {!locked &&
                            doc.document_role !== "DECLARATION_RECEIPT" &&
                            doc.extraction.method !== "DIGITAL_EFORM" && (
                              <button
                                className="button secondary"
                                disabled={!!busy}
                                onClick={chooseReplacement}
                              >
                                Replace document
                              </button>
                            )}
                        </div>
                        {version && version !== doc.version_id && (
                          <Notice>
                            You are viewing an earlier document version.
                            Findings and current values may refer to a different
                            version; check each citation before acting.
                          </Notice>
                        )}
                        {(!version || version === doc.version_id) &&
                          doc.extraction.requires_review &&
                          doc.extraction.decision?.outcome !== "UNAVAILABLE" && (
                          <Notice>
                            {doc.evidence_reviewed_by
                              ? `Evidence review recorded by ${doc.evidence_reviewed_by}.`
                              : "Compare the original with the document type and each extracted field before recording review."}
                          </Notice>
                        )}
                        <PdfPreview
                          caseId={id}
                          versionId={version || doc.version_id}
                          page={previewPage}
                          pageCount={doc.versions.find(item => item.id === (version || doc.version_id))?.source_page_count || 1}
                          convertedFromImages={doc.versions.find(item => item.id === (version || doc.version_id))?.converted_from_images || false}
                        />
                        {!locked && isReviewer(session) && (!version || version === doc.version_id)
                          && doc.extraction.requires_review && !doc.evidence_reviewed_by
                          && doc.document_type !== "UNKNOWN" && doc.fields.length > 0
                          && !doc.extraction.observations?.some((item) => item.issue_type === "UNCERTAIN") && (
                          currentVersion?.uploaded_by === session?.username ? (
                            <Notice>Another reviewer must inspect this upload.</Notice>
                          ) : (
                            <div className="form-body">
                              <label>
                                Evidence review note
                                <textarea value={evidenceReviewNote} rows={3} maxLength={1000}
                                  disabled={!!busy} onChange={(event) => setEvidenceReviewNote(event.target.value)} />
                              </label>
                              <button type="button" className="button secondary"
                                disabled={!!busy || evidenceReviewNote.trim().length < 12}
                                onClick={() => void confirmEvidence()}>
                                Confirm document type and extracted fields
                              </button>
                            </div>
                          )
                        )}
                      </Panel>
                    </div>
                    <div className="review-sidebar">
                      <CaseComparison detail={data} onInspect={inspect} />
                      {!!data.addressVerifications?.length && (
                        <Disclosure title="Address verification">
                          <AddressVerificationPanel detail={data} onInspect={inspect} />
                        </Disclosure>
                      )}
                      <Disclosure title="Advanced document details">
                      <div className="panel-body">
                        {(!version || version === doc.version_id) && doc.evidence_eligibility_reason && (
                          <p className="field-hint">
                            Role: <strong>{label(doc.document_role || doc.document_type)}</strong> ·{" "}
                            {doc.evidence_eligibility_reason}
                          </p>
                        )}
                        {(!version || version === doc.version_id) && doc.extraction.model_document_type && doc.extraction.model_document_type !== doc.document_type && (
                          <p className="field-hint">
                            Effective classification: <strong>{label(doc.document_type)}</strong> (initial model classification: {label(doc.extraction.model_document_type)}).
                          </p>
                        )}
                        {(!version || version === doc.version_id) && doc.extraction.decision && (
                          <DecisionAssessmentPanel decision={doc.extraction.decision} currentRevision={data.submission.revision} />
                        )}
                        {version && version !== doc.version_id ? (
                          <Notice>
                            Select the current version to see its extracted details.
                          </Notice>
                        ) : (
                          <dl className="field-list">
                            {doc.fields.map((field) => (
                              <div key={field.field_name}>
                                <dt>{label(field.field_name)}</dt>
                                <dd>
                                  {field.field_value}
                                  <button
                                    className="text-link"
                                    onClick={() =>
                                      inspect({
                                        ...field,
                                        document_id: doc.id,
                                        version_id: doc.version_id,
                                        filename: doc.filename,
                                      })
                                    }
                                  >
                                    View source
                                    {field.page_number
                                      ? ` | page ${field.page_number}`
                                      : " | page unconfirmed"}
                                  </button>
                                </dd>
                              </div>
                            ))}
                          </dl>
                        )}
                        {(!version || version === doc.version_id) && !!doc.extraction.observations?.length && (
                          <Disclosure title="Details that need checking">
                            {doc.extraction.observations.map((observation, index) => (
                              <p key={index}>{label(observation.field)} (page {observation.page}) · {observation.supporting_text}</p>
                            ))}
                          </Disclosure>
                        )}
                      </div>
                      </Disclosure>
                    </div>
                  </div>
                )}
              </>
            )}
            {tab === "history" && <HandoverHistory detail={data} />}
          </TabPanel>
        </div>
      </div>
    </div>
  );
}
