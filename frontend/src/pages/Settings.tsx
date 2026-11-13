import { useEffect, useState } from "react";
import { Link, useLocation, useSearchParams } from "react-router-dom";
import { Check, UserRound } from "lucide-react";
import { label, useResource } from "../api";
import { useAuth } from "../auth";
import { Badge, ErrorBox, Notice, PageHeader, Panel } from "../components";
import {
  readNotificationPreferences,
  readUiPreferences,
  saveNotificationPreferences,
  saveUiPreferences,
} from "../preferences";
import type { NotificationPreferences, UiPreferences } from "../preferences";
import { Disclosure } from "../ui";
import { DocumentAiStatus, ValidationRules } from "./DocumentSettings";

const categories = [
  ["general", "General"],
  ["appearance", "Appearance"],
  ["notifications", "Notifications"],
  ["ai", "AI Integration"],
  ["rules", "Validation Rules"],
  ["security", "Security & Access"],
  ["about", "About"],
];
export function SettingsWorkspace() {
  const { session, switchUser } = useAuth();
  const location = useLocation();
  const [params, setParams] = useSearchParams();
  const aliases: Record<string, string> = {
    profile: "general",
    preferences: "appearance",
    integrations: "ai",
    access: "security",
    workspace: "general",
    policies: "rules",
    about: "about",
    notifications: "notifications",
  };
  const requested =
    params.get("section") || aliases[location.hash.slice(1)] || "general";
  const section = categories.some(([key]) => key === requested)
    ? requested
    : "general";
  const [preferences, setPreferences] =
    useState<UiPreferences>(readUiPreferences);
  const [notifications, setNotifications] = useState<NotificationPreferences>(
    readNotificationPreferences,
  );
  const [saved, setSaved] = useState(false);
  useEffect(() => setSaved(false), [preferences]);
  function save() {
    saveUiPreferences(preferences);
    setSaved(true);
    window.setTimeout(() => setSaved(false), 2500);
  }
  function updateNotification(
    key: keyof NotificationPreferences,
    value: boolean,
  ) {
    const next = { ...notifications, [key]: value };
    setNotifications(next);
    saveNotificationPreferences(next);
  }
  return (
    <div className="page-stack">
      <PageHeader
        title="Settings"
        description="Manage your workspace and interface preferences."
      />
      <div className="settings-layout">
        <nav className="settings-menu" aria-label="Settings categories">
          {categories.map(([key, title]) => (
            <button
              key={key}
              className={section === key ? "active" : ""}
              aria-current={section === key ? "page" : undefined}
              onClick={() => setParams({ section: key })}
            >
              {title}
            </button>
          ))}
        </nav>
        <div className="settings-content">
          {section === "general" && (
            <>
              <Panel title="Your account">
                <div className="panel-body">
                  <div className="profile-summary">
                    <span className="profile-avatar">
                      <UserRound size={24} />
                    </span>
                    <div>
                      <h3>{session.username}</h3>
                      <p>
                        {"Resolution Management"}
                      </p>
                    </div>
                    <button className="button secondary" onClick={switchUser}>
                      Switch account
                    </button>
                  </div>
                  <dl className="summary-list">
                    <div>
                      <dt>Workspace</dt>
                      <dd>AFI Operations</dd>
                    </div>
                    <div>
                      <dt>Environment</dt>
                      <dd>
                        {session.demoMode
                          ? "Synthetic demonstration"
                          : "Application workspace"}
                      </dd>
                    </div>
                    <div>
                      <dt>Language</dt>
                      <dd>English</dd>
                    </div>
                    <div>
                      <dt>Time zone</dt>
                      <dd>Asia/Singapore (SGT)</dd>
                    </div>
                  </dl>
                </div>
              </Panel>
              <Panel title="Date and table preferences">
                <div className="form-layout">
                  <div className="form-grid">
                    <label>
                      Date format
                      <select
                        value={preferences.dateFormat}
                        onChange={(event) =>
                          setPreferences({
                            ...preferences,
                            dateFormat: event.target
                              .value as UiPreferences["dateFormat"],
                          })
                        }
                      >
                        <option value="d-mmm-yyyy">1 Nov 2026</option>
                        <option value="yyyy-mm-dd">2026-11-01</option>
                      </select>
                    </label>
                    <label>
                      Time format
                      <select
                        value={preferences.timeFormat}
                        onChange={(event) =>
                          setPreferences({
                            ...preferences,
                            timeFormat: event.target
                              .value as UiPreferences["timeFormat"],
                          })
                        }
                      >
                        <option value="24h">24-hour</option>
                        <option value="12h">12-hour</option>
                      </select>
                    </label>
                    <label>
                      Rows per page
                      <select
                        value={preferences.pageSize}
                        onChange={(event) =>
                          setPreferences({
                            ...preferences,
                            pageSize: Number(
                              event.target.value,
                            ) as UiPreferences["pageSize"],
                          })
                        }
                      >
                        <option value={10}>10 rows</option>
                        <option value={20}>20 rows</option>
                        <option value={50}>50 rows</option>
                      </select>
                    </label>
                    <label>
                      Default report range
                      <select
                        value={preferences.defaultRange}
                        onChange={(event) =>
                          setPreferences({
                            ...preferences,
                            defaultRange: event.target
                              .value as UiPreferences["defaultRange"],
                          })
                        }
                      >
                        <option value="7">Last 7 days</option>
                        <option value="30">Last 30 days</option>
                        <option value="90">Last 90 days</option>
                        <option value="all">All available history</option>
                      </select>
                      <small>
                        Applies to reports that offer a date-range filter.
                      </small>
                    </label>
                  </div>
                  <SavePreferences saved={saved} onSave={save} />
                </div>
              </Panel>
            </>
          )}
          {section === "appearance" && (
            <Panel title="Appearance">
              <div className="form-layout">
                <div className="form-grid">
                  <label>
                    Theme
                    <select
                      value={preferences.theme}
                      onChange={(event) =>
                        setPreferences({
                          ...preferences,
                          theme: event.target.value as UiPreferences["theme"],
                        })
                      }
                    >
                      <option value="light">Light</option>
                      <option value="dark">Dark</option>
                      <option value="system">Use system preference</option>
                    </select>
                  </label>
                  <label>
                    Table density
                    <select
                      value={preferences.density}
                      onChange={(event) =>
                        setPreferences({
                          ...preferences,
                          density: event.target
                            .value as UiPreferences["density"],
                        })
                      }
                    >
                      <option value="comfortable">Comfortable</option>
                      <option value="compact">Compact</option>
                    </select>
                  </label>
                </div>
                <p className="muted">
                  Preferences are saved on this device. Compact density reduces
                  row spacing while preserving readable text.
                </p>
                <SavePreferences saved={saved} onSave={save} />
              </div>
            </Panel>
          )}
          {section === "notifications" && (
            <Panel title="In-app notifications">
              <div className="panel-body">
                <div className="notification-settings">
                  {(
                    [
                      [
                        "critical",
                        "Corrections required",
                        "Cases that need the relationship manager to act.",
                      ],
                      [
                        "approvals",
                        "Human review",
                        "Cases with uncertain evidence that need review.",
                      ],
                      [
                        "failed",
                        "Failed actions",
                        "Blocked or failed operational actions.",
                      ],
                      [
                        "verification",
                        "Verification failures",
                        "Actions that did not pass outcome verification.",
                      ],
                    ] as const
                  ).map(([key, title, description]) => (
                    <label key={key} className="setting-toggle">
                      <span>
                        <strong>{title}</strong>
                        <small>{description}</small>
                      </span>
                      <input
                        type="checkbox"
                        checked={notifications[key]}
                        onChange={(event) =>
                          updateNotification(key, event.target.checked)
                        }
                      />
                    </label>
                  ))}
                </div>
                <p className="section-note" role="status">
                  Changes save automatically on this device. Notifications are
                  shown in the app; no email or SMS is configured.
                </p>
              </div>
            </Panel>
          )}
          {section === "ai" && (
            <>
              <DocumentAiStatus />
              <ServiceHealth />
            </>
          )}
          {section === "rules" && <ValidationRules />}
          {section === "security" && (
            <>
              <Panel title="Access permissions">
                <div className="panel-body">
                  <p>
                    Current role:{" "}
                    <strong>{session.roles.map(label).join(", ")}</strong>
                  </p>
                  <div className="access-list">
                    <div>
                      <h3>Resolution Management</h3>
                      <p>
                        Review submissions across owners, propose rule revisions
                        and approve another authorised approver's draft.
                      </p>
                    </div>
                    <div>
                      <h3>Rules administrator</h3>
                      <p>
                        Provides an independent reviewer account. Account
                        management is configured on the backend.
                      </p>
                    </div>
                  </div>
                  <button className="button secondary" onClick={switchUser}>
                    Switch account
                  </button>
                </div>
              </Panel>
              <Disclosure title="Security and audit controls">
                <ul className="plain-list">
                  <li>
                    Permissions and case ownership apply to every document
                    operation.
                  </li>
                  <li>
                    Uploaded files are private. Earlier versions and lifecycle
                    audit history are retained.
                  </li>
                  <li>
                    AI recommendations cannot change operational data or
                    activate policies.
                  </li>
                  <li>
                    Only verified, eligible actions may complete an exception.
                  </li>
                  <li>
                    Sign-in credentials remain in session memory and are not
                    stored with preferences.
                  </li>
                </ul>
                <Link className="text-link" to="/audit">
                  Open audit history
                </Link>
              </Disclosure>
            </>
          )}
          {section === "about" && (
            <Panel title="AFI Resolve 2.0">
              <div className="panel-body">
                <h3>Validate. Reconcile. Resolve. Prevent.</h3>
                <p>
                  Document operations for evidence reconciliation, governed
                  automatic corrections and verified handover to Operations.
                </p>
                <dl className="summary-list">
                  <div>
                    <dt>Team</dt>
                    <dd>Team AFI</dd>
                  </div>
                  <div>
                    <dt>Project</dt>
                    <dd>OCBC Ignite Challenge 2026</dd>
                  </div>
                  <div>
                    <dt>Data</dt>
                    <dd>Synthetic demonstration only</dd>
                  </div>
                </dl>
                <p className="muted">
                  An independent prototype. It does not connect to live OCBC
                  banking systems.
                </p>
                <div className="inline-actions">
                  <Link className="button secondary" to="/demo">
                    Demo environment
                  </Link>
                  <Link className="text-link" to="/legacy">
                    Legacy recovery demo
                  </Link>
                </div>
              </div>
            </Panel>
          )}
        </div>
      </div>
    </div>
  );
}

function SavePreferences({
  saved,
  onSave,
}: {
  saved: boolean;
  onSave: () => void;
}) {
  return (
    <div className="form-actions">
      <button className="button primary" onClick={onSave}>
        <Check size={15} />
        {saved ? "Saved" : "Save preferences"}
      </button>
      {saved && (
        <span className="muted" role="status">
          Your preferences have been updated.
        </span>
      )}
    </div>
  );
}
function ServiceHealth() {
  const result = useResource<{ status: string }>("/actuator/health", 30000);
  return (
    <Disclosure title="Service diagnostics">
      {result.error ? (
        <ErrorBox message={result.error} />
      ) : (
        <p>
          Application health:{" "}
          <Badge value={result.data?.status || "CHECKING"} />
        </p>
      )}
      <Notice>
        Keys and other private configuration are managed on the server and are
        not sent to the browser.
      </Notice>
    </Disclosure>
  );
}
