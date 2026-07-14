import assert from "node:assert/strict";
import test from "node:test";
import {
  closeSync,
  constants,
  fstatSync,
  openSync,
  readFileSync,
  readdirSync
} from "node:fs";
import { dirname, join, resolve, basename } from "node:path";

const root = process.cwd();
const NO_FOLLOW = typeof constants.O_NOFOLLOW === "number" ? constants.O_NOFOLLOW : 0;
const SOURCE_CACHE = new Map();

/**
 * F06 — hard browser/server module separation.
 *
 * Build the TRANSITIVE import graph from every browser entry point ("use client" modules, the
 * browser transport, and browser API clients) and prove none of them can reach server-only runtime
 * configuration, secrets, Node crypto/buffer, Redis, or the gateway signer/key decoder. This is a
 * real module-graph check — not a single-file grep — so a deep transitive path is still caught.
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
  "bff-proxy.ts",
  "bff-server-read.ts",
  "bff-in-process-request.ts",
  "dashboard-server-bff-fetch.ts",
  "dashboard-server-bff-transport.ts"
]);

// External specifiers that must never appear in a browser graph.
const FORBIDDEN_EXTERNAL = ["redis", "server-only"];

// Source-level markers that must never appear in a reachable browser file.
const FORBIDDEN_SOURCE = [
  { re: /\bnew\s+Buffer\b/, name: "Buffer constructor" },
  { re: /\bBuffer\s*\./, name: "Buffer usage" },
  { re: /ORDERPILOT_GATEWAY_SHARED_SECRET/, name: "gateway secret env" },
  { re: /ORDERPILOT_GATEWAY_HEADER_AUTH_SHARED_SECRET/, name: "gateway secret env" },
  { re: /ORDERPILOT_BFF_REDIS_URL/, name: "redis url env" }
];

function isMissingOrNonRegular(error) {
  return Boolean(
    error
    && typeof error === "object"
    && ["ENOENT", "EISDIR", "ELOOP"].includes(error.code)
  );
}

/**
 * Open and read through one descriptor. The descriptor pins the object used by fstat/read, removing
 * the old exists/stat/read check-then-use race. O_NOFOLLOW blocks a symlink swap where supported.
 */
function readRegularUtf8(path) {
  if (SOURCE_CACHE.has(path)) {
    return SOURCE_CACHE.get(path);
  }

  let fd;
  try {
    fd = openSync(path, constants.O_RDONLY | NO_FOLLOW);
    if (!fstatSync(fd).isFile()) {
      SOURCE_CACHE.set(path, null);
      return null;
    }
    const source = readFileSync(fd, "utf8");
    SOURCE_CACHE.set(path, source);
    return source;
  } catch (error) {
    if (isMissingOrNonRegular(error)) {
      SOURCE_CACHE.set(path, null);
      return null;
    }
    throw error;
  } finally {
    if (fd !== undefined) {
      closeSync(fd);
    }
  }
}

function requireSource(path) {
  const source = readRegularUtf8(path);
  assert.notEqual(source, null, `expected readable regular source file ${path.replaceAll("\\", "/")}`);
  return source;
}

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
    if (readRegularUtf8(candidate) !== null) {
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
    const source = requireSource(file);
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
  const entries = readdirSync(dir, { withFileTypes: true });
  for (const entry of entries) {
    if (entry.name === "node_modules" || entry.name === ".next" || entry.name === "dist") {
      continue;
    }
    const full = join(dir, entry.name);
    if (entry.isDirectory()) {
      walk(full, out);
    } else if (entry.isFile() && /\.(ts|tsx|mts|mjs)$/.test(entry.name)) {
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
    if (readRegularUtf8(abs) !== null) {
      roots.add(abs);
    }
  }
  // Every "use client" module and every browser API client (lib/*-api.ts, excluding *.server.ts).
  for (const dir of ["app", "components", "lib"]) {
    const abs = join(root, dir);
    for (const file of walk(abs)) {
      if (file.endsWith(".server.ts")) {
        continue;
      }
      const source = requireSource(file);
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
    const source = requireSource(file);
    for (const { re, name: marker } of FORBIDDEN_SOURCE) {
      assert.ok(!re.test(source), `browser-reachable ${file.replaceAll("\\", "/")} contains ${marker}`);
    }
  }
});

test("F06: bff-public-config has no Node/secret imports and is the browser constant source", () => {
  const source = requireSource(join(root, "lib/bff/bff-public-config.ts"));
  // Check actual imports/usage (not doc-comment prose).
  assert.doesNotMatch(source, /from\s+["'][^"']*(gateway-key|gateway-signer|bff-session-store|bff-config)["']/);
  assert.doesNotMatch(source, /from\s+["'](redis|node:[a-z]+)["']/);
  assert.doesNotMatch(source, /import\s+["']server-only["']/);
  assert.doesNotMatch(source, /\bBuffer\s*[.(]/);
  assert.match(source, /BFF_SESSION_COOKIE|BFF_CSRF_COOKIE|BFF_CSRF_HEADER/);
  // No duplicate constant declarations: bff-config re-exports rather than redeclaring them.
  const configSource = requireSource(join(root, "lib/bff/bff-config.ts"));
  assert.doesNotMatch(configSource, /export const BFF_SESSION_COOKIE\s*=/);
  assert.match(configSource, /from "\.\/bff-public-config\.ts"/);
});
