import { lazy, Suspense, useEffect, useMemo, useState } from "react";
import { NavLink, Route, Routes, useLocation } from "react-router-dom";
import {
  Activity,
  Bell,
  Boxes,
  ChevronDown,
  CircleHelp,
  ClipboardCheck,
  FileBarChart2,
  FileClock,
  GitBranch,
  LayoutDashboard,
  ListFilter,
  LogOut,
  Menu,
  PanelLeftClose,
  PanelLeftOpen,
  Search,
  Settings2,
  ShieldCheck,
  Sparkles,
  X,
} from "lucide-react";
import { AuthContext, Brand, Login, isReviewer } from "./auth";
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
  import("./pages/Operations").then((pages) => ({ default: pages.ReportsPage })),
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
const SettingsPage = lazy(() =>
  import("./pages/Workflows").then((pages) => ({
    default: pages.SettingsPage,
  })),
);
import { DocumentOverview, DocumentQueue, NewDocumentSubmission, DocumentWorkspace, DocumentReports, DocumentAudit, DocumentDemo, ValidationRules } from "./pages/Documents";
import type { Submission } from "./pages/Documents";
const navigationGroups = [
  { title: "Overview", items: [{ to: "/", label: "Operations Overview", icon: LayoutDashboard }] },
  { title: "Document Operations", items: [
    { to: "/submissions", label: "Document Submissions", icon: Boxes },
    { to: "/new", label: "New Submission", icon: ClipboardCheck },
    { to: "/investigations", label: "Validation & Investigations", icon: Sparkles },
    { to: "/exceptions", label: "Exception Queue", icon: ListFilter },
    { to: "/corrections", label: "Corrections & Resubmissions", icon: FileClock },
    { to: "/preflight", label: "Preflight Simulator", icon: ShieldCheck },
    { to: "/operations", label: "Operations Review", icon: ClipboardCheck },
  ]},
  { title: "Insights", items: [
    { to: "/reports", label: "Reports & Analytics", icon: FileBarChart2 },
    { to: "/audit", label: "Audit Trail", icon: FileClock },
  ]},
  { title: "Administration", items: [{ to: "/settings", label: "Settings", icon: Settings2 }] },
  { title: "Demonstration", items: [
    { to: "/demo", label: "Demo Environment", icon: Activity },
    { to: "/legacy", label: "Legacy Recovery Demo", icon: GitBranch },
  ]},
];

function GlobalSearch() {
  const [query, setQuery] = useState("");
  const [search, setSearch] = useState("");
  const [open, setOpen] = useState(false);
  const location = useLocation();
  useEffect(() => setOpen(false), [location.pathname]);
  useEffect(() => {
    const timer = window.setTimeout(() => setSearch(query.trim()), 250);
    return () => window.clearTimeout(timer);
  }, [query]);
  const results = useResource<Submission[]>("/api/documents/submissions", 15000);
  const matches = (results.data || []).filter(c => `${c.customer_name} ${c.customer_reference} ${c.id}`.toLowerCase().includes(search.toLowerCase())).slice(0,5);
  return (
    <div className="global-search" onBlur={(event) => { if (!event.currentTarget.contains(event.relatedTarget as Node | null)) setOpen(false); }}>
      <Search size={16} />
      <input
        aria-label="Search document submissions"
        placeholder="Search customer submissions"
        value={query}
        onChange={(event) => {
          setQuery(event.target.value);
          setOpen(true);
        }}
        onFocus={() => setOpen(true)}
        onKeyDown={(event) => {
          if (event.key === "Escape") setOpen(false);
        }}
      />
      {query && <button aria-label="Clear search" onClick={() => setQuery("")}><X size={14} /></button>}
      {open && query.trim() && (
        <div className="search-results" role="listbox" aria-label="Matching cases">
          {results.loading ? <Loading text="Searching cases…" /> : results.error ? (
            <ErrorBox message={results.error} />
          ) : matches.length ? matches.map((item) => (
            <NavLink key={item.id} to={`/submissions/${item.id}`} onClick={() => setOpen(false)}>
              <span className="search-result-main"><strong>{item.customer_name}</strong><small>{item.customer_reference} · {label(item.status)}</small></span>
              
            </NavLink>
          )) : <p className="search-empty">No matching cases</p>}
        </div>
      )}
    </div>
  );
}

function NotificationCenter() {
  const [open, setOpen] = useState(false);
  const activity = useResource<Audit[]>("/api/documents/audit", 30000);
  const cases = useResource<Submission[]>("/api/documents/submissions", 30000);
  const preferences = readNotificationPreferences();
  const notices = useMemo(() => {
    if (!preferences.critical && !preferences.approvals) return [];
    return (cases.data || []).filter(c => c.status === 'CORRECTION_REQUIRED' || c.status === 'HUMAN_REVIEW').slice(0,8).map(c => ({
      id: c.id, title: label(c.status), detail: c.customer_name, to: `/submissions/${c.id}`, time: c.created_at, tone: 'pending'
    }));
  }, [cases.data, preferences.critical, preferences.approvals]);
  return (
    <div className="notification-menu">
      <button className="icon-button notification-button" aria-label={`Notifications${notices.length ? `, ${notices.length} requiring attention` : ""}`} aria-expanded={open} onClick={() => setOpen(!open)}>
        <Bell size={18} />{notices.length > 0 && <span className="notification-count">{notices.length > 9 ? "9+" : notices.length}</span>}
      </button>
      {open && <section className="notification-popover" aria-label="Notifications">
        <header><div><strong>Notifications</strong><small>From current case and audit data</small></div><button className="icon-button" aria-label="Close notifications" onClick={() => setOpen(false)}><X size={16} /></button></header>
        {activity.error && <ErrorBox message={activity.error} retry={() => void activity.refresh()} />}
        {activity.loading && cases.loading ? <Loading text="Loading notifications…" /> : notices.length ? notices.map((notice) => (
          <NavLink key={notice.id} to={notice.to} onClick={() => setOpen(false)} className="notification-item">
            <span className={`notification-marker ${notice.tone}`} />
            <span><strong>{notice.title}</strong><small>{notice.detail}</small></span>
            {notice.time && <time>{dateTime(notice.time)}</time>}
          </NavLink>
        )) : <p className="notification-empty">No priority notifications from the current data.</p>}
        <NavLink className="notification-footer" to="/audit" onClick={() => setOpen(false)}>Review audit trail</NavLink>
      </section>}
    </div>
  );
}
export default function App() {
  const [session, setSession] = useState<Session | null>(null);
  const [switching, setSwitching] = useState(false);
  const [sidebar, setSidebar] = useState(false);
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false);
  const [userMenu, setUserMenu] = useState(false);
  const location = useLocation();
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
  const current = navigation.find((n) => n.to === "/" ? location.pathname === "/" : location.pathname.startsWith(n.to));
  const currentLabel = location.pathname === "/settings" ? "Settings" : location.pathname.startsWith("/exceptions/") ? "Case details" : current?.label || "Workspace";
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
      <div className={`app-shell ${sidebarCollapsed ? "sidebar-collapsed" : ""}`}>
        <a className="skip-link" href="#main-content">
          Skip to content
        </a>
        {sidebar && (
          <button
            className="sidebar-scrim"
            aria-label="Close navigation"
            onClick={() => setSidebar(false)}
          />
        )}
        <aside className={`sidebar ${sidebar ? "sidebar-open" : ""}`}>
          <div className="sidebar-brand">
            <Brand />
            <button
              className="icon-button mobile-close"
              aria-label="Close menu"
              onClick={() => setSidebar(false)}
            >
              <X size={20} />
            </button>
          </div>
          <div className="workspace-label">
            <span className="workspace-icon">
              <GitBranch size={16} />
            </span>
            <div>
              <strong>AFI Operations</strong>
              <small>Team AFI · Synthetic</small>
            </div>
            <ChevronDown size={14} />
          </div>
          <nav aria-label="Main navigation" className="grouped-navigation">
            {navigationGroups.map((group) => (
              <div className="nav-section" key={group.title}>
                <p className="nav-group-label">{group.title}</p>
                {group.items.map((item) => (
                  <NavLink key={item.to} end={item.to === "/"} to={item.to} title={item.label} onClick={() => setSidebar(false)} className={({ isActive }) => `nav-item ${isActive ? "active" : ""}`}>
                    <item.icon size={18} /><span>{item.label}</span>
                  </NavLink>
                ))}
              </div>
            ))}
          </nav>
          <div className="sidebar-bottom">
            <div className="sidebar-version">
              <Activity size={13} /><span>AFI Resolve</span><span>v2.0</span>
            </div>
          </div>
        </aside>
        <div className="main-wrapper">
          <header className="topbar">
            <div className="topbar-left">
              <button className="icon-button desktop-sidebar-toggle" aria-label={sidebarCollapsed ? "Expand sidebar" : "Collapse sidebar"} onClick={() => setSidebarCollapsed(!sidebarCollapsed)} title={sidebarCollapsed ? "Expand sidebar" : "Collapse sidebar"}>
                {sidebarCollapsed ? <PanelLeftOpen size={18} /> : <PanelLeftClose size={18} />}
              </button>
              <div className="breadcrumb">
                <button className="icon-button mobile-menu" onClick={() => setSidebar(true)} aria-label="Open navigation"><Menu size={20} /></button>
                <span>Workspace</span><span className="breadcrumb-divider">/</span><strong>{currentLabel}</strong>
              </div>
            </div>
            <div className="topbar-right">
              <GlobalSearch key={session.username} />
              <span className="demo-tag">
                <span />
                Synthetic demo
              </span>
              <span className="topbar-divider" />
              <NotificationCenter key={session.username} />
              <div className="user-menu">
                <button
                  className="user-button"
                  onClick={() => setUserMenu(!userMenu)}
                  aria-expanded={userMenu}
                >
                  <span className="avatar">
                    {session.username.slice(0, 2).toUpperCase()}
                  </span>
                  <span className="user-info">
                    <strong>{session.username}</strong>
                    <small>
                      {isReviewer(session) ? "Reviewer" : "Relationship manager"}
                    </small>
                  </span>
                  <ChevronDown size={14} />
                </button>
                {userMenu && (
                  <div className="user-popover">
                    <NavLink className="user-popover-link" to="/settings#profile" onClick={() => setUserMenu(false)}><Activity size={16} />My profile</NavLink>
                    <NavLink className="user-popover-link" to="/settings#preferences" onClick={() => setUserMenu(false)}><Settings2 size={16} />Preferences</NavLink>
                    <NavLink className="user-popover-link" to="/settings" onClick={() => setUserMenu(false)}><Settings2 size={16} />Settings</NavLink>
                    <NavLink className="user-popover-link" to="/settings#about" onClick={() => setUserMenu(false)}><CircleHelp size={16} />About AFI Resolve</NavLink>
                    <button onClick={() => { setSwitching(true); setUserMenu(false); }}><Activity size={16} />Switch account</button>
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
            className="main-content"
            key={session.username}
          >
            <Suspense fallback={<Loading text="Opening workspace page..." />}>
              <Routes>
                <Route path="/" element={<DocumentOverview />} />
                <Route path="/submissions" element={<DocumentQueue />} />
                <Route path="/new" element={<NewDocumentSubmission />} />
                <Route path="/submissions/:id" element={<DocumentWorkspace />} />
                <Route path="/investigations" element={<DocumentQueue mode="investigations" />} />
                <Route path="/exceptions" element={<DocumentQueue mode="exceptions" />} />
                <Route path="/corrections" element={<DocumentQueue mode="corrections" />} />
                <Route path="/preflight" element={<DocumentQueue mode="preflight" />} />
                <Route path="/operations" element={<DocumentQueue mode="operations" />} />
                <Route path="/reports" element={<DocumentReports />} />
                <Route path="/audit" element={<DocumentAudit />} />
                <Route path="/demo" element={<DocumentDemo />} />
                <Route path="/settings" element={<><ValidationRules /><SettingsPage /></>} />
                <Route path="/legacy" element={<><LegacyNavigation /><Overview /></>} />
                <Route path="/legacy/exceptions" element={<><LegacyNavigation /><Exceptions /></>} />
                <Route path="/legacy/exceptions/:id" element={<><LegacyNavigation /><ExceptionDetail /></>} />
                <Route path="/legacy/investigations" element={<><LegacyNavigation /><Investigations /></>} />
                <Route path="/legacy/reports" element={<><LegacyNavigation /><ReportsPage /></>} />
                <Route path="/legacy/twin" element={<><LegacyNavigation /><Twin /></>} />
                <Route path="/legacy/resolutions" element={<><LegacyNavigation /><ResolutionPage /></>} />
                <Route path="/legacy/audit" element={<><LegacyNavigation /><AuditPage /></>} />
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

function LegacyNavigation() {return <div className="doc-legacy-nav"><strong>Legacy transaction recovery</strong>{[['/legacy','Overview'],['/legacy/exceptions','Exceptions'],['/legacy/investigations','Investigations'],['/legacy/twin','Digital twin'],['/legacy/resolutions','Approvals'],['/legacy/audit','Audit'],['/legacy/reports','Reports']].map(([to,title])=><NavLink key={to} to={to}>{title}</NavLink>)}</div>;}
