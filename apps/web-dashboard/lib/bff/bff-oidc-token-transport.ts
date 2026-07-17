import "server-only";

import type { OidcDiscoveryFetch } from "./bff-oidc-runtime-network.ts";
import type { OidcProviderRuntime } from "./bff-oidc-runtime.ts";

const DEFAULT_TIMEOUT_MS = 5_000;
const MAX_TIMEOUT_MS = 10_000;
const DEFAULT_MAX_BODY_BYTES = 128 * 1024;
const MAX_BODY_BYTES = 128 * 1024;

function boundedPositive(value: number | undefined, fallback: number, max: number): number {
  return value !== undefined && Number.isSafeInteger(value) && value > 0
    ? Math.min(value, max)
    : fallback;
}

async function readBounded(response: Response, maxBodyBytes: number): Promise<Uint8Array> {
  const length = response.headers.get("content-length");
  if (length && (!/^[0-9]{1,12}$/.test(length) || Number.parseInt(length, 10) > maxBodyBytes)) {
    await response.body?.cancel().catch(() => undefined);
    throw new TypeError("OIDC_RUNTIME_RESPONSE_TOO_LARGE");
  }
  if (!response.body) return new Uint8Array(0);
  const reader = response.body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      total += value.byteLength;
      if (total > maxBodyBytes) {
        await reader.cancel().catch(() => undefined);
        throw new TypeError("OIDC_RUNTIME_RESPONSE_TOO_LARGE");
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
  return bytes;
}

export function createOidcTokenRuntimeFetch(
  runtime: OidcProviderRuntime,
  baseFetch: OidcDiscoveryFetch,
  options: { timeoutMs?: number; maxBodyBytes?: number } = {}
): OidcDiscoveryFetch {
  const allowed = new Set([runtime.tokenEndpoint, runtime.jwksUri]);
  const timeoutMs = boundedPositive(options.timeoutMs, DEFAULT_TIMEOUT_MS, MAX_TIMEOUT_MS);
  const maxBodyBytes = boundedPositive(
    options.maxBodyBytes,
    DEFAULT_MAX_BODY_BYTES,
    MAX_BODY_BYTES
  );

  return async (input, init = {}) => {
    const raw = typeof input === "string" ? input : input instanceof URL ? input.href : input.url;
    let url: URL;
    try {
      url = new URL(raw);
    } catch {
      throw new TypeError("OIDC_RUNTIME_URL_INVALID");
    }
    if (
      !allowed.has(`${url.origin}${url.pathname}`) ||
      url.search ||
      url.hash ||
      url.username ||
      url.password
    ) {
      throw new TypeError("OIDC_RUNTIME_URL_DENIED");
    }
    const method = (init.method ?? "GET").toUpperCase();
    if (method !== "GET" && method !== "POST") throw new TypeError("OIDC_RUNTIME_METHOD_DENIED");

    const controller = new AbortController();
    const upstreamSignal = init.signal;
    const abort = () => controller.abort();
    if (upstreamSignal?.aborted) controller.abort();
    else upstreamSignal?.addEventListener("abort", abort, { once: true });
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    if (typeof timer === "object" && "unref" in timer) timer.unref();

    let response: Response;
    try {
      response = await baseFetch(url.href, {
        ...init,
        method,
        cache: "no-store",
        credentials: "omit",
        redirect: "manual",
        signal: controller.signal
      });
    } catch {
      throw new TypeError(controller.signal.aborted ? "OIDC_RUNTIME_TIMEOUT" : "OIDC_RUNTIME_NETWORK_ERROR");
    } finally {
      clearTimeout(timer);
      upstreamSignal?.removeEventListener("abort", abort);
    }

    if (response.status >= 300 && response.status < 400) {
      await response.body?.cancel().catch(() => undefined);
      throw new TypeError("OIDC_RUNTIME_REDIRECT_DENIED");
    }
    const contentType = response.headers.get("content-type")?.toLowerCase() ?? "";
    if (!contentType.startsWith("application/json")) {
      await response.body?.cancel().catch(() => undefined);
      throw new TypeError("OIDC_RUNTIME_CONTENT_TYPE_DENIED");
    }
    const bytes = await readBounded(response, maxBodyBytes);
    return new Response(bytes, {
      status: response.status,
      statusText: response.statusText,
      headers: { "content-type": contentType }
    });
  };
}
