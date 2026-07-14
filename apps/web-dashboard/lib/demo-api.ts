import { dashboardCoreApiBaseUrl, enrichDashboardRequestInit, isDashboardApiAuthorityAvailable } from "./api-transport";
import { dashboardApiFetch } from "./dashboard-http";
import { demoTenantId } from "./frontend-authority.mjs";

export type ApiResult<T> =
  | { ok: true; data: T; message: string }
  | { ok: false; data?: T; message: string; status?: number };

export type BotWebhookResponse = {
  conversationId?: string;
  messageId?: string;
  intent?: "RFQ_REQUEST" | "UNKNOWN" | string;
  status?: string;
  responseMessage?: string;
  requiresHumanReview?: boolean;
  createdRfqDraftId?: string;
};

export type DemoRfqHandoffResponse = {
  handoffId: string;
  status: string;
  message: string;
};

export type ReconciliationRunResponse = {
  expectedStock?: number | string;
  actualStock?: number | string;
  mismatchQuantity?: number | string;
  severity?: string;
  status?: string;
  reconciliationCaseId?: string;
  discrepancyCreatedOrUpdated?: boolean;
};

export type ReconciliationCase = {
  id?: string;
  expectedStock?: number | string;
  actualStock?: number | string;
  mismatchQuantity?: number | string;
  severity?: string;
  status?: string;
  likelyCauses?: string;
};

export type ReconciliationCasesResponse = {
  cases?: ReconciliationCase[];
  totalElements?: number;
};

export type CommerceAnalyticsSummaryResponse = {
  totalSalesAmount?: number | string;
  totalSalesAmountNote?: string;
  totalBotRfqRequests?: number;
  openReconciliationCases?: number;
  highSeverityReconciliationCases?: number;
  channelBreakdown?: Record<string, number>;
};

const DEFAULT_BASE_URL = "http://localhost:8080";
const DEMO_RFQ_TEXT = "Need 2 EA PAD-OE-04465 brake pads for Toyota Camry 2018, wholesale, Almaty.";

export const demoConfig = {
  baseUrl: dashboardCoreApiBaseUrl(),
  tenantId: demoTenantId(),
  productId: process.env.NEXT_PUBLIC_DEMO_PRODUCT_ID ?? "",
  locationId: process.env.NEXT_PUBLIC_DEMO_LOCATION_ID ?? ""
};

export const demoTelegramRfqText = DEMO_RFQ_TEXT;

export const demoTelegramUnknownPayload = {
  update_id: 91002,
  message: {
    message_id: 7002,
    chat: { id: 450001 },
    text: "Can you check the thing we discussed last time?"
  }
};

export const reconciliationFixture = {
  openingStock: 150,
  sold: 34,
  expectedStock: 116,
  actualStock: 100,
  mismatch: -16,
  severity: "HIGH",
  likelyCauses: "Unposted warehouse issue, counting variance, or delayed sales posting."
};

function headers() {
  const requestHeaders: Record<string, string> = { "Content-Type": "application/json" };
  if (demoConfig.tenantId) {
    requestHeaders["X-Tenant-Id"] = demoConfig.tenantId;
  }
  return requestHeaders;
}

async function requestJson<T>(path: string, init?: RequestInit): Promise<ApiResult<T>> {
  if (!isDashboardApiAuthorityAvailable(demoConfig.tenantId)) {
    return { ok: false, message: "Authenticated dashboard access is unavailable." };
  }
  try {
    const response = await dashboardApiFetch(
      path,
      enrichDashboardRequestInit({
        ...init,
        headers: { ...headers(), ...(init?.headers ?? {}) }
      })
    );
    const text = await response.text();
    const data = text ? (JSON.parse(text) as T) : ({} as T);

    if (!response.ok) {
      return {
        ok: false,
        data,
        status: response.status,
        message: `Backend returned ${response.status}. Demo backend data may not be seeded yet.`
      };
    }

    return { ok: true, data, message: "Backend call completed." };
  } catch {
    return {
      ok: false,
      message: "Core API is not reachable from the browser."
    };
  }
}

export function sendDemoTelegramRfq() {
  return requestDashboardJson<DemoRfqHandoffResponse>("/api/demo/rfq-handoff");
}

export function sendUnknownTelegramMessage() {
  return requestJson<BotWebhookResponse>("/api/v1/bot/telegram/webhook", {
    method: "POST",
    body: JSON.stringify(demoTelegramUnknownPayload)
  });
}

export function runInventoryReconciliation() {
  if (!demoConfig.productId || !demoConfig.locationId) {
    return Promise.resolve<ApiResult<ReconciliationRunResponse>>({
      ok: false,
      message: "Demo backend data not seeded yet. Configure NEXT_PUBLIC_DEMO_PRODUCT_ID and NEXT_PUBLIC_DEMO_LOCATION_ID after seeding demo data."
    });
  }

  return requestJson<ReconciliationRunResponse>("/api/v1/reconciliation/inventory/run", {
    method: "POST",
    body: JSON.stringify({ productId: demoConfig.productId, locationId: demoConfig.locationId })
  });
}

export function refreshCommerceAnalytics() {
  return requestJson<CommerceAnalyticsSummaryResponse>("/api/v1/analytics/commerce/summary", {
    method: "GET",
    headers: { "X-OrderPilot-Permissions": "ANALYTICS_READ" }
  });
}

export function viewReconciliationCases() {
  return requestJson<ReconciliationCasesResponse>("/api/v1/reconciliation/cases", { method: "GET" });
}

async function requestDashboardJson<T>(path: string): Promise<ApiResult<T>> {
  try {
    const response = await dashboardApiFetch(path, enrichDashboardRequestInit({ method: "POST" }));
    const text = await response.text();
    if (!response.ok) {
      return {
        ok: false,
        status: response.status,
        message: `Demo action returned ${response.status}.`
      };
    }
    return {
      ok: true,
      data: (text ? JSON.parse(text) : {}) as T,
      message: "Backend call completed."
    };
  } catch {
    return { ok: false, message: "Demo action is unavailable." };
  }
}
