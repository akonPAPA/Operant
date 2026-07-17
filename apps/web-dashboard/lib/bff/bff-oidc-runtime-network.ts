import "server-only";

import { isProductionLikeDeployment } from "./bff-deployment-profile.ts";

export const DEFAULT_OIDC_DISCOVERY_TIMEOUT_MS = 5_000;
export const DEFAULT_OIDC_DISCOVERY_MAX_BODY_BYTES = 64 * 1024;

export type OidcRuntimeNetworkErrorCode =
  | "OIDC_DISCOVERY_URL_INVALID"
  | "OIDC_DISCOVERY_TIMEOUT"
  | "OIDC_DISCOVERY_NETWORK_ERROR"
  | "OIDC_DISCOVERY_REDIRECT_REJECTED"
  | "OIDC_DISCOVERY_HTTP_ERROR"
  | "OIDC_DISCOVERY_RESPONSE_TOO_LARGE"
  | "OIDC_DISCOVERY_CONTENT_TYPE_INVALID";

const NETWORK_ERROR_MESSAGES: Record<OidcRuntimeNetworkErrorCode, string> = {
  OIDC_DISCOVERY_URL_INVALID: "OIDC discovery URL is invalid.",
  OIDC_DISCOVERY_TIMEOUT: "OIDC discovery timed out.",
  OIDC_DISCOVERY_NETWORK_ERROR: "OIDC discovery network request failed.",
  OIDC_DISCOVERY_REDIRECT_REJECTED: "OIDC discovery redirects are not allowed.",
  OIDC_DISCOVERY_HTTP_ERROR: "OIDC discovery returned an unsuccessful HTTP status.",
  OIDC_DISCOVERY_RESPONSE_TOO_LARGE: "OIDC discovery response exceeded the configured size limit.",
  OIDC_DISCOVERY_CONTENT_TYPE_INVALID: "OIDC discovery response must be JSON."
};

export class OidcRuntimeNetworkError extends Error {
  readonly code: OidcRuntimeNetworkErrorCode;

  constructor(code: OidcRuntimeNetworkErrorCode) {
    super(NETWORK_ERROR_MESSAGES[code]);
    this.name = "OidcRuntimeNetworkError";
    this.code = code;
  }
}

export type OidcDiscoveryFetch = (input: string | URL | Request, init?: RequestInit) => Promise<Response>;

export type BoundedOidcDiscoveryFetchOptions = {
  fetch?: OidcDiscoveryFetch;
  timeoutMs?: number;
  maxBodyBytes?: number;
};

function ipv4ToInt(host: string): number | null {
  if (!/^\d{1,3}(?:\.\d{1,3}){3}$/.test(host)) {
    return null;
  }
  const parts = host.split(".").map((part) => Number.parseInt(part, 10));
  if (parts.some((part) => !Number.isInteger(part) || part < 0 || part > 255)) {
    return null;
  }
  return parts.reduce((acc, part) => (acc << 8) + part, 0) >>> 0;
}

function ipv4InCidr(value: number, base: string, bits: number): boolean {
  const baseValue = ipv4ToInt(base);
  if (baseValue === null) {
    return false;
  }
  const mask = bits === 0 ? 0 : (0xffffffff << (32 - bits)) >>> 0;
  return (value & mask) === (baseValue & mask);
}

function deniedIpv4Literal(host: string): boolean {
  const value = ipv4ToInt(host);
  if (value === null) {
    return false;
  }
  return [
    ["0.0.0.0", 8],
    ["10.0.0.0", 8],
    ["100.64.0.0", 10],
    ["127.0.0.0", 8],
    ["169.254.0.0", 16],
    ["172.16.0.0", 12],
    ["192.0.0.0", 24],
    ["192.0.2.0", 24],
    ["192.168.0.0", 16],
    ["198.18.0.0", 15],
    ["198.51.100.0", 24],
    ["203.0.113.0", 24],
    ["224.0.0.0", 4],
    ["240.0.0.0", 4]
  ].some(([base, bits]) => ipv4InCidr(value, base as string, bits as number));
}

function deniedIpv6Literal(hostname: string): boolean {
  const host = hostname.toLowerCase().replace(/^\[|\]$/g, "");
  if (!host.includes(":")) {
    return false;
  }
  if (host.startsWith("::ffff:")) {
    return true;
  }
  return (
    host === "::" ||
    host === "::1" ||
    host.startsWith("fc") ||
    host.startsWith("fd") ||
    host.startsWith("fe80") ||
    host.startsWith("ff") ||
    host.startsWith("2001:db8")
  );
}

function unsafeDiscoveryHostname(hostname: string): boolean {
  const host = hostname.toLowerCase().replace(/^\[|\]$/g, "");
  return host === "localhost" || deniedIpv4Literal(host) || deniedIpv6Literal(host);
}

function parsedHttpsUrl(input: string | URL | Request): URL {
  const raw = typeof input === "string" ? input : input instanceof URL ? input.href : input.url;
  let parsed: URL;
  try {
    parsed = new URL(raw);
  } catch {
    throw new OidcRuntimeNetworkError("OIDC_DISCOVERY_URL_INVALID");
  }
  if (
    parsed.protocol !== "https:" ||
    parsed.username ||
    parsed.password ||
    parsed.hash ||
    parsed.port === "0" ||
    unsafeDiscoveryHostname(parsed.hostname)
  ) {
    throw new OidcRuntimeNetworkError("OIDC_DISCOVERY_URL_INVALID");
  }
  if (/[\x00-\x1f\x7f]/.test(raw)) {
    throw new OidcRuntimeNetworkError("OIDC_DISCOVERY_URL_INVALID");
  }
  return parsed;
}

function discoveryHeaders(headers: HeadersInit | undefined): Headers {
  const next = new Headers(headers);
  next.delete("authorization");
  next.delete("cookie");
  next.delete("set-cookie");
  next.set("accept", "application/json");
  return next;
}

function validateBoundedPositiveInteger(value: number, fallback: number): number {
  if (!Number.isSafeInteger(value) || value <= 0) {
    return fallback;
  }
  return value;
}

async function readResponseBytesBounded(response: Response, maxBodyBytes: number): Promise<Uint8Array> {
  const rawLength = response.headers.get("content-length");
  if (rawLength) {
    if (!/^[0-9]{1,12}$/.test(rawLength) || Number.parseInt(rawLength, 10) > maxBodyBytes) {
      await response.body?.cancel().catch(() => undefined);
      throw new OidcRuntimeNetworkError("OIDC_DISCOVERY_RESPONSE_TOO_LARGE");
    }
  }
  if (!response.body) {
    return new Uint8Array(0);
  }

  const reader = response.body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) {
        break;
      }
      total += value.byteLength;
      if (total > maxBodyBytes) {
        await reader.cancel().catch(() => undefined);
        throw new OidcRuntimeNetworkError("OIDC_DISCOVERY_RESPONSE_TOO_LARGE");
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

function safeResponseHeaders(response: Response): Headers {
  const headers = new Headers();
  const contentType = response.headers.get("content-type");
  if (contentType) {
    headers.set("content-type", contentType);
  }
  return headers;
}

export function createBoundedOidcDiscoveryFetch(
  options: BoundedOidcDiscoveryFetchOptions = {}
): OidcDiscoveryFetch {
  const baseFetch = options.fetch ?? fetch;
  const timeoutMs = validateBoundedPositiveInteger(options.timeoutMs ?? DEFAULT_OIDC_DISCOVERY_TIMEOUT_MS, DEFAULT_OIDC_DISCOVERY_TIMEOUT_MS);
  const maxBodyBytes = validateBoundedPositiveInteger(
    options.maxBodyBytes ?? DEFAULT_OIDC_DISCOVERY_MAX_BODY_BYTES,
    DEFAULT_OIDC_DISCOVERY_MAX_BODY_BYTES
  );

  return async (input, init = {}) => {
    const url = parsedHttpsUrl(input);
    const controller = new AbortController();
    const upstreamSignal = init.signal;
    const abortFromUpstream = () => controller.abort();
    if (upstreamSignal?.aborted) {
      controller.abort();
    } else {
      upstreamSignal?.addEventListener("abort", abortFromUpstream, { once: true });
    }
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    if (typeof timer === "object" && "unref" in timer) {
      (timer as { unref(): void }).unref();
    }

    let response: Response;
    try {
      response = await baseFetch(url.href, {
        ...init,
        method: "GET",
        body: undefined,
        cache: "no-store",
        credentials: "omit",
        redirect: "manual",
        headers: discoveryHeaders(init.headers),
        signal: controller.signal
      });
    } catch {
      if (controller.signal.aborted) {
        throw new OidcRuntimeNetworkError("OIDC_DISCOVERY_TIMEOUT");
      }
      throw new OidcRuntimeNetworkError("OIDC_DISCOVERY_NETWORK_ERROR");
    } finally {
      clearTimeout(timer);
      upstreamSignal?.removeEventListener("abort", abortFromUpstream);
    }

    if (response.status >= 300 && response.status < 400) {
      await response.body?.cancel().catch(() => undefined);
      throw new OidcRuntimeNetworkError("OIDC_DISCOVERY_REDIRECT_REJECTED");
    }
    if (!response.ok) {
      await response.body?.cancel().catch(() => undefined);
      throw new OidcRuntimeNetworkError("OIDC_DISCOVERY_HTTP_ERROR");
    }
    const contentType = response.headers.get("content-type")?.toLowerCase() ?? "";
    if (!contentType.startsWith("application/json")) {
      await response.body?.cancel().catch(() => undefined);
      throw new OidcRuntimeNetworkError("OIDC_DISCOVERY_CONTENT_TYPE_INVALID");
    }

    const bytes = await readResponseBytesBounded(response, maxBodyBytes);
    return new Response(bytes, {
      status: response.status,
      statusText: response.statusText,
      headers: safeResponseHeaders(response)
    });
  };
}
