import {
  lazy,
  Suspense,
  useEffect,
  useRef,
  useState,
} from "react";
import {
  Navigate,
  NavLink,
  Route,
  Routes,
  useLocation,
  useNavigationType,
} from "react-router-dom";
import {
  Activity,
  Boxes,
  ChevronDown,
  CircleHelp,
  FileBarChart2,
  LayoutDashboard,
  ListFilter,
  LogOut,
  Menu,
  PanelLeftClose,
  PanelLeftOpen,
  Settings2,
  X,
} from "lucide-react";
import { AuthContext, Brand, Login } from "./auth";
import { clearSession } from "./api";
import type { Session } from "./api";
import { Empty, Loading } from "./components";
import { applyUiPreferences } from "./preferences";
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
import { AddressRequests } from "./pages/AddressRequests";
import { SettingsWorkspace } from "./pages/Settings";
import { Dialog } from "./ui";
const navigationGroups = [{
  title: "",
  items: [
    { to: "/", label: "Overview", icon: LayoutDashboard },
    { to: "/submissions", label: "Cases", icon: Boxes },
    { to: "/exceptions", label: "Requires Attention", icon: ListFilter },
    { to: "/reports", label: "Reports & Audit", icon: FileBarChart2 },
    { to: "/settings", label: "Settings", icon: Settings2 },
  ],
}];
const aliases: Record<string, string> = {
  "/new": "/submissions",
  "/investigations": "/submissions",
  "/corrections": "/submissions",
  "/preflight": "/submissions",
  "/audit": "/reports",
};

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
  const previousPage = useRef({
    pathname: location.pathname,
    username: session?.username,
  });
  useEffect(() => {
    const previous = previousPage.current;
    const userChanged = previous.username !== session?.username;
    const pageChanged = previous.pathname !== location.pathname;
    previousPage.current = {
      pathname: location.pathname,
      username: session?.username,
    };
    if (
      !session ||
      (!userChanged && (!pageChanged || navigationType === "POP"))
    )
      return;
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
                <strong>{currentLabel}</strong>
              </div>
            </div>
            <div className="topbar-right">
              <span className="demo-tag">
                <span />
                Demo
              </span>
              <span className="topbar-divider" />
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
                  <span className="avatar">RM</span>
                  <span className="user-info">
                    <strong>{session.username}</strong>
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
                <Route path="/address-requests" element={<AddressRequests />} />
                <Route path="/" element={<DocumentOverview />} />
                <Route path="/submissions" element={<DocumentQueue />} />
                <Route
                  path="/new"
                  element={<Navigate to="/submissions" replace />}
                />
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
