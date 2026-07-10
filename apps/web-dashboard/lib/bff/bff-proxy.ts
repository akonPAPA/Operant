import { cookies } from "next/headers";
import { NextResponse } from "next/server";
import { isBffProxyPathAllowed, corePathFromBffSegments } from "./bff-allowlist";
import {
  BFF_CSRF_COOKIE,
  BFF_CSRF_HEADER,
  BFF_SESSION_COOKIE,
  bffGatewayClockSkewSeconds,
  bffGatewaySharedSecret,
  bffRuntimeMode,
  bffSessionSecret,
  coreApiInternalBaseUrl
} from "./bff-config";
import { signGatewayHeaders } from "./bff-gateway-signer";
import { parseSessionToken } from "./bff-session";
import { isSessionRevoked } from "./bff-session-revocation";

const SAFE_PROXY_ERROR = "The request could not be completed.";

export async function readOperatorSessionFromCookies() {
  if (bffRuntimeMode() !== "bff-production") {
    return null;
  }
  const secret = bffSessionSecret();
  const cookieStore = await cookies();
  return parseSessionToken(cookieStore.get(BFF_SESSION_COOKIE)?.value, secret);
}

export function validateCsrf(request: Request, csrfCookie: string | undefined): boolean {
  const header = request.headers.get(BFF_CSRF_HEADER)?.trim();
  if (!header || !csrfCookie) {
    return false;
  }
  return header === csrfCookie;
}

export async function proxyCoreRequest(
  request: Request,
  segments: string[]
): Promise<NextResponse> {
  if (bffRuntimeMode() !== "bff-production") {
    return NextResponse.json({ message: SAFE_PROXY_ERROR }, { status: 503 });
  }
  if (!isBffProxyPathAllowed(segments)) {
    return NextResponse.json({ message: SAFE_PROXY_ERROR }, { status: 404 });
  }
  const session = await readOperatorSessionFromCookies();
  if (!session || isSessionRevoked(session.sessionId)) {
    return NextResponse.json({ message: SAFE_PROXY_ERROR }, { status: 401 });
  }
  const method = request.method.toUpperCase();
  if (method !== "GET" && method !== "HEAD" && method !== "OPTIONS") {
    const cookieStore = await cookies();
    if (!validateCsrf(request, cookieStore.get(BFF_CSRF_COOKIE)?.value)) {
      return NextResponse.json({ message: SAFE_PROXY_ERROR }, { status: 403 });
    }
  }
  const gatewaySecret = bffGatewaySharedSecret();
  if (!gatewaySecret) {
    return NextResponse.json({ message: SAFE_PROXY_ERROR }, { status: 503 });
  }
  const corePath = corePathFromBffSegments(segments);
  const url = new URL(request.url);
  const target = `${coreApiInternalBaseUrl()}${corePath}${url.search}`;
  const body =
    method === "GET" || method === "HEAD" ? undefined : await request.text();
  const signed = signGatewayHeaders({
    method,
    requestUri: corePath,
    tenantId: session.tenantId,
    actorId: session.actorId,
    permissions: session.permissions,
    sharedSecret: gatewaySecret
  });
  let upstream: Response;
  try {
    upstream = await fetch(target, {
      method,
      body: body && body.length > 0 ? body : undefined,
      headers: {
        "Content-Type": request.headers.get("Content-Type") ?? "application/json",
        ...signed,
        ...(request.headers.get("Idempotency-Key")
          ? { "Idempotency-Key": request.headers.get("Idempotency-Key") as string }
          : {})
      },
      cache: "no-store"
    });
  } catch {
    return NextResponse.json({ message: SAFE_PROXY_ERROR }, { status: 502 });
  }
  const text = await upstream.text();
  return new NextResponse(text || null, {
    status: upstream.status,
    headers: {
      "Content-Type": upstream.headers.get("Content-Type") ?? "application/json",
      "Cache-Control": "no-store"
    }
  });
}

export function validateBffProductionConfig(): string | null {
  if (bffRuntimeMode() !== "bff-production") {
    return null;
  }
  if (!bffSessionSecret() || bffSessionSecret().length < 32) {
    return "ORDERPILOT_BFF_SESSION_SECRET must be at least 32 characters in BFF production mode";
  }
  if (!bffGatewaySharedSecret()) {
    return "ORDERPILOT_GATEWAY_HEADER_AUTH_SHARED_SECRET is required for BFF production mode";
  }
  if (!coreApiInternalBaseUrl().startsWith("http")) {
    return "CORE_API_BASE_URL must be configured for BFF production mode";
  }
  void bffGatewayClockSkewSeconds();
  return null;
}
