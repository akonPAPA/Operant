// OP-CAP-22 Order Journey & Fulfillment Visibility client.
// Read-only, tenant-scoped views of the commercial transaction lifecycle: list, attention queue,
// and journey detail (ordered milestones, recent events, fulfillment signals). Reads require
// ANALYTICS_READ (re-validated by the backend ApiPermissionInterceptor on every request).
// The frontend never invents journey statuses, never shows fake carrier/GPS/payment data, and
// never mutates business data. Payment milestones are surfaced exactly as the backend reports them
// (UNKNOWN until a real payment mirror exists). No secrets or raw payloads are exposed.

export type OrderJourneyListItem = {
  id: string;
  sourceType: string;
  sourceId: string;
  customerAccountId: string | null;
  customerDisplayName: string | null;
  currentStage: string;
  currentStatus: string;
  riskLevel: string;
  blocked: boolean;
  evidenceLevel: string;
  lastSignalAt: string | null;
  updatedAt: string;
};

export type OrderJourneySummary = {
  items: OrderJourneyListItem[];
  total: number;
  blockedCount: number;
  previewLimit: number;
  partial: boolean;
  generatedAt: string;
};

export type OrderJourneyMilestone = {
  milestoneCode: string;
  milestoneLabel: string;
  milestoneState: string;
  evidenceLevel: string;
  occurredAt: string | null;
  estimatedAt: string | null;
  sourceType: string | null;
  sourceRef: string | null;
  customerVisible: boolean;
  sortOrder: number;
};

export type OrderJourneyEvent = {
  eventType: string;
  eventStatus: string | null;
  evidenceLevel: string;
  message: string;
  sourceType: string | null;
  sourceRef: string | null;
  actorType: string;
  customerVisible: boolean;
  occurredAt: string;
};

export type FulfillmentSignal = {
  id: string;
  sourceType: string;
  signalType: string;
  signalStatus: string | null;
  confidence: number | string | null;
  sourceRef: string | null;
  customerVisible: boolean;
  receivedAt: string;
  processedAt: string | null;
};

export type OrderJourneyDetail = {
  id: string;
  sourceType: string;
  sourceId: string;
  customerAccountId: string | null;
  customerDisplayName: string | null;
  currentStage: string;
  currentStatus: string;
  riskLevel: string;
  blocked: boolean;
  customerVisibleStatus: string;
  internalStatus: string;
  lastSignalAt: string | null;
  createdAt: string;
  updatedAt: string;
  milestones: OrderJourneyMilestone[];
  recentEvents: OrderJourneyEvent[];
  fulfillmentSignals: FulfillmentSignal[];
  paymentStatusAvailable: boolean;
  fulfillmentTrackingConnected: boolean;
  generatedAt: string;
};

export type OrderJourneyListResult = { data: OrderJourneySummary | null; error?: string };
export type OrderJourneyDetailResult = { data: OrderJourneyDetail | null; error?: string };

const DEFAULT_BASE_URL = "http://localhost:8080";
const ANALYTICS_READ = "ANALYTICS_READ";

export const orderJourneyClient = {
  baseUrl: process.env.CORE_API_BASE_URL ?? process.env.NEXT_PUBLIC_CORE_API_URL ?? DEFAULT_BASE_URL,
  tenantId: process.env.NEXT_PUBLIC_DEMO_TENANT_ID ?? ""
};

function baseHeaders(): Record<string, string> {
  const h: Record<string, string> = {
    "Content-Type": "application/json",
    "X-OrderPilot-Permissions": ANALYTICS_READ
  };
  if (orderJourneyClient.tenantId) {
    h["X-Tenant-Id"] = orderJourneyClient.tenantId;
  }
  return h;
}

async function read<T>(path: string): Promise<{ data: T | null; error?: string }> {
  if (!orderJourneyClient.tenantId) {
    return { data: null, error: "Set NEXT_PUBLIC_DEMO_TENANT_ID to read tenant-scoped order journeys." };
  }
  try {
    const response = await fetch(`${orderJourneyClient.baseUrl}${path}`, {
      cache: "no-store",
      method: "GET",
      headers: baseHeaders()
    });
    const text = await response.text();
    if (!response.ok) {
      let message = "";
      if (text) {
        try {
          message = (JSON.parse(text) as { message?: string }).message ?? "";
        } catch {
          message = "";
        }
      }
      return { data: null, error: message || `Core API returned ${response.status}.` };
    }
    return { data: text ? (JSON.parse(text) as T) : null };
  } catch {
    return { data: null, error: "Core API not reachable." };
  }
}

export async function getOrderJourneys(): Promise<OrderJourneyListResult> {
  return read<OrderJourneySummary>("/api/v1/order-journeys");
}

export async function getOrderJourneyAttention(): Promise<OrderJourneyListResult> {
  return read<OrderJourneySummary>("/api/v1/order-journeys/attention");
}

export async function getOrderJourney(id: string): Promise<OrderJourneyDetailResult> {
  return read<OrderJourneyDetail>(`/api/v1/order-journeys/${encodeURIComponent(id)}`);
}
