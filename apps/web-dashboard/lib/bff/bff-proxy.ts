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
  bffPublicOrigin,
  bffRuntimeMode,
  bffGatewayClockSkewSeconds,
  bffUpstreamTimeoutMs,
  validatedCoreApiInternalBaseUrl
} from "./bff-config.ts";
import { signGatewayHeaders } from "./bff-gateway-signer.ts";
import { loadOperatorSession, requireRedisSessionBackend } from "./bff-session-store.ts";
import { isProductionLikeDeployment } from "./bff-deployment-profile.ts";
import { parseBffSessionMaxAgeSeconds } from "./bff-session-ttl-policy.ts";
import { validateCsrf, validateSameOrigin } from "./bff-csrf.ts";
import { readSecurityCookie, type SecurityCookiePolicy } from "./bff-cookies.ts";
import { resolveIdempotencyKey } from "./bff-idempotency-key.ts";
import { bodyMatchesStrictPolicy } from "./bff-strict-body-policy.ts";
import {
  parseStrictQuoteJsonBody,
  quoteMutationKind,
  validateQuoteMutationBody
} from "./bff-quote-mutation-contract.ts";

const SAFE_PROXY_ERROR = "The request could not be completed.";
const ALLOWED_ACCEPT = new Set(["application/json", "*/*", "application/json, text/plain, */*"]);
const MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
const UUID_QUERY = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const TEXT_QUERY = /^[A-Za-z0-9][A-Za-z0-9._~:@ -]{0,127}$/;
const SESSION_COOKIE_POLICY: SecurityCookiePolicy = { minLength: 43, maxLength: 128, pattern: /^[A-Za-z0-9_-]+$/ };
const CSRF_COOKIE_POLICY: SecurityCookiePolicy = { minLength: 16, maxLength: 256, pattern: /^[A-Za-z0-9_-]+$/ };

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
  const sessionCookie = readSecurityCookie(request.headers.get("cookie"), BFF_SESSION_COOKIE, SESSION_COOKIE_POLICY);
  if (sessionCookie.status !== "valid") {
    return null;
  }
  return loadOperatorSession(sessionCookie.value);
}

function buildOutboundHeaders(
  request: Request,
  rule: BffRouteRule,
  signed: Record<string, string>,
  hasBody: boolean,
  idempotencyKey: string | null
): Headers {
  // A brand-new header set: nothing from the browser is forwarded except the bounded
  // business headers explicitly registered for this route.
  const headers = new Headers(signed);
  if (hasBody && rule.contentType) {
    headers.set("Content-Type", rule.contentType);
  }
  const accept = request.headers.get("accept")?.trim().toLowerCase();
  headers.set("Accept", accept && ALLOWED_ACCEPT.has(accept) ? accept : "application/json");
  // F01: the key was already validated against the canonical contract and, if present, is
  // forwarded byte-for-byte. A present-but-invalid key was rejected upstream with 400.
  if (idempotencyKey !== null) {
    headers.set("Idempotency-Key", idempotencyKey);
  }
  if (rule.allowIfMatch) {
    const ifMatch = request.headers.get("if-match")?.trim();
    if (ifMatch && ifMatch.length <= 256) {
      headers.set("If-Match", ifMatch);
    }
  }
  return headers;
}

// Semantic numeric grammars: no leading zeros (except a lone "0"), no sign, no decimal,
// no exponent, no whitespace, no hex. Capping at 9 digits keeps values < 1e9 < 2^31, so
// integer overflow is rejected at the BFF before Core ever parses the value.
const POSITIVE_INT_QUERY = /^[1-9][0-9]{0,8}$/;
const NON_NEGATIVE_INT_QUERY = /^(0|[1-9][0-9]{0,8})$/;

function queryValueAllowed(value: string, policy: BffQueryFieldPolicy): boolean {
  if (value.length === 0 || value.length > (policy.maxLength ?? 128)) {
    return false;
  }
  if (policy.type === "uuid") {
    return UUID_QUERY.test(value);
  }
  if (policy.type === "positive-int") {
    return POSITIVE_INT_QUERY.test(value);
  }
  if (policy.type === "non-negative-int") {
    return NON_NEGATIVE_INT_QUERY.test(value);
  }
  if (policy.type === "bounded-int") {
    if (!NON_NEGATIVE_INT_QUERY.test(value)) {
      return false;
    }
    const parsed = Number.parseInt(value, 10);
    const min = policy.min ?? 0;
    const max = policy.max ?? Number.MAX_SAFE_INTEGER;
    return parsed >= min && parsed <= max;
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

type BoundedBodyBytes =
  | { ok: true; bytes: Uint8Array }
  | { ok: false; status: 413 };

type CanonicalJsonBody =
  | { ok: true; body: string; parsed: unknown }
  | { ok: false; status: 400 };

/**
 * Read raw request bytes with a hard size cap before any decode/parse work.
 * Oversized or malformed Content-Length → 413.
 */
async function readRequestBodyBytesBounded(
  request: Request,
  maxBytes: number
): Promise<BoundedBodyBytes> {
  const declaredLength = contentLengthValue(request);
  if (Number.isNaN(declaredLength) || (declaredLength !== null && declaredLength > maxBytes)) {
    await request.body?.cancel().catch(() => undefined);
    return { ok: false, status: 413 };
  }
  if (!request.body) {
    return { ok: true, bytes: new Uint8Array(0) };
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
        return { ok: false, status: 413 };
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
  return { ok: true, bytes };
}

/**
 * JSON mutation body path: fatal UTF-8 → JSON.parse → JSON.stringify.
 * Duplicate object keys collapse to the last value (ECMA-262), and only the
 * normalized serialization is forwarded upstream. Raw ambiguous bytes never leave the BFF.
 * A leading UTF-8 BOM (U+FEFF) is stripped once after a successful fatal decode.
 */
export function canonicalizeJsonRequestBody(bytes: Uint8Array): CanonicalJsonBody {
  let text: string;
  try {
    text = new TextDecoder("utf-8", { fatal: true }).decode(bytes);
  } catch {
    return { ok: false, status: 400 };
  }
  if (text.length > 0 && text.charCodeAt(0) === 0xfeff) {
    text = text.slice(1);
  }
  try {
    const parsed: unknown = JSON.parse(text);
    return { ok: true, body: JSON.stringify(parsed), parsed };
  } catch {
    return { ok: false, status: 400 };
  }
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
  // F04: immutable config validation is synchronous and never connects to Redis. Cheap
  // request-shape denials below (malformed path, unknown route, invalid cookie syntax) all
  // return before the first Redis touch, which happens only in the session lookup.
  const configError = validateImmutableBffConfig();
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
      const csrfCookie = readSecurityCookie(request.headers.get("cookie"), BFF_CSRF_COOKIE, CSRF_COOKIE_POLICY);
      if (csrfCookie.status !== "valid" || !validateCsrf(request, csrfCookie.value)) {
        return safeJson(403);
      }
    }
  }

  let body: string | undefined;
  if (rule.kind === "mutation") {
    const requestContentType = request.headers.get("content-type")?.split(";")[0].trim().toLowerCase();
    // ONE bounded body read for the whole request. Session, permission, same-origin and CSRF were
    // all resolved above, so no body byte is consumed before authentication/authorization.
    const rawBody = await readRequestBodyBytesBounded(request, rule.maxBodyBytes);
    if (!rawBody.ok) {
      return safeJson(rawBody.status);
    }
    // High-authority quote mutations carry an additional route-specific business schema. It runs on
    // the SAME already-read bytes (no second read, no request cloning).
    const quoteKind = quoteMutationKind(segments, method);
    if (rawBody.bytes.byteLength === 0) {
      if (quoteKind) {
        // The quote-authority contract requires an explicit business-intent body.
        return safeJson(400);
      }
      // Empty body is allowed for other registered mutations (idempotent POST markers, etc.).
      body = undefined;
    } else {
      if (!rule.contentType || requestContentType !== rule.contentType) {
        return safeJson(415);
      }
      if (rule.contentType === "application/json") {
        if (quoteKind) {
          // One coherent quote path: fatal UTF-8 + duplicate-key rejection on the RAW bytes, then
          // the strict key allowlist (rule.bodyPolicy — the single allowlist), then the value
          // schema. Any failure returns before signing, so a rejected body makes zero Core calls.
          const parsed = parseStrictQuoteJsonBody(rawBody.bytes);
          if (!parsed.ok) {
            return safeJson(400);
          }
          if (rule.bodyPolicy && !bodyMatchesStrictPolicy(parsed.object, rule.bodyPolicy)) {
            return safeJson(400);
          }
          if (!validateQuoteMutationBody(quoteKind, parsed.object)) {
            return safeJson(400);
          }
          body = JSON.stringify(parsed.object);
        } else {
          const canonical = canonicalizeJsonRequestBody(rawBody.bytes);
          if (!canonical.ok) {
            return safeJson(canonical.status);
          }
          // Strict request-field allowlist (high-authority mutations only). A body carrying any
          // unknown / authority / server-state / prototype-pollution key fails closed here — before
          // signing or any upstream call — so a rejected body produces zero Core calls.
          if (rule.bodyPolicy && !bodyMatchesStrictPolicy(canonical.parsed, rule.bodyPolicy)) {
            return safeJson(400);
          }
          body = canonical.body;
        }
      } else {
        // Non-JSON mutation media types are not registered today; fail closed.
        return safeJson(415);
      }
    }
  }

  const query = validatedQuery(request.url, rule);
  if (query === null) {
    return safeJson(400);
  }

  // F01: resolve the Idempotency-Key against the route policy and canonical contract BEFORE
  // signing or any upstream call. A present-but-invalid key fails closed (400) — it is never
  // silently trimmed or dropped, which would downgrade a keyed mutation to an unkeyed one.
  const idempotency = resolveIdempotencyKey(request.headers.get("idempotency-key"), rule.idempotency);
  if (!idempotency.ok) {
    return safeJson(400);
  }
  const forwardedIdempotencyKey = idempotency.forward ? idempotency.value : null;

  const coreBaseUrl = validatedCoreApiInternalBaseUrl();
  const gatewaySecret = bffGatewaySharedSecret();
  if (!coreBaseUrl || !gatewaySecret) {
    return safeJson(503);
  }

  const corePath = corePathFromBffSegments(segments);
  // One source of truth: validate → build exact forwarded query → sign exact query → send exact query.
  const forwardedQuery = query;
  const rawQueryForSignature = forwardedQuery.startsWith("?") ? forwardedQuery.slice(1) : forwardedQuery;
  const target = new URL(corePath, `${coreBaseUrl}/`);
  target.search = forwardedQuery;
  const bodyBytes = body === undefined ? new Uint8Array(0) : Buffer.from(body, "utf8");
  const contentTypeForSignature =
    bodyBytes.byteLength === 0 ? "" : rule.contentType === "application/json" ? "application/json" : "";
  if (bodyBytes.byteLength > 0 && contentTypeForSignature !== "application/json") {
    return safeJson(415);
  }
  // Least privilege: sign only the matched route permission after session possession check.
  // Outbound headers are rebuilt from scratch — client-supplied gateway/signature/hash headers
  // are never forwarded (buildOutboundHeaders starts from the signed set only).
  let signed: Record<string, string>;
  try {
    signed = signGatewayHeaders({
      method,
      path: corePath,
      rawQuery: rawQueryForSignature,
      contentType: contentTypeForSignature,
      bodyBytes,
      tenantId: session.tenantId,
      actorId: session.actorId,
      permissions: [rule.permission],
      sharedSecret: gatewaySecret
    });
  } catch {
    return safeJson(503);
  }

  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), bffUpstreamTimeoutMs());
  let upstream: Response;
  try {
    upstream = await fetch(target, {
      method,
      body: bodyBytes.byteLength > 0 ? body : undefined,
      headers: buildOutboundHeaders(request, rule, signed, bodyBytes.byteLength > 0, forwardedIdempotencyKey),
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

/**
 * Synchronous, immutable BFF configuration validation (F04): shape/format only, and NO Redis
 * connection. This is the check the per-request hot path uses so that malformed paths, unknown
 * routes, and invalid session cookies are rejected cheaply — Redis is contacted only by the
 * session lookup, and only after the request has a valid route and a syntactically valid cookie.
 */
export function validateImmutableBffConfig(): string | null {
  if (bffRuntimeMode() !== "bff-production") {
    return null;
  }
  // Production sessions are opaque Redis IDs with server-side records — not HMAC session tokens.
  if (!bffGatewaySharedSecret()) {
    return "ORDERPILOT_GATEWAY_SHARED_SECRET must be exactly 64 hexadecimal characters (openssl rand -hex 32)";
  }
  if (!bffPublicOrigin()) {
    return "ORDERPILOT_PUBLIC_ORIGIN must be an exact origin URL (https in production-like profiles)";
  }
  if (!validatedCoreApiInternalBaseUrl()) {
    return "CORE_API_BASE_URL must be an exact https origin, loopback http origin (127.0.0.1 / [::1]), or private single-label Compose service http origin for BFF production mode";
  }
  try {
    bffGatewayClockSkewSeconds();
    bffUpstreamTimeoutMs();
  } catch (error) {
    return error instanceof Error ? error.message : "BFF numeric configuration is invalid";
  }
  const ttl = parseBffSessionMaxAgeSeconds(process.env.ORDERPILOT_BFF_SESSION_MAX_AGE_SECONDS, {
    allowDefaultOnMissing: true
  });
  if (!ttl.ok) {
    return ttl.reason;
  }
  return null;
}

/**
 * Full fail-closed configuration validation for BFF bootstrap (Node runtime only). Extends the
 * immutable check with a Redis availability probe for production-like deployments. Use this at
 * startup — NOT on the per-request denial path (see validateImmutableBffConfig / F04).
 */
export async function validateBffProductionConfig(): Promise<string | null> {
  const immutable = validateImmutableBffConfig();
  if (immutable) {
    return immutable;
  }
  if (bffRuntimeMode() !== "bff-production") {
    return null;
  }
  if (isProductionLikeDeployment()) {
    const redisError = await requireRedisSessionBackend();
    if (redisError) {
      return redisError;
    }
  }
  return null;
}
