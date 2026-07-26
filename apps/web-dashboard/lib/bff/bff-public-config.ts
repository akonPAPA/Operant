/**
 * F06 — browser-safe BFF configuration (public trust boundary).
 *
 * This module contains ONLY non-secret, deterministic constants and runtime-mode/env-shape helpers.
 * It has no Node-runtime imports (no node:crypto, node:buffer, Buffer, redis), no gateway key/secret
 * material, and no gateway signer. It is therefore safe to import from "use client" modules, the
 * browser transport, and the Edge graph. Server-only configuration (gateway secret, internal Core
 * URL, Redis, key decoding, cookie-Secure/origin validation) lives in bff-config.ts, which must never
 * be reachable from the browser import graph. A transitive-graph test enforces this boundary.
 *
 * Single source of truth: bff-config.ts re-exports these symbols so server code has one import site.
 */
import { isProductionNodeRuntime } from "./bff-deployment-profile.ts";

// Public cookie/header names (not secrets — the browser must read/attach these).
export const BFF_SESSION_COOKIE = "op_session";
export const BFF_CSRF_COOKIE = "op_csrf";
export const BFF_CSRF_HEADER = "X-OP-CSRF-Token";

export type BffRuntimeMode = "demo-dev" | "bff-production" | "unavailable";

function envFlag(name: string): string {
  // Bracket access avoids Next/SWC compile-time inlining of process.env.NAME patterns.
  return String(process.env[name] ?? "").trim();
}

function isDemoMode(): boolean {
  const privateDemo = envFlag("ORDERPILOT_DEMO_MODE");
  if (privateDemo === "true") {
    return true;
  }
  if (privateDemo === "false") {
    return false;
  }
  return envFlag("NEXT_PUBLIC_ORDERPILOT_DEMO_MODE") === "true";
}

function isBffEnabled(): boolean {
  return envFlag("ORDERPILOT_BFF_ENABLED") === "true";
}

export function bffRuntimeMode(): BffRuntimeMode {
  // Private ORDERPILOT_DEMO_MODE=false must win over a build-time-inlined NEXT_PUBLIC demo flag
  // so production artifacts and E2E can enable BFF even when .env.local baked demo=true into the build.
  if (isBffEnabled() && !isDemoMode()) {
    return "bff-production";
  }
  if (isProductionNodeRuntime() || process.env.NODE_ENV === "production") {
    return "unavailable";
  }
  return "demo-dev";
}

export type StrictIntegerResult =
  | { ok: true; value: number }
  | { ok: false; reason: string };

export function parseStrictBoundedInteger(
  raw: string | undefined,
  name: string,
  options: { defaultValue?: number; min: number; max: number }
): StrictIntegerResult {
  if (raw === undefined) {
    if (options.defaultValue === undefined) {
      return { ok: false, reason: `${name} is required` };
    }
    return { ok: true, value: options.defaultValue };
  }
  const trimmed = raw.trim();
  if (trimmed === "") {
    return { ok: false, reason: `${name} must be a decimal integer` };
  }
  if (!/^[0-9]+$/.test(trimmed)) {
    return { ok: false, reason: `${name} must be a decimal integer` };
  }
  const value = Number(trimmed);
  if (!Number.isSafeInteger(value)) {
    return { ok: false, reason: `${name} must be a safe integer` };
  }
  if (value < options.min) {
    return { ok: false, reason: `${name} must be at least ${options.min}` };
  }
  if (value > options.max) {
    return { ok: false, reason: `${name} must not exceed ${options.max}` };
  }
  return { ok: true, value };
}
