/**
 * Node-runtime BFF proxy (framework-free for testability).
 *
 * Authority boundary: tenant, actor, and permissions come ONLY from the server-side session;
 * the proxy constructs a brand-new outbound header set and signs it. Browser-provided
 * authority, credential, and hop-by-hop headers are never forwarded. Responses are rebuilt
 * from scratch: Core Set-Cookie and internal headers are never exposed, and authenticated
 * responses are Cache-Control: no-store.
 */
import {
  matchBffRoute,
  corePathFromBffSegments,
  type BffQueryFieldPolicy,
  type BffRouteRule
} from "./bff-route-registry.ts";
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
import { parseBffSessionMaxAgeSeconds } from "./bff-session-ttl-policy.ts";
import { validateCsrf, validateSameOrigin } from "./bff-csrf.ts";
import { readSecurityCookieHeader } from "./bff-cookies.ts";

const SAFE_PROXY_ERROR = "The request could not be completed.";
const SAFE_IDEMPOTENCY_KEY = /^[A-Za-z0-9._~:-]{1,128}$/;
const ALLOWED_ACCEPT = new Set(["application/json", "*/*", "application/json, text/plain, */*"]);
const MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
const UUID_QUERY = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const TEXT_QUERY = /^[A-Za-z0-9][A-Za-z0-9._~:@ -]{0,127}$/;

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
  const sessionId = readSecurityCookieHeader(request.headers.get("cookie"), BFF_SESSION_COOKIE);
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

function queryValueAllowed(value: string, policy: BffQueryFieldPolicy): boolean {
  if (value.length === 0 || value.length > (policy.maxLength ?? 128)) {
    return false;
  }
  if (policy.type === "uuid") {
    return UUID_QUERY.test(value);
  }
  if (policy.type === "positive-int") {
    return /^[1-9][0-9]{0,8}$/.test(value);
  }
  if (policy.type === "enum") {
    return Boolean(policy.enumValues?.includes(value));
  }
  return TEXT_QUERY.test(value);
}

function validatedQuery(requestUrl: string, rule: BffRouteRule): string | null {
  let url: URL;
  try {
    url = new URL(requestUrl);
  } catch {
    return null;
  }
  if (!url.search) {
    return "";
  }
  if (url.search.length > 2048 || /[\u0000-\u001f\u007f]/.test(url.search)) {
    return null;
  }
  const allowedKeys = new Set(Object.keys(rule.query));
  for (const key of url.searchParams.keys()) {
    if (!allowedKeys.has(key)) {
      return null;
    }
    const values = url.searchParams.getAll(key);
    const policy = rule.query[key];
    if (values.length === 0 || values.length > (policy.maxValues ?? 1)) {
      return null;
    }
    if (!values.every((value) => queryValueAllowed(value, policy))) {
      return null;
    }
  }
  return url.search;
}

function contentLengthValue(request: Request): number | null {
  const raw = request.headers.get("content-length");
  if (!raw) {
    return null;
  }
  if (!/^[0-9]{1,12}$/.test(raw)) {
    return Number.NaN;
  }
  return Number.parseInt(raw, 10);
}

async function readRequestBodyBounded(request: Request, maxBytes: number): Promise<string | null> {
  const declaredLength = contentLengthValue(request);
  if (Number.isNaN(declaredLength) || (declaredLength !== null && declaredLength > maxBytes)) {
    await request.body?.cancel().catch(() => undefined);
    return null;
  }
  if (!request.body) {
    return "";
  }
  const reader = request.body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) {
        break;
      }
      total += value.byteLength;
      if (total > maxBytes) {
        await reader.cancel().catch(() => undefined);
        return null;
      }
      chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }
  const bytes = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return new TextDecoder().decode(bytes);
}

async function readUpstreamTextBounded(upstream: Response): Promise<string | null> {
  const rawLength = upstream.headers.get("content-length");
  if (rawLength) {
    if (!/^[0-9]{1,12}$/.test(rawLength) || Number.parseInt(rawLength, 10) > MAX_RESPONSE_BYTES) {
      await upstream.body?.cancel().catch(() => undefined);
      return null;
    }
  }
  if (!upstream.body) {
    return "";
  }
  const reader = upstream.body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) {
        break;
      }
      total += value.byteLength;
      if (total > MAX_RESPONSE_BYTES) {
        await reader.cancel().catch(() => undefined);
        return null;
      }
      chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }
  const bytes = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return new TextDecoder().decode(bytes);
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
      const csrfCookie = readSecurityCookieHeader(request.headers.get("cookie"), BFF_CSRF_COOKIE);
      if (!validateCsrf(request, csrfCookie)) {
        return safeJson(403);
      }
    }
  }

  let body: string | undefined;
  if (rule.kind === "mutation") {
    const requestContentType = request.headers.get("content-type")?.split(";")[0].trim().toLowerCase();
    const bodyText = await readRequestBodyBounded(request, rule.maxBodyBytes);
    if (bodyText === null) {
      return safeJson(413);
    }
    if (bodyText.length === 0) {
      body = undefined;
    } else {
      if (!rule.contentType || requestContentType !== rule.contentType) {
        return safeJson(415);
      }
      body = bodyText;
    }
  }

  const query = validatedQuery(request.url, rule);
  if (query === null) {
    return safeJson(400);
  }

  const coreBaseUrl = validatedCoreApiInternalBaseUrl();
  const gatewaySecret = bffGatewaySharedSecret();
  if (!coreBaseUrl || !gatewaySecret) {
    return safeJson(503);
  }

  const corePath = corePathFromBffSegments(segments);
  const target = `${coreBaseUrl}${corePath}${query}`;
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

  const upstreamContentType = upstream.headers.get("Content-Type")?.toLowerCase() ?? "";
  if (upstream.status === 204) {
    return new Response(null, { status: 204, headers: { "Cache-Control": "no-store" } });
  }
  if (!upstreamContentType.startsWith("application/json")) {
    await upstream.body?.cancel().catch(() => undefined);
    return safeJson(502);
  }
  // Bounded safe error mapping: raw Core 4xx/5xx bodies are never exposed to the browser.
  if (upstream.status >= 400) {
    await upstream.body?.cancel().catch(() => undefined);
    return safeJson(upstream.status >= 500 ? 502 : upstream.status);
  }
  const text = await readUpstreamTextBounded(upstream);
  if (text === null) {
    return safeJson(502);
  }
  const responseHeaders = new Headers({ "Cache-Control": "no-store" });
  responseHeaders.set("Content-Type", upstreamContentType);
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
  const ttl = parseBffSessionMaxAgeSeconds(process.env.ORDERPILOT_BFF_SESSION_MAX_AGE_SECONDS, {
    allowDefaultOnMissing: true
  });
  if (!ttl.ok) {
    return ttl.reason;
  }
  if (isProductionLikeDeployment()) {
    const redisError = await requireRedisSessionBackend();
    if (redisError) {
      return redisError;
    }
  }
  return null;
}
