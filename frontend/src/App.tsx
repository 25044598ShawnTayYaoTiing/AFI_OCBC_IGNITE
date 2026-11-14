import {
  lazy,
  Suspense,
  useEffect,
  useId,
  useMemo,
  useRef,
  useState,
} from "react";
import { Navigate, NavLink, Route, Routes, useLocation, useNavigationType } from "react-router-dom";
import {
  Activity,
  Bell,
  Boxes,
  ChevronDown,
  CircleHelp,
  ClipboardCheck,
  FileBarChart2,
  LayoutDashboard,
  ListFilter,
  LogOut,
  Menu,
  PanelLeftClose,
  PanelLeftOpen,
  Search,
  Settings2,
  ShieldCheck,
  X,
} from "lucide-react";
import { AuthContext, Brand, Login } from "./auth";
import { clearSession, useResource, label, dateTime } from "./api";
import type { Audit, Session } from "./api";
import { Empty, Loading, ErrorBox } from "./components";
import { applyUiPreferences, readNotificationPreferences } from "./preferences";
const Overview = lazy(() =>
  import("./pages/Operations").then((pages) => ({ default: pages.Overview })),
);
const Exceptions = lazy(() =>
  import("./pages/Operations").then((pages) => ({ default: pages.Exceptions })),
);
const Investigations = lazy(() =>
  import("./pages/Operations").then((pages) => ({
    default: pages.Investigations,
  })),
);
const ReportsPage = lazy(() =>
  import("./pages/Operations").then((pages) => ({
    default: pages.ReportsPage,
  })),
);
const ExceptionDetail = lazy(() =>
  import("./pages/Operations").then((pages) => ({
    default: pages.ExceptionDetail,
  })),
);
const Twin = lazy(() =>
  import("./pages/Workflows").then((pages) => ({ default: pages.Twin })),
);
const ResolutionPage = lazy(() =>
  import("./pages/Workflows").then((pages) => ({
    default: pages.ResolutionPage,
  })),
);
const AuditPage = lazy(() =>
  import("./pages/Workflows").then((pages) => ({ default: pages.AuditPage })),
);
const LegacySettingsPage = lazy(() =>
  import("./pages/Workflows").then((pages) => ({
    default: pages.SettingsPage,
  })),
);
import {
  DocumentOverview,
  DocumentQueue,
  DocumentWorkspace,
  DocumentReports,
  DocumentAudit,
  DocumentDemo,
} from "./pages/Documents";
import type { Submission } from "./document-types";
import { SettingsWorkspace } from "./pages/Settings";
import { Dialog } from "./ui";
const navigationGroups = [
  {
    title: "",
    items: [
      { to: "/", label: "Command centre", icon: LayoutDashboard },
      { to: "/submissions", label: "Case queue", icon: Boxes },
      { to: "/exceptions", label: "Needs attention", icon: ListFilter },
      { to: "/operations", label: "Completed handovers", icon: ClipboardCheck },
      { to: "/reports", label: "Reports & audit", icon: FileBarChart2 },
      { to: "/settings", label: "Settings", icon: Settings2 },
    ],
  },
];
const aliases: Record<string, string> = {
  "/new": "/submissions",
  "/investigations": "/submissions",
  "/corrections": "/submissions",
  "/preflight": "/submissions",
  "/audit": "/reports",
};

function GlobalSearch() {
  const [query, setQuery] = useState("");
  const [search, setSearch] = useState("");
  const [open, setOpen] = useState(false);
  const input = useRef<HTMLInputElement>(null);
  const region = useRef<HTMLDivElement>(null);
  const resultsId = useId();
  const location = useLocation();
  useEffect(() => {
    setOpen(false);
  }, [location.pathname]);
  useEffect(() => {
    const timer = window.setTimeout(() => setSearch(query.trim()), 250);
    return () => window.clearTimeout(timer);
  }, [query]);
  const results = useResource<Submission[]>(
    "/api/documents/submissions",
    15000,
  );
  const matches = (results.data || [])
    .filter((c) =>
      `${c.customer_name} ${c.customer_reference} ${c.id}`
        .toLowerCase()
        .includes(search.toLowerCase()),
    )
    .slice(0, 5);
  return (
    <div
      className="global-search"
      onBlur={(event) => {
        if (!event.currentTarget.contains(event.relatedTarget as Node | null))
          setOpen(false);
      }}
      onKeyDown={(event) => {
        if (event.key === "Escape") {
          event.preventDefault();
          event.stopPropagation();
          setOpen(false);
          input.current?.focus();
        }
        if (!open) return;
        const links = Array.from(
          region.current?.querySelectorAll<HTMLAnchorElement>("a[href]") || [],
        );
        const index = links.indexOf(
          document.activeElement as HTMLAnchorElement,
        );
        if (event.key === "ArrowDown" && links.length) {
          event.preventDefault();
          links[(index + 1) % links.length].focus();
        }
        if (event.key === "ArrowUp" && links.length) {
          event.preventDefault();
          if (index <= 0) input.current?.focus();
          else links[index - 1].focus();
        }
      }}
    >
      <Search size={16} aria-hidden="true" />
      <input
        ref={input}
        type="search"
        aria-label="Search document submissions"
        aria-controls={open && query.trim() ? resultsId : undefined}
        placeholder="Search customer or reference"
        value={query}
        onChange={(event) => {
          setQuery(event.target.value);
          setOpen(true);
        }}
        onFocus={() => setOpen(true)}
      />
      {query && (
        <button
          aria-label="Clear search"
          onClick={() => {
            setQuery("");
            input.current?.focus();
          }}
        >
          <X size={16} />
        </button>
      )}
      {open && query.trim() && (
        <div
          id={resultsId}
          ref={region}
          className="search-results"
          role="region"
          aria-label="Matching cases"
        >
          {results.error ? (
            <ErrorBox
              message={results.error}
              retry={() => void results.refresh()}
            />
          ) : results.loading || search !== query.trim() ? (
            <Loading text="Searching cases..." />
          ) : matches.length ? (
            matches.map((item) => (
              <NavLink
                key={item.id}
                to={`/submissions/${item.id}`}
                onClick={() => setOpen(false)}
              >
                <span className="search-result-main">
                  <strong>{item.customer_name}</strong>
                  <small>
                    {item.customer_reference} / {label(item.status)}
                  </small>
                </span>
              </NavLink>
            ))
          ) : (
            <p className="search-empty" role="status">
              No matching cases. Try a customer name or reference.
            </p>
          )}
        </div>
      )}
    </div>
  );
}

function NotificationCenter() {
  const [open, setOpen] = useState(false);
  const activity = useResource<Audit[]>("/api/documents/audit", 30000);
  const cases = useResource<Submission[]>("/api/documents/submissions", 30000);
  const [preferences, setPreferences] = useState(readNotificationPreferences);
  useEffect(() => {
    const update = () => setPreferences(readNotificationPreferences());
    window.addEventListener("afi-preferences-changed", update);
    return () => window.removeEventListener("afi-preferences-changed", update);
  }, []);
  const notices = useMemo(() => {
    const entries = (cases.data || [])
      .filter(
        (c) =>
          (c.status === "CORRECTION_REQUIRED" && preferences.critical) ||
          (["HUMAN_REVIEW", "RESOLUTION_MANAGEMENT"].includes(c.status) && preferences.approvals),
      )
      .map((c) => ({
        id: c.id,
        title: label(c.status),
        detail: c.customer_name,
        to: `/submissions/${c.id}?tab=exceptions`,
        time: c.created_at,
        tone: "pending",
      }));
    for (const event of activity.data || []) {
      const failed =
        /AUTOMATION_(FAILED|BLOCKED)|POLICY_BLOCKED/.test(event.eventType) &&
        preferences.failed;
      const verification =
        /VERIFICATION_FAILED/.test(event.eventType) && preferences.verification;
      if (failed || verification)
        entries.push({
          id: event.id,
          title: label(event.eventType.replace("DOCUMENT_", "")),
          detail: "Review the recorded outcome",
          to: event.exceptionId
            ? `/submissions/${event.exceptionId}?tab=automation`
            : "/audit",
          time: event.timestamp,
          tone: "critical",
        });
    }
    return entries.sort((a, b) => b.time.localeCompare(a.time));
  }, [
    cases.data,
    activity.data,
    preferences.critical,
    preferences.approvals,
    preferences.failed,
    preferences.verification,
  ]);
  return (
    <div
      className="notification-menu"
      onBlur={(event) => {
        if (!event.currentTarget.contains(event.relatedTarget as Node | null))
          setOpen(false);
      }}
      onKeyDown={(event) => {
        if (event.key === "Escape") {
          event.stopPropagation();
          setOpen(false);
          event.currentTarget
            .querySelector<HTMLButtonElement>("button")
            ?.focus();
        }
      }}
    >
      <button
        className="icon-button notification-button"
        aria-label={`Notifications${activity.error || cases.error ? ", data unavailable" : notices.length ? `, ${notices.length} requiring attention` : ""}`}
        aria-expanded={open}
        onClick={() => setOpen(!open)}
      >
        <Bell size={18} />
        {!activity.error && !cases.error && notices.length > 0 && (
          <span className="notification-count">
            {notices.length > 9 ? "9+" : notices.length}
          </span>
        )}
      </button>
      {open && (
        <section className="notification-popover" aria-label="Notifications">
          <header>
            <div>
              <strong>Notifications</strong>
              <small>From current case and audit data</small>
            </div>
            <button
              className="icon-button"
              aria-label="Close notifications"
              onClick={() => setOpen(false)}
            >
              <X size={16} />
            </button>
          </header>
          {activity.error || cases.error ? (
            <ErrorBox
              message={activity.error || cases.error}
              retry={() =>
                void Promise.all([activity.refresh(), cases.refresh()])
              }
            />
          ) : activity.loading || cases.loading ? (
            <Loading text="Loading notifications…" />
          ) : notices.length ? (
            notices.slice(0, 8).map((notice) => (
              <NavLink
                key={notice.id}
                to={notice.to}
                onClick={() => setOpen(false)}
                className="notification-item"
              >
                <span className={`notification-marker ${notice.tone}`} />
                <span>
                  <strong>{notice.title}</strong>
                  <small>{notice.detail}</small>
                </span>
                {notice.time && <time>{dateTime(notice.time)}</time>}
              </NavLink>
            ))
          ) : (
            <p className="notification-empty">
              No priority notifications from the current data.
            </p>
          )}
          {!activity.error && !cases.error && notices.length > 8 && (
            <p className="notification-empty">
              Showing the latest 8 of {notices.length} notifications.
            </p>
          )}
          <NavLink
            className="notification-footer"
            to="/audit"
            onClick={() => setOpen(false)}
          >
            Review audit trail
          </NavLink>
        </section>
      )}
    </div>
  );
}
export default function App() {
  const [session, setSession] = useState<Session | null>(null);
  const [switching, setSwitching] = useState(false);
  const [sidebar, setSidebar] = useState(false);
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false);
  const [userMenu, setUserMenu] = useState(false);
  const userMenuTrigger = useRef<HTMLButtonElement>(null);
  const [isMobile, setIsMobile] = useState(
    () => window.matchMedia("(max-width: 760px)").matches,
  );
  useEffect(() => {
    const media = window.matchMedia("(max-width: 760px)");
    const update = () => {
      setIsMobile(media.matches);
      if (!media.matches) setSidebar(false);
    };
    media.addEventListener("change", update);
    return () => media.removeEventListener("change", update);
  }, []);
  const location = useLocation();
  const navigationType = useNavigationType();
  const previousPage = useRef({ pathname: location.pathname, username: session?.username });
  useEffect(() => {
    const previous = previousPage.current;
    const userChanged = previous.username !== session?.username;
    const pageChanged = previous.pathname !== location.pathname;
    previousPage.current = { pathname: location.pathname, username: session?.username };
    if (!session || (!userChanged && (!pageChanged || navigationType === "POP"))) return;
    window.scrollTo({ top: 0, left: 0, behavior: "instant" });
    document.getElementById("main-content")?.focus({ preventScroll: true });
  }, [location.pathname, session?.username, navigationType]);
  useEffect(() => {
    applyUiPreferences();
    const update = () => applyUiPreferences();
    window.addEventListener("afi-preferences-changed", update);
    const systemTheme = window.matchMedia("(prefers-color-scheme: dark)");
    systemTheme.addEventListener("change", update);
    return () => {
      window.removeEventListener("afi-preferences-changed", update);
      systemTheme.removeEventListener("change", update);
    };
  }, []);
  if (!session) return <Login onSuccess={setSession} />;
  const navigation = navigationGroups.flatMap((group) => group.items);
  const navigationPath = aliases[location.pathname] || location.pathname;
  const current = navigation.find((n) =>
    n.to === "/" ? navigationPath === "/" : navigationPath.startsWith(n.to),
  );
  const currentLabel = location.pathname.startsWith("/legacy")
    ? "Legacy recovery"
    : location.pathname === "/demo"
      ? "Demo environment"
      : location.pathname === "/settings"
        ? "Settings"
        : location.pathname.startsWith("/exceptions/")
          ? "Case details"
          : current?.label || "Workspace";
  const navigationContent = (
    <>
      <div className="sidebar-brand">
        <Brand compact />
        <button
          className="icon-button mobile-close"
          aria-label="Close menu"
          onClick={() => setSidebar(false)}
        >
          <X size={20} />
        </button>
      </div>
      <nav aria-label="Main navigation" className="grouped-navigation">
        {navigationGroups.map((group) => (
          <div className="nav-section" key={group.title}>
            {group.title && <p className="nav-group-label">{group.title}</p>}
            {group.items.map((item) => (
              <NavLink
                key={item.to}
                end={item.to === "/"}
                to={item.to}
                title={item.label}
                onClick={() => setSidebar(false)}
                className={({ isActive }) =>
                  `nav-item ${isActive || aliases[location.pathname] === item.to ? "active" : ""}`
                }
              >
                <item.icon size={18} />
                <span>{item.label}</span>
              </NavLink>
            ))}
          </div>
        ))}
      </nav>
      <div className="sidebar-bottom">
        <NavLink
          to="/demo"
          className="nav-item"
          onClick={() => setSidebar(false)}
        >
          <Activity size={17} />
          <span>Demo environment</span>
        </NavLink>
        <p className="sidebar-version">AFI Resolve 2.0</p>
      </div>
    </>
  );
  return (
    <AuthContext.Provider
      value={{
        session,
        switchUser: () => {
          setSwitching(true);
          setUserMenu(false);
        },
      }}
    >
      <div
        className={`app-shell ${sidebarCollapsed ? "sidebar-collapsed" : ""}`}
        onKeyDown={(event) => {
          if (event.key === "Escape" && userMenu) {
            event.preventDefault();
            event.stopPropagation();
            setUserMenu(false);
            userMenuTrigger.current?.focus();
          }
        }}
      >
        <a className="skip-link" href="#main-content">
          Skip to content
        </a>
        {isMobile ? (
          <Dialog
            open={sidebar}
            id="workspace-navigation"
            className="sidebar sidebar-open navigation-dialog"
            label="Workspace navigation"
            onClose={() => setSidebar(false)}
          >
            {navigationContent}
          </Dialog>
        ) : (
          <aside id="workspace-navigation" className="sidebar">
            {navigationContent}
          </aside>
        )}
        <div className="main-wrapper">
          <header className="topbar">
            <div className="topbar-left">
              <button
                className="icon-button desktop-sidebar-toggle"
                aria-label={
                  sidebarCollapsed ? "Expand sidebar" : "Collapse sidebar"
                }
                onClick={() => setSidebarCollapsed(!sidebarCollapsed)}
                title={sidebarCollapsed ? "Expand sidebar" : "Collapse sidebar"}
              >
                {sidebarCollapsed ? (
                  <PanelLeftOpen size={18} />
                ) : (
                  <PanelLeftClose size={18} />
                )}
              </button>
              <div className="breadcrumb">
                <button
                  className="icon-button mobile-menu"
                  onClick={() => setSidebar(true)}
                  aria-label="Open navigation"
                  aria-expanded={sidebar}
                  aria-controls="workspace-navigation"
                >
                  <Menu size={20} />
                </button>
                <span>Resolve</span>
                <span className="breadcrumb-divider">/</span>
                <strong>{currentLabel}</strong>
              </div>
            </div>
            <GlobalSearch key={session.username} />
            <div className="topbar-right">
              <span className="demo-tag">
                <span />
                Synthetic demo
              </span>
              <span className="topbar-divider" />
              <NotificationCenter key={session.username} />
              <div
                className="user-menu"
                onBlur={(event) => {
                  if (
                    !event.currentTarget.contains(
                      event.relatedTarget as Node | null,
                    )
                  )
                    setUserMenu(false);
                }}
              >
                <button
                  className="user-button"
                  ref={userMenuTrigger}
                  data-account-menu-trigger
                  aria-label="Resolution Management account menu"
                  onClick={() => setUserMenu(!userMenu)}
                  aria-expanded={userMenu}
                >
                  <span className="avatar">
                    RM
                  </span>
                  <span className="user-info">
                    <strong>Resolution Management</strong>
                    <small>
                      {"Service operations"}
                    </small>
                  </span>
                  <ChevronDown size={14} />
                </button>
                {userMenu && (
                  <div className="user-popover">
                    <NavLink
                      className="user-popover-link"
                      to="/settings?section=general"
                      onClick={() => setUserMenu(false)}
                    >
                      <Activity size={16} />
                      My profile
                    </NavLink>
                    <NavLink
                      className="user-popover-link"
                      to="/settings?section=appearance"
                      onClick={() => setUserMenu(false)}
                    >
                      <Settings2 size={16} />
                      Preferences
                    </NavLink>
                    <NavLink
                      className="user-popover-link"
                      to="/settings"
                      onClick={() => setUserMenu(false)}
                    >
                      <Settings2 size={16} />
                      Settings
                    </NavLink>
                    <NavLink
                      className="user-popover-link"
                      to="/settings?section=about"
                      onClick={() => setUserMenu(false)}
                    >
                      <CircleHelp size={16} />
                      About AFI Resolve
                    </NavLink>
                    <button
                      onClick={() => {
                        setSwitching(true);
                        setUserMenu(false);
                      }}
                    >
                      <Activity size={16} />
                      Switch account
                    </button>
                    <button
                      onClick={() => {
                        clearSession();
                        setSession(null);
                        setUserMenu(false);
                      }}
                    >
                      <LogOut size={16} />
                      Sign out
                    </button>
                  </div>
                )}
              </div>
            </div>
          </header>
          <main
            id="main-content"
            tabIndex={-1}
            className="main-content"
            key={session.username}
          >
            <Suspense fallback={<Loading text="Opening workspace page..." />}>
              <Routes>
                <Route path="/" element={<DocumentOverview />} />
                <Route path="/submissions" element={<DocumentQueue />} />
                <Route path="/new" element={<Navigate to="/submissions" replace />} />
                <Route
                  path="/submissions/:id"
                  element={<DocumentWorkspace />}
                />
                <Route
                  path="/investigations"
                  element={<DocumentQueue mode="investigations" />}
                />
                <Route
                  path="/exceptions"
                  element={<DocumentQueue mode="exceptions" />}
                />
                <Route
                  path="/corrections"
                  element={<DocumentQueue mode="corrections" />}
                />
                <Route
                  path="/preflight"
                  element={<DocumentQueue mode="preflight" />}
                />
                <Route
                  path="/operations"
                  element={<DocumentQueue mode="operations" />}
                />
                <Route path="/reports" element={<DocumentReports />} />
                <Route path="/audit" element={<DocumentAudit />} />
                <Route path="/demo" element={<DocumentDemo />} />
                <Route path="/settings" element={<SettingsWorkspace />} />
                <Route
                  path="/legacy"
                  element={
                    <>
                      <LegacyNavigation />
                      <Overview />
                    </>
                  }
                />
                <Route
                  path="/legacy/exceptions"
                  element={
                    <>
                      <LegacyNavigation />
                      <Exceptions />
                    </>
                  }
                />
                <Route
                  path="/legacy/exceptions/:id"
                  element={
                    <>
                      <LegacyNavigation />
                      <ExceptionDetail />
                    </>
                  }
                />
                <Route
                  path="/legacy/investigations"
                  element={
                    <>
                      <LegacyNavigation />
                      <Investigations />
                    </>
                  }
                />
                <Route
                  path="/legacy/reports"
                  element={
                    <>
                      <LegacyNavigation />
                      <ReportsPage />
                    </>
                  }
                />
                <Route
                  path="/legacy/twin"
                  element={
                    <>
                      <LegacyNavigation />
                      <Twin />
                    </>
                  }
                />
                <Route
                  path="/legacy/resolutions"
                  element={
                    <>
                      <LegacyNavigation />
                      <ResolutionPage />
                    </>
                  }
                />
                <Route
                  path="/legacy/tools"
                  element={
                    <>
                      <LegacyNavigation />
                      <LegacySettingsPage demoOnly />
                    </>
                  }
                />
                <Route
                  path="/legacy/audit"
                  element={
                    <>
                      <LegacyNavigation />
                      <AuditPage />
                    </>
                  }
                />
                <Route
                  path="*"
                  element={
                    <Empty
                      title="Page not found"
                      description="Choose a workspace page from the navigation."
                    />
                  }
                />
              </Routes>
            </Suspense>
          </main>
          <footer className="app-footer">
            <span>
              <ShieldCheck size={13} /> Synthetic records only · No real
              financial transactions
            </span>
            <span>Team AFI · Ignite Challenge 2026</span>
          </footer>
        </div>
      </div>
      {switching && (
        <Login
          modal
          onClose={() => setSwitching(false)}
          onSuccess={(next) => {
            setSession(next);
            setSwitching(false);
          }}
        />
      )}
    </AuthContext.Provider>
  );
}

function LegacyNavigation() {
  return (
    <div className="doc-legacy-nav">
      <strong>Legacy transaction recovery</strong>
      {[
        ["/legacy", "Overview"],
        ["/legacy/exceptions", "Exceptions"],
        ["/legacy/investigations", "Investigations"],
        ["/legacy/twin", "Digital twin"],
        ["/legacy/resolutions", "Approvals"],
        ["/legacy/audit", "Audit"],
        ["/legacy/reports", "Reports"],
        ["/legacy/tools", "Demo controls"],
      ].map(([to, title]) => (
        <NavLink key={to} to={to}>
          {title}
        </NavLink>
      ))}
    </div>
  );
}
