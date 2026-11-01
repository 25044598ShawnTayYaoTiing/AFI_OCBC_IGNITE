export interface UiPreferences {
  theme: "light" | "dark" | "system";
  density: "comfortable" | "compact";
  dateFormat: "d-mmm-yyyy" | "yyyy-mm-dd";
  timeFormat: "24h" | "12h";
  pageSize: 10 | 20 | 50;
  defaultRange: "7" | "30" | "90" | "all";
}

export interface NotificationPreferences {
  critical: boolean;
  approvals: boolean;
  failed: boolean;
  verification: boolean;
}

const UI_KEY = "afi-resolve-ui-preferences";
const NOTIFICATION_KEY = "afi-resolve-notification-preferences";

export const defaultUiPreferences: UiPreferences = {
  theme: "light",
  density: "comfortable",
  dateFormat: "d-mmm-yyyy",
  timeFormat: "24h",
  pageSize: 10,
  defaultRange: "7",
};

export const defaultNotificationPreferences: NotificationPreferences = {
  critical: true,
  approvals: true,
  failed: true,
  verification: true,
};

function readStored<T>(key: string, fallback: T): T {
  try {
    const value = localStorage.getItem(key);
    return value ? { ...fallback, ...JSON.parse(value) } : fallback;
  } catch {
    return fallback;
  }
}

export function readUiPreferences(): UiPreferences {
  return readStored(UI_KEY, defaultUiPreferences);
}

export function saveUiPreferences(value: UiPreferences) {
  localStorage.setItem(UI_KEY, JSON.stringify(value));
  applyUiPreferences();
  window.dispatchEvent(new Event("afi-preferences-changed"));
}

export function readNotificationPreferences(): NotificationPreferences {
  return readStored(NOTIFICATION_KEY, defaultNotificationPreferences);
}

export function saveNotificationPreferences(value: NotificationPreferences) {
  localStorage.setItem(NOTIFICATION_KEY, JSON.stringify(value));
  window.dispatchEvent(new Event("afi-preferences-changed"));
}

export function applyUiPreferences() {
  const preferences = readUiPreferences();
  const dark = preferences.theme === "dark" ||
    (preferences.theme === "system" && window.matchMedia("(prefers-color-scheme: dark)").matches);
  document.documentElement.dataset.theme = dark ? "dark" : "light";
  document.documentElement.dataset.density = preferences.density;
}
