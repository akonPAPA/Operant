import assert from "node:assert/strict";
import { readFileSync, readdirSync, statSync } from "node:fs";
import { join } from "node:path";
import test from "node:test";

const root = process.cwd();
const SERVER_ONLY_FILES = [
  "lib/bff/bff-oidc-login-handler.ts",
  "lib/bff/bff-oidc-callback-handler.ts",
  "lib/bff/bff-oidc-token-transport.ts",
  "lib/bff/bff-oidc-identity-types.ts",
  "lib/bff/bff-oidc-identity-record.ts",
  "lib/bff/bff-oidc-identity-source.ts"
];
const NODE_ONLY_HELPERS = [
  "lib/bff/bff-auth-cookie-writer.ts",
  "lib/bff/bff-oidc-browser-binding.ts"
];

function walk(directory, result = []) {
  for (const entry of readdirSync(directory)) {
    if (entry === "node_modules" || entry === ".next") continue;
    const path = join(directory, entry);
    if (statSync(path).isDirectory()) walk(path, result);
    else if (/\.(ts|tsx|mjs)$/.test(entry)) result.push(path);
  }
  return result;
}

test("new OIDC helpers remain outside browser entry graphs", () => {
  for (const relative of SERVER_ONLY_FILES) {
    const source = readFileSync(join(root, relative), "utf8");
    assert.match(source, /import "server-only";/, relative);
    assert.doesNotMatch(source, /NEXT_PUBLIC_/, relative);
  }
  const names = [...SERVER_ONLY_FILES, ...NODE_ONLY_HELPERS].map((path) => path.split("/").at(-1));
  for (const directory of ["app", "components"]) {
    for (const file of walk(join(root, directory))) {
      const source = readFileSync(file, "utf8");
      if (!/^\s*["']use client["'];/m.test(source)) continue;
      for (const name of names) {
        assert.doesNotMatch(source, new RegExp(name.replaceAll(".", "\\.")), file);
      }
    }
  }
});
