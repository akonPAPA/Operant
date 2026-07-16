import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import test from "node:test";

const root = process.cwd();

/**
 * P1-B: Edge entry (Next.js `proxy.ts` / legacy `middleware.ts`) must contain no
 * Node-runtime-only imports — directly or transitively.
 */
const FORBIDDEN_SPECIFIERS = [
  "bff-proxy",
  "bff-session-store",
  "bff-gateway-signer",
  "bff-session",
  "bff-auth-handlers",
  "bff-oidc-identity-mapping",
  "bff-oidc-config",
  "bff-oidc-runtime",
  "bff-oidc-runtime-network",
  "openid-client",
  "oauth4webapi",
  "jose",
  "bff-config",
  "bff-deployment-profile",
  "redis",
  "next/headers"
];

function edgeEntryFile() {
  const proxyPath = join(root, "proxy.ts");
  const middlewarePath = join(root, "middleware.ts");
  if (existsSync(proxyPath)) {
    return proxyPath;
  }
  if (existsSync(middlewarePath)) {
    return middlewarePath;
  }
  throw new Error("expected apps/web-dashboard/proxy.ts or middleware.ts");
}

function resolveLocal(fromFile, specifier) {
  let base;
  if (specifier.startsWith("@/")) {
    base = join(root, specifier.slice(2));
  } else if (specifier.startsWith(".")) {
    base = resolve(dirname(fromFile), specifier);
  } else {
    return null; // external package
  }
  for (const candidate of [base, `${base}.ts`, `${base}.tsx`, join(base, "index.ts")]) {
    if (existsSync(candidate) && !candidate.endsWith(`${root}`)) {
      try {
        readFileSync(candidate, "utf8");
        return candidate;
      } catch {
        /* directory */
      }
    }
  }
  return null;
}

function collectImportGraph(entryFile) {
  const visited = new Set();
  const specifiers = new Set();
  const queue = [entryFile];
  while (queue.length > 0) {
    const file = queue.pop();
    if (visited.has(file)) {
      continue;
    }
    visited.add(file);
    const source = readFileSync(file, "utf8");
    assert.doesNotMatch(
      source,
      /\brequire\s*\(|import\s*\(/,
      `edge entry graph file must not use require/dynamic import: ${file}`
    );
    const importPattern = /(?:import|export)\s[^;]*?from\s+["']([^"']+)["']|import\s+["']([^"']+)["']/g;
    for (const match of source.matchAll(importPattern)) {
      const specifier = match[1] ?? match[2];
      if (!specifier) {
        continue;
      }
      specifiers.add(specifier);
      const local = resolveLocal(file, specifier);
      if (local) {
        queue.push(local);
      }
    }
  }
  return { files: visited, specifiers };
}

test("edge entry import graph contains no Node-only or session/proxy/signer modules", () => {
  const { files, specifiers } = collectImportGraph(edgeEntryFile());
  for (const specifier of specifiers) {
    assert.ok(
      !specifier.startsWith("node:"),
      `edge entry must not import Node builtin: ${specifier}`
    );
    for (const forbidden of FORBIDDEN_SPECIFIERS) {
      assert.ok(
        !specifier.includes(forbidden),
        `edge entry must not import ${forbidden} (found ${specifier})`
      );
    }
  }
  for (const file of files) {
    const normalized = file.replaceAll("\\", "/");
    for (const forbidden of ["bff-proxy", "bff-session-store", "bff-gateway-signer", "bff-auth-handlers", "bff-oidc-identity-mapping", "bff-oidc-config", "bff-oidc-runtime", "bff-oidc-runtime-network"]) {
      assert.ok(
        !normalized.includes(forbidden),
        `edge entry transitively reaches ${forbidden}: ${normalized}`
      );
    }
  }
  // The edge entry may reach only known pure, Edge-safe local modules (F09 refactor moved the
  // header set + branch decision into lib/edge-middleware-core.ts, which imports safe-internal-path).
  const EDGE_SAFE_LOCAL = ["edge-middleware-core", "safe-internal-path"];
  for (const file of files) {
    const normalized = file.replaceAll("\\", "/");
    const allowed = normalized.endsWith("/proxy.ts") || EDGE_SAFE_LOCAL.some((m) => normalized.includes(`/lib/${m}.ts`));
    assert.ok(allowed, `edge entry reached an unexpected local module: ${normalized}`);
  }
});

test("edge entry session cookie name stays aligned with BFF config without importing it", () => {
  const edgeSource = readFileSync(edgeEntryFile(), "utf8");
  // F06: the canonical browser-safe constant lives in bff-public-config.ts (bff-config re-exports it).
  const configSource = readFileSync(join(root, "lib/bff/bff-public-config.ts"), "utf8");
  const cookie = edgeSource.match(/const BFF_SESSION_COOKIE = "([^"]+)"/);
  assert.ok(cookie, "edge entry must define BFF_SESSION_COOKIE locally");
  assert.match(configSource, new RegExp(`export const BFF_SESSION_COOKIE = "${cookie[1]}"`));
  assert.match(edgeSource, /middlewareBffRuntimeEnabled/);
  assert.doesNotMatch(edgeSource, /request\.(headers|cookies|nextUrl|url).*ORDERPILOT_BFF|ORDERPILOT_DEPLOY/);
  assert.match(edgeSource, /process\.env\.ORDERPILOT_BFF_ENABLED/);
  assert.match(edgeSource, /process\.env\.NODE_ENV/);
});

test("edge entry performs no authoritative session validation", () => {
  const source = readFileSync(edgeEntryFile(), "utf8");
  assert.doesNotMatch(source, /loadOperatorSession|parseSessionToken|signGatewayHeaders|createClient/);
  assert.match(source, /hasSessionCookie/);
  // F09: security headers are applied to EVERY branch via a single helper sourced from the pure core.
  assert.match(source, /applySecurityHeaders/);
  const coreSource = readFileSync(join(root, "lib/edge-middleware-core.ts"), "utf8");
  assert.match(coreSource, /X-Content-Type-Options/);
  assert.match(coreSource, /X-Frame-Options/);
  assert.match(coreSource, /Referrer-Policy/);
  assert.match(coreSource, /Permissions-Policy/);
  assert.match(coreSource, /Cache-Control/);
});

test("edge entry returns JSON for unauthenticated API paths and redirects only page navigation", () => {
  const source = readFileSync(edgeEntryFile(), "utf8");
  const coreSource = readFileSync(join(root, "lib/edge-middleware-core.ts"), "utf8");
  // The proxy materialises each decision: a bounded JSON 401 and a redirect, both header-wrapped.
  assert.match(source, /NextResponse\.json\(/);
  assert.match(source, /status:\s*401|"json-401"/);
  assert.match(source, /NextResponse\.redirect\(/);
  assert.match(source, /applySecurityHeaders\(\s*NextResponse\.json/);
  // The ordering invariant now lives in the pure decision function: a protected /api/ path resolves
  // to json-401 BEFORE the page-redirect branch.
  assert.match(coreSource, /pathname\.startsWith\("\/api\/"\)/);
  assert.ok(
    coreSource.indexOf('kind: "json-401"') < coreSource.indexOf('kind: "redirect"'),
    "API denial (json-401) must be decided before the page redirect"
  );
});
