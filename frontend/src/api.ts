import { useCallback, useEffect, useRef, useState } from "react";

import { readUiPreferences } from "./preferences";

export interface Session {
  username: string;
  roles: string[];
  demoMode: boolean;
}
export interface Audit {
  id: string;
  exceptionId: string | null;
  timestamp: string;
  actor: string;
  eventType: string;
  details: unknown;
  result: string;
  correlationId: string | null;
}
export interface Evidence {
  totalRecords: number;
  processedRecords: number;
  pendingRecords: number;
  duplicateRecords: number;
  reconciliationMismatches: number;
  failureReason: string;
  sourceHash: string;
  sampleRecords: Record<string, unknown>[];
}
export interface Case {
  id: string;
  type: string;
  batchId: string;
  severity: string;
  status: string;
  description: string;
  detectedAt: string;
  resolvedAt?: string;
  workflow: string;
  evidence?: Evidence;
}
export interface Page<T> {
  items: T[];
  total: number;
  page: number;
  size: number;
}
export interface Summary {
  totalExceptions: number;
  activeExceptions: number;
  resolvedExceptions: number;
  awaitingApproval: number;
  autoResolutionRate: number;
  averageResolutionSeconds: number;
  categories: { name: string; value: number }[];
  statuses: { name: string; value: number }[];
  trends: { date: string; detected: number; resolved: number }[];
  automatedAttempts: { successful: number; failed: number };
}
export interface Investigation {
  id: string;
  exceptionId: string;
  probableRootCause: string;
  evidence: string[];
  candidateActions: string[];
  requiresAdditionalEvidence: boolean;
  explanation: string;
  method: string;
  completedAt: string;
  fallbackReason?: string;
}
export interface Simulation {
  id: string;
  exceptionId: string;
  strategy: string;
  sourceHash: string;
  safe: boolean;
  totalRecords: number;
  alreadyProcessed: number;
  pendingRecords: number;
  affectedRecords: number;
  expectedSuccessfulRecords: number;
  duplicateAttemptRisk: number;
  policyViolations: string[];
  decision: string;
  explanation: string;
  createdAt: string;
}
export interface Verification {
  passed: boolean;
  totalRecords: number;
  processedRecords: number;
  pendingRecords: number;
  duplicateProcessingAttempts: number;
  reconciliationIntegrity: boolean;
  checks: { name: string; passed: boolean; detail: string }[];
}
export interface Resolution {
  id: string;
  exceptionId: string;
  actionType: string;
  status: string;
  idempotencyKey: string;
  executedAt: string;
  verification: Verification | null;
}

let credentials = "";
export function clearSession() {
  credentials = "";
}
export class ApiError extends Error {
  status: number;
  constructor(message: string, status: number) {
    super(message);
    this.status = status;
  }
}
export async function api<T>(
  path: string,
  options: RequestInit = {},
): Promise<T> {
  let response: Response;
  try {
    response = await fetch(path, {
      ...options,
      headers: {
        "Content-Type": "application/json",
        ...(credentials ? { Authorization: `Basic ${credentials}` } : {}),
        ...options.headers,
      },
    });
  } catch {
    throw new ApiError(
      "Cannot reach the API. Check that the backend is running, then try again.",
      0,
    );
  }
  if (!response.ok) {
    let problem: { message?: string; error?: string } = {};
    try {
      problem = await response.json();
    } catch {
      /* Empty upstream response */
    }
    throw new ApiError(
      problem.message ||
        problem.error ||
        (response.status === 401
          ? "Incorrect credentials or session expired. Please sign in again."
          : `Request failed (${response.status}). Please try again.`),
      response.status,
    );
  }
  if (response.status === 204) return null as T;
  return response.json();
}
export async function publicApi<T>(path: string): Promise<T> {
  let response: Response;
  try {
    response = await fetch(path, { headers: { Accept: "application/json" } });
  } catch {
    throw new ApiError("The public health endpoint is unavailable.", 0);
  }
  if (!response.ok) throw new ApiError(`Health check failed (${response.status}).`, response.status);
  return response.json();
}
export async function authenticate(
  username: string,
  password: string,
): Promise<Session> {
  const token = btoa(`${username}:${password}`);
  const session = await api<Session>("/api/auth/me", {
    headers: { Authorization: `Basic ${token}` },
  });
  credentials = token;
  return session;
}
export function post<T>(path: string, body?: unknown): Promise<T> {
  return api<T>(path, {
    method: "POST",
    body: body === undefined ? undefined : JSON.stringify(body),
  });
}
export async function privatePdf(path: string): Promise<Blob> {
  const response = await fetch(path, { headers: credentials ? { Authorization: `Basic ${credentials}` } : {} });
  if (!response.ok) throw new Error(`Document access failed (${response.status})`);
  return response.blob();
}
export function uploadPdfs<T>(path: string, files: File[], progress: (value: number) => void): Promise<T> {
  return new Promise((resolve, reject) => {
    const request = new XMLHttpRequest();
    request.open('POST', path);
    if (credentials) request.setRequestHeader('Authorization', `Basic ${credentials}`);
    request.upload.onprogress = e => { if (e.lengthComputable) progress(Math.round(e.loaded / e.total * 100)); };
    request.onerror = () => reject(new Error('Upload failed. Check the backend connection.'));
    request.onload = () => {
      try {
        const result = JSON.parse(request.responseText);
        if (request.status >= 200 && request.status < 300) resolve(result);
        else reject(new Error(result.message || 'Upload failed'));
      } catch { reject(new Error('Upload failed. Check the PDF size and service availability.')); }
    };
    const form = new FormData(); files.forEach(file => form.append('files', file)); request.send(form);
  });
}
export function useResource<T>(path: string | null, refreshMs = 0) {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(true);
  const requestId = useRef(0);
  const refresh = useCallback(async () => {
    if (!path) {
      setData(null);
      setLoading(false);
      return;
    }
    const current = ++requestId.current;
    setError("");
    try {
      const next = await api<T>(path);
      if (current === requestId.current) setData(next);
    } catch (e) {
      if (current === requestId.current)
        setError(e instanceof Error ? e.message : "Something went wrong");
    } finally {
      if (current === requestId.current) setLoading(false);
    }
  }, [path]);
  useEffect(() => {
    setData(null);
    setLoading(Boolean(path));
    void refresh();
    const timer = refreshMs
      ? window.setInterval(refresh, refreshMs)
      : undefined;
    return () => {
      requestId.current++;
      window.clearInterval(timer);
    };
  }, [refresh, refreshMs]);
  return { data, error, loading, refresh };
}
export const formatNumber = (n: number | undefined | null) =>
  n == null
    ? "—"
    : new Intl.NumberFormat("en-SG", { maximumFractionDigits: 1 }).format(n);
export const label = (text: string | undefined | null) =>
  text
    ? text
        .toLowerCase()
        .replaceAll("_", " ")
        .replace(/\b\w/g, (c) => c.toUpperCase())
    : "—";
export function dateTime(value: string | undefined | null) {
  if (!value) return "—";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  const preferences = readUiPreferences();
  const datePart = new Intl.DateTimeFormat(
    preferences.dateFormat === "yyyy-mm-dd" ? "en-CA" : "en-SG",
    preferences.dateFormat === "yyyy-mm-dd"
      ? { year: "numeric", month: "2-digit", day: "2-digit", timeZone: "Asia/Singapore" }
      : { day: "2-digit", month: "short", year: "numeric", timeZone: "Asia/Singapore" },
  ).format(date);
  const timePart = new Intl.DateTimeFormat("en-SG", {
    hour: "2-digit",
    minute: "2-digit",
    hourCycle: preferences.timeFormat === "24h" ? "h23" : "h12",
    timeZone: "Asia/Singapore",
  }).format(date);
  return `${datePart}, ${timePart}`;
}
export function dateKey(value: string | undefined | null) {
  if (!value) return "";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "";
  const parts = new Intl.DateTimeFormat("en-GB", {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    timeZone: "Asia/Singapore",
  }).formatToParts(date);
  const part = (type: Intl.DateTimeFormatPartTypes) => parts.find((item) => item.type === type)?.value || "";
  return `${part("year")}-${part("month")}-${part("day")}`;
}
export function detailText(value: unknown): string {
  let detail = value;
  if (typeof detail === "string") {
    const original = detail;
    const trimmed = detail.trim();
    if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
      try {
        detail = JSON.parse(trimmed);
      } catch {
        return original;
      }
    } else {
      return detail;
    }
  }
  if (detail == null) return "";
  if (Array.isArray(detail)) return `${detail.length} recorded items`;
  if (typeof detail === "object") {
    const fields = detail as Record<string, unknown>;
    if (
      typeof fields.passed === "boolean" &&
      ("totalRecords" in fields || Array.isArray(fields.checks))
    ) {
      const parts = [`Verification ${fields.passed ? "passed" : "failed"}`];
      if (typeof fields.processedRecords === "number")
        parts.push(`${formatNumber(fields.processedRecords)} processed`);
      if (typeof fields.pendingRecords === "number")
        parts.push(`${formatNumber(fields.pendingRecords)} pending`);
      if (typeof fields.duplicateProcessingAttempts === "number")
        parts.push(
          `${formatNumber(fields.duplicateProcessingAttempts)} duplicate attempts`,
        );
      if (Array.isArray(fields.checks)) {
        const passedChecks = fields.checks.filter(
          (check) =>
            !!check &&
            typeof check === "object" &&
            "passed" in check &&
            check.passed === true,
        ).length;
        parts.push(`${passedChecks}/${fields.checks.length} checks passed`);
      }
      if (typeof fields.reconciliationIntegrity === "boolean")
        parts.push(
          `reconciliation ${fields.reconciliationIntegrity ? "passed" : "failed"}`,
        );
      return parts.join(" · ");
    }
    if ("sampleRecords" in fields && "totalRecords" in fields) {
      const parts: string[] = [];
      for (const [key, title] of [
        ["totalRecords", "total"],
        ["processedRecords", "processed"],
        ["pendingRecords", "pending"],
        ["duplicateRecords", "duplicates"],
        ["reconciliationMismatches", "mismatches"],
      ] as const) {
        const count = fields[key];
        if (typeof count === "number")
          parts.push(`${formatNumber(count)} ${title}`);
      }
      if (typeof fields.failureReason === "string" && fields.failureReason)
        parts.push(fields.failureReason);
      return parts.join(" · ");
    }
    return Object.entries(fields)
      .slice(0, 8)
      .map(([key, field]) => {
        const title = label(key.replace(/([A-Z])/g, " $1"));
        if (Array.isArray(field)) return `${title}: ${field.length} items`;
        if (field && typeof field === "object") return `${title}: details available`;
        if (typeof field === "boolean") return `${title}: ${field ? "Yes" : "No"}`;
        return `${title}: ${String(field)}`;
      })
      .join(" · ");
  }
  return String(detail);
}

export function detailSearchText(value: unknown): string {
  if (typeof value === "string") return value;
  try {
    return JSON.stringify(value) || "";
  } catch {
    return detailText(value);
  }
}
