import { createContext, useContext, useRef, useState } from "react";
import type { FormEvent } from "react";
import {
  ArrowRight,
  Check,
  Fingerprint,
  Layers3,
  LockKeyhole,
  ShieldCheck,
  X,
} from "lucide-react";
import { authenticate } from "./api";
import type { Session } from "./api";
import { Dialog } from "./ui";
export const AuthContext = createContext<{
  session: Session;
  switchUser: () => void;
}>({
  session: { username: "", roles: [], demoMode: false },
  switchUser: () => {},
});
export const useAuth = () => useContext(AuthContext);
export const isReviewer = (session: Session) =>
  session.roles.some((role) => role.replace("ROLE_", "") === "REVIEWER");
export function Brand({ compact = false }: { compact?: boolean }) {
  return (
    <div className={`brand ${compact ? "brand-dark" : ""}`}>
      <span className="brand-symbol">
        <svg
          width="28"
          height="28"
          viewBox="0 0 32 32"
          fill="none"
          aria-hidden="true"
        >
          <path d="m6 24 10-18 10 18h-6l-4-7-4 7H6Z" fill="currentColor" />
          <path
            d="M16 26v-5"
            stroke="white"
            strokeWidth="3"
            strokeLinecap="round"
          />
        </svg>
      </span>
      <span>
        <strong>
          AFI<span className="brand-light"> Resolve</span>
        </strong>
        <small>VALIDATE. RECONCILE. RESOLVE. PREVENT.</small>
      </span>
    </div>
  );
}
export function Login({
  onSuccess,
  modal = false,
  onClose,
}: {
  onSuccess: (s: Session) => void;
  modal?: boolean;
  onClose?: () => void;
}) {
  const passwordInput = useRef<HTMLInputElement>(null);
  function chooseAccount(user: string) {
    setUsername(user);
    setPassword("");
    setError("");
    passwordInput.current?.focus();
  }
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  async function submit(user = username, pass = password) {
    setBusy(true);
    setError("");
    try {
      onSuccess(await authenticate(user, pass));
    } catch (e) {
      setError(e instanceof Error ? e.message : "Sign in failed");
    } finally {
      setBusy(false);
    }
  }
  const form = (
    <div className="login-form">
      <div className="login-icon">
        <LockKeyhole size={24} />
      </div>
      <div className="eyebrow">OPERATIONS WORKSPACE</div>
      <h1>{modal ? "Switch account" : "Welcome to AFI Resolve"}</h1>
      <p className="muted">
        Sign in to validate synthetic forms and coordinate document corrections.
      </p>
      <form
        onSubmit={(e: FormEvent) => {
          e.preventDefault();
          void submit();
        }}
      >
        <label>
          Username
          <input
            autoComplete="username"
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            placeholder="Enter your username"
            required
          />
        </label>
        <label>
          Password
          <input
            ref={passwordInput}
            autoComplete="current-password"
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            placeholder="Enter your password"
            required
          />
        </label>
        {error && (
          <div className="error-message" role="alert">
            {error}
          </div>
        )}
        <button className="button primary wide" disabled={busy}>
          {busy ? "Verifying credentials…" : "Sign in to workspace"}
          <ArrowRight size={16} />
        </button>
      </form>
      <div className="login-divider">
        <span>CHOOSE AN ACCOUNT</span>
      </div>
      <div className="demo-accounts">
        <button
          disabled={busy}
          onClick={() => chooseAccount("operator")}
        >
          <Fingerprint size={20} />
          <span>
            <strong>Use operator account</strong>
            <small>Validate & reconcile</small>
          </span>
          <ArrowRight size={16} />
        </button>
        <button
          disabled={busy}
          onClick={() => chooseAccount("reviewer")}
        >
          <ShieldCheck size={20} />
          <span>
            <strong>Use reviewer account</strong>
            <small>Review & manage demo</small>
          </span>
          <ArrowRight size={16} />
        </button>
      </div>
      <p className="login-notice">
        <LockKeyhole size={13} /> Enter the configured account password. Credentials stay in memory.
      </p>
    </div>
  );
  if (modal)
    return (
      <Dialog
        className="modal auth-modal"
        label="Switch account"
        initialFocus="input"
        returnFocus="[data-account-menu-trigger]"
        onClose={() => onClose?.()}
      >
        <button
          className="icon-button modal-close"
          onClick={onClose}
          aria-label="Close account switcher"
        >
          <X size={20} />
        </button>
        {form}
      </Dialog>
    );
  return (
    <main className="login-page">
      <section className="login-story">
        <Brand />
        <div className="login-story-content">
          <span className="outline-pill">TEAM AFI · IGNITE 2026</span>
          <h2>
            Every exception.
            <br />A safer resolution.
          </h2>
          <p>
            Investigate with intelligence. Reconcile related forms.
            <br />
            Route with confidence.
          </p>
          <div className="login-visual">
            <div className="visual-orbit" />
            <div className="visual-core">
              <Layers3 size={38} />
            </div>
            <span className="visual-node n1">
              <Check size={16} /> Evidence collected
            </span>
            <span className="visual-node n2">
              <ShieldCheck size={16} /> Intake simulated
            </span>
            <span className="visual-node n3">
              <Check size={16} /> Outcome verified
            </span>
          </div>
        </div>
        <p className="login-footer">
          A Team AFI prototype. Synthetic records only.
          <br />
          Independent project · Not an official OCBC application.
        </p>
      </section>
      <section className="login-right">
        {form}
        <span className="login-bottom">
          Built for thoughtful, accountable operations.
        </span>
      </section>
    </main>
  );
}
