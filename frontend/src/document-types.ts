import type { Audit } from "./api";
export interface Submission {
  id: string;
  category: string;
  risk_score?: number | null;
  risk_level?: string | null;
  risk_hold?: boolean;
  capture_method?: "PDF" | "DIGITAL_EFORM";
  requires_rm_approval?: boolean;
  approved_revision?: number | null;
  customer_name: string;
  customer_reference: string;
  status: string;
  owner_name: string;
  submission_source?: "RM" | "CUSTOMER_PORTAL";
  customer_id?: string | null;
  revision: number;
  validated_revision: number | null;
  created_at: string;
  routed_at: string | null;
  ai_method?: string;
  open_exceptions?: number;
  verified_automations?: number;
}
export interface Field {
  document_id: string;
  version_id: string;
  filename: string;
  field_name: string;
  field_value: string;
  page_number: number | null;
  supporting_text: string;
  method?: string;
}
export interface Version {
  id: string;
  version: number;
  filename: string;
  created_at: string;
  uploaded_by: string;
}
export interface Doc {
  id: string;
  version_id: string;
  filename: string;
  document_type: string;
  current_version: number;
  fields: Field[];
  versions: Version[];
  extraction: {
    method: string;
    requires_review: boolean;
    notes: string;
    observations?: {
      field: string;
      page: number;
      supporting_text: string;
      issue_type: string;
    }[];
  };
}
export interface Finding {
  id: string;
  check_key: string;
  rule_id: string;
  name: string;
  description: string;
  status: string;
  severity: string;
  action_type: string;
  field_name: string;
  corrective_action: string;
  evidence: Field[];
  resolution_method: string;
}
export interface Check {
  outcome?: string;
  category?: string;
  created_at?: string;
  id?: string;
  condition_type?: string;
  field_name?: string;
  name: string;
  passed: boolean;
  severity: string;
  corrective_action: string;
  evidence: Field[];
  check_key: string;
  rule_version?: number;
}
export interface Task {
  id: string;
  exception_id: string;
  description: string;
  assigned_role: string;
  priority: string;
  status: string;
  created_at: string;
  resolved_at: string | null;
  corrective_action: string;
}
export interface Preflight {
  ruleSet: string;
  id: string;
  revision: number;
  totalChecks: number;
  checksPassed: number;
  blockingIssues: number;
  actualReady: boolean;
  predictedPassedAfterAttachment: number;
  predictedTotalChecks: number;
  predictedBlockingIssues: number;
  permittedAttachmentAvailable: boolean;
  safeAdministrativeAttachment: boolean;
  noNewIssues: boolean;
  humanApprovalRequired: boolean;
  unresolvedExceptions: number;
  validatedCurrentRevision: boolean;
  explanation: string;
  createdAt: string;
  checks: Check[];
}
export interface Recommendation {
  exception_id: string;
  explanation: string;
  evidence_ids: string[];
  recommended_action: string;
  human_review_required: boolean;
}
export interface Investigation {
  submission_id: string;
  revision: number;
  method: string;
  model: string | null;
  fallback_reason: string | null;
  completed_at?: string;
  recommendations: Recommendation[];
  source_findings: {
    exception_id: string;
    evidence: {
      evidence_id: string;
      document_id: string | null;
      version_id: string | null;
      page: number | null;
      field: string;
      value: string;
      supporting_text: string;
    }[];
  }[];
}
export interface AutomationAttempt {
  id: string;
  exception_id: string;
  revision: number;
  action_type: string;
  status: string;
  simulation_id: string | null;
  result_json: string;
  created_at: string;
  completed_at: string | null;
}
export interface AiStatus {
  configured: boolean | null;
  model?: string;
  liveStatus: string;
  lastLiveSuccessAt?: string;
  lastFailureCode?: string;
  liveRequestsSucceeded?: number;
  liveRequestsFailed?: number;
}
export interface Detail {
  riskAssessment?: RiskAssessment | null;
  investigations: Investigation[];
  automation: AutomationAttempt[];
  automationEnabled: boolean;
  ruleSet: string;
  submission: Submission;
  documents: Doc[];
  exceptions: Finding[];
  checks: Check[];
  tasks: Task[];
  audit: Audit[];
  routing: { destination: string; created_at: string; reason: string }[];
  simulations: Preflight[];
}
export interface Metrics {
  humanInterventionRequired: number;
  successfullyRouted: number;
  failedOrBlockedAutomation: number;
  riskCounts?: Record<string, number>;
  onHold?: number;
  totalSubmissions: number;
  statuses: Record<string, number>;
  automaticallyResolved: number;
  recurringErrors: Record<string, number>;
  averageCorrectionSeconds: number | null;
  resubmissionCycles: number;
  measuredCorrectionTasks: number;
  averageSubmissionToValidationSeconds: number | null;
}
export interface Rule {
  id: string;
  rule_key: string;
  name: string;
  description: string;
  document_type: string;
  condition_type: string;
  field_name: string;
  severity: string;
  corrective_action: string;
  version: number;
  status: string;
  created_by: string;
  approved_by: string | null;
}

export interface RiskAssessment {
  score: number;
  level: string;
  policy: string;
  assessedAt: string;
  blockingFindings: number;
  correctionAttempts: number;
  contributions: {
    issue: string;
    ruleId: string;
    weight: number;
    policyVersion: number;
    explanation: string;
    evidence: Field[];
  }[];
}
