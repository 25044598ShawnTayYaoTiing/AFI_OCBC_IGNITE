import { RiskInvestigation } from "./RiskInvestigation";
import { CaseComparison } from "./CaseComparison";
import { ContactPhoneReview } from "./ContactPhoneReview";
import { useEffect, useRef, useState } from "react";
import { Link, useParams, useSearchParams } from "react-router-dom";
import { ArrowRight, UploadCloud } from "lucide-react";
import { label, post, uploadPdfs, useResource } from "../api";
import type { Detail, Field } from "../document-types";
import { Empty, ErrorBox, Loading, Notice, Panel } from "../components";
import { Disclosure, Tabs, TabPanel } from "../ui";
import { BASE, message, PdfPreview } from "./DocumentShared";
import {
  AutonomyEvidence,
  CorrectionTasks,
  HandoverHistory,
  PreflightView,
} from "./DocumentResolution";
import { AddressVerificationPanel } from "./AddressVerificationPanel";
import { CaseSummary } from "./CaseSummary";
import { CaseDecisionPanel } from "./CaseDecisionPanel";
import { CaseIssues } from "./CaseIssues";
import { reviewState } from "./review-state";
import { useAuth, isReviewer } from "../auth";
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
  const [finding, setFinding] = useState("");
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
    setFinding("");
    setError("");
    setNotice("");
    setPreviewPage(1);
  }, [id]);
  const data = result.data;
  const open = data?.exceptions.filter((item) => item.status === "OPEN") || [];
  const doc = data?.documents.find((item) => item.id === selected) || data?.documents[0];
  const locked = ["OPERATIONS", "CANCELLED"].includes(
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

  async function run(action: string, note?: string): Promise<boolean> {
    if (mutation.current) return false;
    mutation.current = true;
    setBusy(action);
    setError("");
    setNotice("");
    try {
      await post(
        `${BASE}/submissions/${id}/${action}`,
        ["approve", "clear-hold", "authorize-correction"].includes(action)
          ? { revision: data!.submission.revision, ...(note ? { note } : {}) }
          : {},
      );
      await result.refresh();
      setNotice(
        action === "preflight"
          ? "Safety check completed."
          : action === "approve"
            ? "Request sent to Operations."
            : action === "clear-hold"
              ? "Review decision saved."
              : action === "authorize-correction"
                ? "Correction request approved."
                : "Request checked.",
      );
      if (action === "preflight") setParams({ tab: "preflight" });
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
            Cases <ArrowRight size={14} />
          </Link>
        </div>
        <div className="case-title-row">
          <div>
            <h1>{label(data.submission.category)}</h1>
            <p>{data.submission.customer_name}</p>
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
                <CaseDecisionPanel key={id} detail={data} busy={!!busy} onAction={run} />
                {state.open.length > 0 && (
                  <CaseIssues detail={data} selected={finding} onSelect={setFinding} onInspect={inspect} />
                )}
                {!locked && isReviewer(session) && data.submission.category === "CONTACT_UPDATE" &&
                  open.some((item) => item.check_key === "SERVICE_PHONE_REVIEW") && (
                    <ContactPhoneReview id={id} revision={data.submission.revision} onUpdate={() => void result.refresh()} />
                  )}
                {state.customerTasks.length > 0 && (
                  <Disclosure title="Customer corrections" open={requestedTab === "corrections"}>
                    <CorrectionTasks detail={data} onUpload={() => setParams({ tab: "review" })} />
                  </Disclosure>
                )}
                <Disclosure title="Review details" open={openDetails ? true : undefined}>
                  {data.riskAssessment && <RiskInvestigation detail={data} onUpdate={() => void result.refresh()} showActions={false} />}
                  <AutonomyEvidence detail={data} onInspect={inspect} />
                  {!locked && <PreflightView detail={data} busy={!!busy} onRun={() => void run("preflight")} />}
                </Disclosure>
              </div>
            )}
            {tab === "review" && (
              <>
                <CaseComparison detail={data} onInspect={inspect} />
                {!!data.addressVerifications?.length && <Disclosure title="Address verification"><AddressVerificationPanel detail={data} onInspect={inspect} /></Disclosure>}
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
                                setFinding("");
                                setVersion("");
                                setPreviewPage(1);
                              }}
                            >
                              {data.documents.map((item) => (
                                <option key={item.id} value={item.id}>
                                  {label(item.document_type)} | {item.filename}
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
                        {(!version || version === doc.version_id) && doc.extraction.requires_review && (
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
                    <Panel title="Details read from this document" className="review-sidebar">
                      <div className="panel-body">
                        {(!version || version === doc.version_id) && doc.extraction.decision && (
                          <p className="muted">
                            {doc.extraction.decision.outcome === "UNAVAILABLE"
                              ? "AI evidence assessment unavailable."
                              : doc.extraction.decision.outcome === "PASSED"
                                ? "AI evidence assessment agreed with extraction."
                                : "AI evidence assessment needs review."}
                            {doc.extraction.decision.answers?.readable && (
                              <> Estimated readability {Math.round(doc.extraction.decision.answers.readable.noul * 100)}%.</>
                            )}
                          </p>
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
                        {!!doc.extraction.observations?.length && (
                          <Disclosure title="Details that need checking">
                            {doc.extraction.observations.map((observation, index) => (
                              <p key={index}>{label(observation.field)} (page {observation.page}) · {observation.supporting_text}</p>
                            ))}
                          </Disclosure>
                        )}
                      </div>
                    </Panel>
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
