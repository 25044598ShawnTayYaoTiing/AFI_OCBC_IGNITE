import { useEffect, useRef, useState } from "react";
import { post } from "../api";
import { ErrorBox, Panel } from "../components";
import { Dialog } from "../ui";

export function ContactPhoneReview({
  id,
  revision,
  onUpdate,
}: {
  id: string;
  revision: number;
  onUpdate: () => void;
}) {
  const [note, setNote] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [confirming, setConfirming] = useState(false);
  const submitting = useRef(false);
  useEffect(() => {
    setNote("");
    setConfirming(false);
    setError("");
  }, [id, revision]);
  async function record() {
    if (submitting.current || note.trim().length < 12) return;
    submitting.current = true;
    setBusy(true);
    setError("");
    try {
      await post(
        `/api/documents/submissions/${encodeURIComponent(id)}/verify-phone`,
        { revision, verificationNote: note },
      );
      setNote("");
      onUpdate();
    } catch (error) {
      setError(
        error instanceof Error
          ? error.message
          : "Unable to record verification.",
      );
    } finally {
      submitting.current = false;
      setBusy(false);
      setConfirming(false);
    }
  }
  return (
    <Panel title="Independent phone verification">
      <form
        className="panel-body"
        onSubmit={(event) => {
          event.preventDefault();
          if (!busy && note.trim().length >= 12) setConfirming(true);
        }}
      >
        <p>
          Email authorisation does not prove ownership of a new phone number.
          Record this only after completing the separate verification
          procedure. No SMS provider is connected.
        </p>
        <label>
          Verification method and evidence
          <input
            required
            minLength={12}
            maxLength={500}
            value={note}
            onChange={(event) => setNote(event.target.value)}
            disabled={busy}
          />
        </label>
        <button
          className="button secondary"
          disabled={busy || note.trim().length < 12}
        >
          Record completed phone verification
        </button>
        {error && <ErrorBox message={error} />}
      </form>
      {confirming && (
        <Dialog
          label="Confirm phone verification"
          initialFocus="[data-cancel]"
          onClose={() => {
            if (!busy) setConfirming(false);
          }}
        >
          <div className="decision-confirmation">
            <h2>Record completed phone verification?</h2>
            <p>
              This records your evidence for revision {revision}. Confirm only
              after completing the independent verification procedure.
            </p>
            <blockquote>{note}</blockquote>
            <div className="dialog-actions">
              <button
                data-cancel
                className="button secondary"
                disabled={busy}
                onClick={() => setConfirming(false)}
              >
                Cancel
              </button>
              <button
                className="button primary"
                disabled={busy}
                onClick={() => void record()}
              >
                {busy ? "Recording..." : "Confirm verification"}
              </button>
            </div>
          </div>
        </Dialog>
      )}
    </Panel>
  );
}
