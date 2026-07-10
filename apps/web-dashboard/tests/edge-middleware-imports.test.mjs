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
  // sanity: the graph really was traversed beyond the entry file
  assert.ok(files.size >= 2, "expected middleware to import at least bff-config");
});

test("middleware performs no authoritative session validation", () => {
  const source = readFileSync(join(root, "middleware.ts"), "utf8");
  assert.doesNotMatch(source, /loadOperatorSession|parseSessionToken|signGatewayHeaders|createClient/);
  assert.match(source, /hasSessionCookie/);
  assert.match(source, /X-Content-Type-Options/);
});
