/**
 * Node-runtime BFF proxy (framework-free for testability).
 *
 * Authority boundary: tenant, actor, and permissions come ONLY from the server-side session;
 * the proxy constructs a brand-new outbound header set and signs it. Browser-provided
 * authority, credential, and hop-by-hop headers are never forwarded. Responses are rebuilt
 * from scratch: Core Set-Cookie and internal headers are never exposed, and authenticated
 * responses are Cache-Control: no-store.
 */
import { matchBffRoute, corePathFromBffSegments, type BffRouteRule } from "./bff-route-registry.ts";
import {
  BFF_CSRF_COOKIE,
  BFF_SESSION_COOKIE,
  bffGatewaySharedSecret,
  bffRuntimeMode,
  bffSessionSecret,
  bffUpstreamTimeoutMs,
  validatedCoreApiInternalBaseUrl
} from "./bff-config.ts";
import { signGatewayHeaders } from "./bff-gateway-signer.ts";
import { loadOperatorSession, requireRedisSessionBackend } from "./bff-session-store.ts";
import { isProductionLikeDeployment } from "./bff-deployment-profile.ts";
import { validateCsrf, validateSameOrigin } from "./bff-csrf.ts";
import { readCookie } from "./bff-cookies.ts";

const SAFE_PROXY_ERROR = "The request could not be completed.";
const SAFE_IDEMPOTENCY_KEY = /^[A-Za-z0-9._~:-]{1,128}$/;
const ALLOWED_ACCEPT = new Set(["application/json", "*/*", "application/json, text/plain, */*"]);

function safeJson(status: number): Response {
  return new Response(JSON.stringify({ message: SAFE_PROXY_ERROR }), {
    status,
    headers: { "Content-Type": "application/json", "Cache-Control": "no-store" }
  });
}

/**
 * Request-hardening on the raw (still percent-encoded) path: encoded slashes/backslashes,
 * dot segments, duplicate slashes, malformed percent escapes, control characters, and
 * over-long paths are all rejected before any route matching.
 */
export function rawBffPathRejected(rawPathname: string): boolean {
  if (rawPathname.length > 2048) {
    return true;
  }
  if (/%2f|%5c/i.test(rawPathname)) {
    return true;
  }
  if (rawPathname.includes("\\")) {
    return true;
  }
  if (rawPathname.includes("//")) {
    return true;
  }

  if (/[\u0000-\u001f\u007f]/.test(rawPathname)) {
    return true;
  }
  if (/%(?![0-9a-fA-F]{2})/.test(rawPathname)) {
    return true;
  }
  for (const segment of rawPathname.split("/")) {
    let decoded: string;
    try {
      decoded = decodeURIComponent(segment);
    } catch {
      return true;
    }
    if (decoded === "." || decoded === ".." || decoded.includes("/") || decoded.includes("\\")) {
      return true;
    }
  }
  return false;
}

async function readOperatorSession(request: Request) {
  const sessionId = readCookie(request.headers.get("cookie"), BFF_SESSION_COOKIE);
  return loadOperatorSession(sessionId);
}

function buildOutboundHeaders(
  request: Request,
  rule: BffRouteRule,
  signed: Record<string, string>,
  hasBody: boolean
): Headers {
  // A brand-new header set: nothing from the browser is forwarded except the bounded
  // business headers explicitly registered for this route.
  const headers = new Headers(signed);
  if (hasBody && rule.contentType) {
    headers.set("Content-Type", rule.contentType);
  }
  const accept = request.headers.get("accept")?.trim().toLowerCase();
  headers.set("Accept", accept && ALLOWED_ACCEPT.has(accept) ? accept : "application/json");
  if (rule.allowIdempotencyKey) {
    const idempotencyKey = request.headers.get("idempotency-key")?.trim();
    if (idempotencyKey && SAFE_IDEMPOTENCY_KEY.test(idempotencyKey)) {
      headers.set("Idempotency-Key", idempotencyKey);
    }
  }
  if (rule.allowIfMatch) {
    const ifMatch = request.headers.get("if-match")?.trim();
    if (ifMatch && ifMatch.length <= 256) {
      headers.set("If-Match", ifMatch);
    }
  }
  return headers;
}

function sanitizedQuery(requestUrl: string): string {
  try {
    const search = new URL(requestUrl).search;
    if (!search) {
      return "";
    }

    if (search.length > 2048 || /[\u0000-\u001f\u007f]/.test(search)) {
      return "";
    }
    return search;
  } catch {
    return "";
  }
}

export async function proxyCoreRequest(request: Request, segments: string[]): Promise<Response> {
  if (bffRuntimeMode() !== "bff-production") {
    return safeJson(503);
  }
  const configError = await validateBffProductionConfig();
  if (configError) {
    return safeJson(503);
  }

  let rawPathname: string;
  try {
    rawPathname = new URL(request.url).pathname;
  } catch {
    return safeJson(400);
  }
  if (rawBffPathRejected(rawPathname)) {
    return safeJson(400);
  }

  const method = request.method.toUpperCase();
  const rule = matchBffRoute(segments, method);
  if (!rule) {
    return safeJson(404);
  }

  const session = await readOperatorSession(request);
  if (!session) {
    return safeJson(401);
  }
  // Defense in depth: the session must actually hold the registered permission.
  if (!session.permissions.includes(rule.permission)) {
    return safeJson(403);
  }

  if (rule.kind === "mutation") {
    if (!validateSameOrigin(request)) {
      return safeJson(403);
    }
    if (rule.csrfRequired) {
      const csrfCookie = readCookie(request.headers.get("cookie"), BFF_CSRF_COOKIE);
      if (!validateCsrf(request, csrfCookie)) {
        return safeJson(403);
      }
    }
  }

  let body: string | undefined;
  if (rule.kind === "mutation") {
    const requestContentType = request.headers.get("content-type")?.split(";")[0].trim().toLowerCase();
    body = await request.text();
    if (body.length === 0) {
      body = undefined;
    } else {
      if (!rule.contentType || requestContentType !== rule.contentType) {
        return safeJson(415);
      }
      if (new TextEncoder().encode(body).byteLength > rule.maxBodyBytes) {
        return safeJson(413);
      }
    }
  }

  const coreBaseUrl = validatedCoreApiInternalBaseUrl();
  const gatewaySecret = bffGatewaySharedSecret();
  if (!coreBaseUrl || !gatewaySecret) {
    return safeJson(503);
  }

  const corePath = corePathFromBffSegments(segments);
  const target = `${coreBaseUrl}${corePath}${sanitizedQuery(request.url)}`;
  const signed = signGatewayHeaders({
    method,
    requestUri: corePath,
    tenantId: session.tenantId,
    actorId: session.actorId,
    permissions: session.permissions,
    sharedSecret: gatewaySecret
  });

  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), bffUpstreamTimeoutMs());
  let upstream: Response;
  try {
    upstream = await fetch(target, {
      method,
      body,
      headers: buildOutboundHeaders(request, rule, signed, body !== undefined),
      cache: "no-store",
      redirect: "manual",
      signal: controller.signal
    });
  } catch (error) {
    return safeJson(error instanceof Error && error.name === "AbortError" ? 504 : 502);
  } finally {
    clearTimeout(timer);
  }

  // Bounded safe error mapping: raw Core 5xx bodies are never exposed to the browser.
  if (upstream.status >= 500) {
    return safeJson(502);
  }
  const text = await upstream.text();
  const responseHeaders = new Headers({ "Cache-Control": "no-store" });
  const upstreamContentType = upstream.headers.get("Content-Type");
  responseHeaders.set(
    "Content-Type",
    upstreamContentType?.startsWith("application/json") ? upstreamContentType : "application/json"
  );
  return new Response(text.length > 0 ? text : null, {
    status: upstream.status,
    headers: responseHeaders
  });
}

/** Fail-closed configuration validation for BFF production mode (Node runtime only). */
export async function validateBffProductionConfig(): Promise<string | null> {
  if (bffRuntimeMode() !== "bff-production") {
    return null;
  }
  if (!bffSessionSecret() || bffSessionSecret().length < 32) {
    return "ORDERPILOT_BFF_SESSION_SECRET must be at least 32 characters in BFF production mode";
  }
  if (!bffGatewaySharedSecret()) {
    return "ORDERPILOT_GATEWAY_HEADER_AUTH_SHARED_SECRET is required for BFF production mode";
  }
  if (!validatedCoreApiInternalBaseUrl()) {
    return "CORE_API_BASE_URL must be an explicit http(s) URL for BFF production mode";
  }
  if (isProductionLikeDeployment()) {
    const redisError = await requireRedisSessionBackend();
    if (redisError) {
      return redisError;
    }
  }
  return null;
}
