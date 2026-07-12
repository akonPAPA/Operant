import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import test from "node:test";

const root = process.cwd();

/**
 * P1-B: Edge Middleware must contain no Node-runtime-only imports — directly or
 * transitively. The forbidden set mirrors the boundary spec: bff-proxy,
 * bff-session-store, bff-gateway-signer, node:* builtins, and redis.
 */
const FORBIDDEN_SPECIFIERS = [
  "bff-proxy",
  "bff-session-store",
  "bff-gateway-signer",
  "bff-session",
  "bff-auth-handlers",
  "bff-config",
  "bff-deployment-profile",
  "redis",
  "next/headers"
];

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
      `middleware graph file must not use require/dynamic import: ${file}`
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

test("middleware import graph contains no Node-only or session/proxy/signer modules", () => {
  const { files, specifiers } = collectImportGraph(join(root, "middleware.ts"));
  for (const specifier of specifiers) {
    assert.ok(
      !specifier.startsWith("node:"),
      `middleware must not import Node builtin: ${specifier}`
    );
    for (const forbidden of FORBIDDEN_SPECIFIERS) {
      assert.ok(
        !specifier.includes(forbidden),
        `middleware must not import ${forbidden} (found ${specifier})`
      );
    }
  }
  for (const file of files) {
    const normalized = file.replaceAll("\\", "/");
    for (const forbidden of ["bff-proxy", "bff-session-store", "bff-gateway-signer", "bff-auth-handlers"]) {
      assert.ok(
        !normalized.includes(forbidden),
        `middleware transitively reaches ${forbidden}: ${normalized}`
      );
    }
  }
  assert.equal(files.size, 1, "middleware should not import server-only BFF modules");
});

test("middleware session cookie name stays aligned with BFF config without importing it", () => {
  const middlewareSource = readFileSync(join(root, "middleware.ts"), "utf8");
  const configSource = readFileSync(join(root, "lib/bff/bff-config.ts"), "utf8");
  const cookie = middlewareSource.match(/const BFF_SESSION_COOKIE = "([^"]+)"/);
  assert.ok(cookie, "middleware must define BFF_SESSION_COOKIE locally");
  assert.match(configSource, new RegExp(`export const BFF_SESSION_COOKIE = "${cookie[1]}"`));
  assert.match(middlewareSource, /middlewareBffRuntimeEnabled/);
  assert.doesNotMatch(middlewareSource, /request\.(headers|cookies|nextUrl|url).*ORDERPILOT_BFF|ORDERPILOT_DEPLOY/);
  assert.match(middlewareSource, /process\.env\.ORDERPILOT_BFF_ENABLED/);
  assert.match(middlewareSource, /process\.env\.NODE_ENV/);
});

test("middleware performs no authoritative session validation", () => {
  const source = readFileSync(join(root, "middleware.ts"), "utf8");
  assert.doesNotMatch(source, /loadOperatorSession|parseSessionToken|signGatewayHeaders|createClient/);
  assert.match(source, /hasSessionCookie/);
  assert.match(source, /X-Content-Type-Options/);
});

test("middleware returns JSON for unauthenticated API paths and redirects only page navigation", () => {
  const source = readFileSync(join(root, "middleware.ts"), "utf8");
  assert.match(source, /pathname\.startsWith\("\/api\/"\)/);
  assert.match(source, /NextResponse\.json\(/);
  assert.match(source, /status:\s*401/);
  const denialBlock = source.slice(source.indexOf('if (!hasSessionCookie'), source.indexOf('return response;', source.indexOf('if (!hasSessionCookie')));
  assert.match(denialBlock, /NextResponse\.json\(/);
  assert.match(denialBlock, /NextResponse\.redirect\(login\)/);
  assert.ok(
    denialBlock.indexOf('pathname.startsWith("/api/")') < denialBlock.indexOf('NextResponse.redirect(login)'),
    "API denial must be handled before the page redirect"
  );
});
