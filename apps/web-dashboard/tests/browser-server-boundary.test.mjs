import assert from "node:assert/strict";
import test from "node:test";
import { closeSync, constants, fstatSync, lstatSync, openSync, readFileSync, readdirSync } from "node:fs";
import { dirname, isAbsolute, join, relative, resolve, basename } from "node:path";

const root = process.cwd();
const NOFOLLOW = process.platform === "win32" ? 0 : (constants.O_NOFOLLOW ?? 0);

function assertInsideRoot(path) {
  const absolute = resolve(path);
  const rel = relative(root, absolute);
  if (rel === "" || (!rel.startsWith("..") && !isAbsolute(rel))) return absolute;
  throw new Error(`refusing out-of-tree path: ${path}`);
}

function lstatNoSymlink(path) {
  const absolute = assertInsideRoot(path);
  const stat = lstatSync(absolute);
  if (stat.isSymbolicLink()) throw new Error(`refusing symlink path: ${relative(root, absolute)}`);
  return { absolute, stat };
}

function pathIsFile(path) {
  try {
    const { absolute } = lstatNoSymlink(path);
    const fd = openSync(absolute, constants.O_RDONLY | NOFOLLOW);
    try { return fstatSync(fd).isFile(); }
    finally { closeSync(fd); }
  } catch (error) {
    if (error?.code === "ENOENT" || error?.code === "ENOTDIR") return false;
    throw error;
  }
}

function pathIsDirectory(path) {
  try { return lstatNoSymlink(path).stat.isDirectory(); }
  catch (error) { if (error?.code === "ENOENT" || error?.code === "ENOTDIR") return false; throw error; }
}

function readTextNoSymlink(path) {
  const { absolute } = lstatNoSymlink(path);
  const fd = openSync(absolute, constants.O_RDONLY | NOFOLLOW);
  try {
    if (!fstatSync(fd).isFile()) throw new Error(`expected file: ${relative(root, absolute)}`);
    return readFileSync(fd, "utf8");
  } finally {
    closeSync(fd);
  }
}

/**
 * F06 â€” hard browser/server module separation.
 *
 * Build the TRANSITIVE import graph from every browser entry point ("use client" modules, the
 * browser transport, and browser API clients) and prove none of them can reach server-only runtime
 * configuration, secrets, Node crypto/buffer, Redis, or the gateway signer/key decoder. This is a
 * real module-graph check â€” not a single-file grep â€” so a deep transitive path is still caught.
 */

// Resolved files whose presence in a browser graph is a violation.
const FORBIDDEN_FILE_BASENAMES = new Set([
  "bff-config.ts",
  "bff-server-config.ts",
  "bff-gateway-signer.ts",
  "bff-gateway-key.ts",
  "bff-session-store.ts",
  "bff-session.ts",
  "bff-auth-handlers.ts",
  "bff-oidc-config.ts",
  "bff-oidc-runtime.ts",
  "bff-oidc-runtime-network.ts",
  "bff-oidc-auth-code.ts",
  "bff-oidc-identity-mapping.ts",
  "bff-oidc-transaction-store.ts",
  "bff-oidc-readiness.ts",
  "bff-proxy.ts",
  "bff-server-read.ts",
  "bff-in-process-request.ts",
  "dashboard-server-bff-fetch.ts",
  "dashboard-server-bff-transport.ts"
]);

// External specifiers that must never appear in a browser graph.
const FORBIDDEN_EXTERNAL = ["redis", "server-only", "openid-client", "oauth4webapi", "jose"];

// Source-level markers that must never appear in a reachable browser file.
const FORBIDDEN_SOURCE = [
  { re: /\bnew\s+Buffer\b/, name: "Buffer constructor" },
  { re: /\bBuffer\s*\./, name: "Buffer usage" },
  { re: /ORDERPILOT_GATEWAY_SHARED_SECRET/, name: "gateway secret env" },
  { re: /ORDERPILOT_GATEWAY_HEADER_AUTH_SHARED_SECRET/, name: "gateway secret env" },
  { re: /ORDERPILOT_BFF_REDIS_URL/, name: "redis url env" }
];

function resolveLocal(fromFile, specifier) {
  let base;
  if (specifier.startsWith("@/")) {
    base = join(root, specifier.slice(2));
  } else if (specifier.startsWith(".")) {
    base = resolve(dirname(fromFile), specifier);
  } else {
    return { external: specifier };
  }
  for (const candidate of [
    base,
    `${base}.ts`,
    `${base}.tsx`,
    `${base}.mts`,
    `${base}.mjs`,
    join(base, "index.ts"),
    join(base, "index.tsx")
  ]) {
    if (pathIsFile(candidate)) {
      return { file: candidate };
    }
  }
  return { unresolved: specifier };
}

// Match runtime imports/re-exports/static dynamic imports; skip fully type-only imports.
const IMPORT_RE = /(?:^|\n)\s*(?:import|export)(?!\s+type\b)[^;]*?from\s*["']([^"']+)["']|import\(\s*["']([^"']+)["']\s*\)/g;

function collectGraph(rootFiles) {
  const visited = new Set();
  const externals = new Set();
  const queue = [...rootFiles];
  while (queue.length > 0) {
    const file = queue.pop();
    if (visited.has(file)) {
      continue;
    }
    visited.add(file);
    const source = readTextNoSymlink(file);
    IMPORT_RE.lastIndex = 0;
    let match;
    while ((match = IMPORT_RE.exec(source)) !== null) {
      const specifier = match[1] ?? match[2];
      if (!specifier) {
        continue;
      }
      const resolved = resolveLocal(file, specifier);
      if (resolved.file) {
        if (!visited.has(resolved.file)) {
          queue.push(resolved.file);
        }
      } else if (resolved.external) {
        externals.add(resolved.external);
      }
    }
  }
  return { files: visited, externals };
}

function walk(dir, out = []) {
  for (const entry of readdirSync(dir)) {
    if (entry === "node_modules" || entry === ".next" || entry === "dist") {
      continue;
    }
    const full = join(dir, entry);
    const st = lstatNoSymlink(full).stat;
    if (st.isDirectory()) {
      walk(full, out);
    } else if (/\.(ts|tsx|mts|mjs)$/.test(entry)) {
      out.push(full);
    }
  }
  return out;
}

function browserEntryPoints() {
  const roots = new Set();
  // Explicit browser transport + csrf modules.
  for (const rel of [
    "lib/api-transport.ts",
    "lib/dashboard-http.browser.ts",
    "lib/browser-csrf-cookie.ts",
    "lib/dashboard-fetch-headers.ts"
  ]) {
    const abs = join(root, rel);
    if (pathIsFile(abs)) {
      roots.add(abs);
    }
  }
  // Every "use client" module and every browser API client (lib/*-api.ts, excluding *.server.ts).
  for (const dir of ["app", "components", "lib"]) {
    const abs = join(root, dir);
    if (!pathIsDirectory(abs)) {
      continue;
    }
    for (const file of walk(abs)) {
      if (file.endsWith(".server.ts")) {
        continue;
      }
      const source = readTextNoSymlink(file);
      const isUseClient = /^\s*["']use client["']/m.test(source);
      const isBrowserApiClient = /[/\\]lib[/\\][^/\\]*-api\.ts$/.test(file);
      if (isUseClient || isBrowserApiClient) {
        roots.add(file);
      }
    }
  }
  return [...roots];
}

test("F06: no browser entry point transitively reaches server-only config, secrets, or Node crypto/buffer", () => {
  const roots = browserEntryPoints();
  assert.ok(roots.length >= 5, `expected several browser entry points, found ${roots.length}`);
  const { files, externals } = collectGraph(roots);

  for (const external of externals) {
    for (const forbidden of FORBIDDEN_EXTERNAL) {
      assert.ok(external !== forbidden && !external.startsWith(`${forbidden}/`), `browser graph imports "${external}"`);
    }
    assert.ok(!external.startsWith("node:"), `browser graph imports Node builtin "${external}"`);
  }

  for (const file of files) {
    const name = basename(file);
    assert.ok(
      !FORBIDDEN_FILE_BASENAMES.has(name),
      `browser graph transitively reaches server-only module ${file.replaceAll("\\", "/")}`
    );
    const source = readTextNoSymlink(file);
    for (const { re, name: marker } of FORBIDDEN_SOURCE) {
      assert.ok(!re.test(source), `browser-reachable ${file.replaceAll("\\", "/")} contains ${marker}`);
    }
  }
});

test("F06: bff-public-config has no Node/secret imports and is the browser constant source", () => {
  const source = readTextNoSymlink(join(root, "lib/bff/bff-public-config.ts"));
  // Check actual imports/usage (not doc-comment prose).
  assert.doesNotMatch(source, /from\s+["'][^"']*(gateway-key|gateway-signer|bff-session-store|bff-config)["']/);
  assert.doesNotMatch(source, /from\s+["'](redis|node:[a-z]+)["']/);
  assert.doesNotMatch(source, /import\s+["']server-only["']/);
  assert.doesNotMatch(source, /\bBuffer\s*[.(]/);
  assert.match(source, /BFF_SESSION_COOKIE|BFF_CSRF_COOKIE|BFF_CSRF_HEADER/);
  // No duplicate constant declarations: bff-config re-exports rather than redeclaring them.
  const configSource = readTextNoSymlink(join(root, "lib/bff/bff-config.ts"));
  assert.doesNotMatch(configSource, /export const BFF_SESSION_COOKIE\s*=/);
  assert.match(configSource, /from "\.\/bff-public-config\.ts"/);
});
