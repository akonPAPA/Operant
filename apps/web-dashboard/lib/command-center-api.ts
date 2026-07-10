import { dashboardFetchHeaders, dashboardFetchUrl } from "./bff/dashboard-fetch";
import { dashboardCoreApiBaseUrl } from "./api-transport";
import { demoTenantId } from "./frontend-authority.mjs";

// OP-CAP-21 Transaction Command Center read-model client.
// Read-only, tenant-scoped projection of the Operant command center surface:
// metrics, work-queue preview, runtime/outbox health, audit timeline preview, and
// reconciliation preview. Reads require ANALYTICS_READ (re-validated by the backend
// ApiPermissionInterceptor on every request). These responses are advisory display data only —
// they never approve quotes/orders and never trigger external/ERP/connector/AI writes.
// No secrets, audit detail blobs, raw AI prompts, or raw payment data are exposed.

export type CommandCenterMetric = {
  key: string;
  label: string;
  value: number;
  unit: string;
  available: boolean;
  partial: boolean;
  note: string | null;
};

export type WorkQueueItem = {
  caseId: string;
  caseNumber: string;
  title: string;
  status: string;
  severity: string;
  priority: string;
  sourceType: string;
  createdAt: string;
  linkRoute: string;
};

export type WorkQueuePreview = {
  items: WorkQueueItem[];
  openTotal: number;
  previewLimit: number;
  partial: boolean;
  generatedAt: string;
};

export type RuntimeHealth = {
  available: boolean;
  pendingJobs: number;
  runningJobs: number;
  failedJobs: number;
  lastJobQueuedAt: string | null;
  degraded: boolean;
  note: string | null;
};

export type OutboxHealth = {
  available: boolean;
  pendingEvents: number;
  publishedEvents: number;
  skippedExternalDisabled: number;
  lastPublishedAt: string | null;
  degraded: boolean;
  note: string | null;
};

export type AuditTimelineItem = {
  action: string;
  entityType: string;
  occurredAt: string;
};

export type AuditTimelinePreview = {
  items: AuditTimelineItem[];
  previewLimit: number;
  partial: boolean;
  generatedAt: string;
};

export type ReconciliationPreviewCase = {
  caseId: string;
  productId: string;
  locationId: string;
  mismatchQuantity: number | string;
  severity: string;
  status: string;
  updatedAt: string;
};

export type ReconciliationPreview = {
  available: boolean;
  openCases: number;
  highSeverityOpenCases: number;
  recentCases: ReconciliationPreviewCase[];
  previewLimit: number;
  partial: boolean;
  note: string | null;
  generatedAt: string;
};

export type CommandCenterSummary = {
  metrics: CommandCenterMetric[];
  workQueue: WorkQueuePreview;
  runtime: RuntimeHealth;
  outbox: OutboxHealth;
  auditTimeline: AuditTimelinePreview;
  reconciliation: ReconciliationPreview;
  generatedAt: string;
};

export type CommandCenterResult = {
  data: CommandCenterSummary | null;
  error?: string;
};

const DEFAULT_BASE_URL = "http://localhost:8080";
const ANALYTICS_READ = "ANALYTICS_READ";

export const commandCenterClient = {
  baseUrl: dashboardCoreApiBaseUrl(),
  tenantId: demoTenantId()
};

function baseHeaders(): Record<string, string> {
  const h: Record<string, string> = {
    "Content-Type": "application/json",
    "X-OrderPilot-Permissions": ANALYTICS_READ
  };
  if (commandCenterClient.tenantId) {
    h["X-Tenant-Id"] = commandCenterClient.tenantId;
  }
  return h;
}

export async function getCommandCenterSummary(): Promise<CommandCenterResult> {
  if (!commandCenterClient.tenantId) {
    return { data: null, error: "Authenticated dashboard access is unavailable." };
  }
  try {
    const response = await fetch(`${commandCenterClient.baseUrl}/api/v1/command-center/summary`, {
      cache: "no-store",
      method: "GET",
      headers: baseHeaders()
    });
    const text = await response.text();
    if (!response.ok) {
      let message = "";
      if (text) {
        try {
          const parsed = JSON.parse(text) as { message?: string };
          message = parsed.message ?? "";
        } catch {
          message = "";
        }
      }
      return { data: null, error: message || `Core API returned ${response.status}.` };
    }
    return { data: text ? (JSON.parse(text) as CommandCenterSummary) : null };
  } catch {
    return { data: null, error: "Core API not reachable." };
  }
}
