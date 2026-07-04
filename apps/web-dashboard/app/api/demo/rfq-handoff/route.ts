import { NextResponse } from "next/server";

const CORE_PATH = "/api/v1/demo/rfq-handoff";
const DEMO_ACTION_PERMISSION = "ADMIN_SETTINGS_MANAGE";
const SAFE_FAILURE_MESSAGE = "The demo RFQ could not be created.";
const DEFAULT_CORE_API_BASE_URL = "http://localhost:8080";

type CoreDemoRfqResponse = {
  handoffId?: unknown;
  status?: unknown;
  message?: unknown;
};

export async function POST(request: Request) {
  const submittedBody = (await request.text()).trim();
  if (submittedBody && submittedBody !== "{}") {
    return NextResponse.json(
      { message: "This demo action does not accept request fields." },
      { status: 400 }
    );
  }
  const tenantId = process.env.ORDERPILOT_DEMO_TENANT_ID?.trim();
  const demoEnabled =
    process.env.NODE_ENV !== "production"
    && process.env.ORDERPILOT_DEMO_MODE === "true";
  if (!demoEnabled || !tenantId) {
    return NextResponse.json({ message: SAFE_FAILURE_MESSAGE }, { status: 503 });
  }

  const coreApiBaseUrl =
    process.env.CORE_API_BASE_URL ?? DEFAULT_CORE_API_BASE_URL;
  let response: Response;
  try {
    response = await fetch(`${coreApiBaseUrl}${CORE_PATH}`, {
      method: "POST",
      cache: "no-store",
      headers: {
        "Content-Type": "application/json",
        "X-Tenant-Id": tenantId,
        "X-OrderPilot-Permissions": DEMO_ACTION_PERMISSION
      }
    });
  } catch {
    return NextResponse.json({ message: SAFE_FAILURE_MESSAGE }, { status: 502 });
  }

  const text = await response.text();
  if (!response.ok) {
    return NextResponse.json(
      { message: SAFE_FAILURE_MESSAGE },
      { status: response.status }
    );
  }

  try {
    const data = JSON.parse(text) as CoreDemoRfqResponse;
    if (
      typeof data.handoffId !== "string"
      || typeof data.status !== "string"
      || typeof data.message !== "string"
    ) {
      throw new Error("Invalid core response");
    }
    return NextResponse.json({
      handoffId: data.handoffId,
      status: data.status,
      message: data.message
    });
  } catch {
    return NextResponse.json({ message: SAFE_FAILURE_MESSAGE }, { status: 502 });
  }
}
