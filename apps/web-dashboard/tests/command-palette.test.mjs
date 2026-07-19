// WP3/WP5 — command palette proof.
//
// Compiles the REAL `components/command-palette.tsx` (stubbing ONLY next/navigation so no router
// context is required) and proves:
//  - the pure `filterPaletteEntries` search is bounded to the supplied registry entries (label /
//    section / synonym match; empty query returns all; no-match returns empty);
//  - the palette is closed by default at render (no dialog markup, no arbitrary URL/command input);
//  - the trigger exposes an accessible dialog affordance;
//  - the entries the shell derives from the WP1 registry NEVER include staff or customer routes.

import assert from "node:assert/strict";
import { createElement } from "react";
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { pathToFileURL } from "node:url";
import test from "node:test";
import ts from "typescript";
import { renderToStaticMarkup } from "react-dom/server";
import { paletteDestinations } from "../components/navigation-registry.ts";

const root = process.cwd();

function transpile(source, fileName) {
  return ts.transpileModule(source, {
    compilerOptions: {
      jsx: ts.JsxEmit.ReactJSX,
      module: ts.ModuleKind.ESNext,
      target: ts.ScriptTarget.ES2022
    },
    fileName
  }).outputText;
}

const tmpDir = mkdtempSync(join(root, ".wp5-palette-"));
test.after(() => rmSync(tmpDir, { recursive: true, force: true }));

const componentJs = transpile(readFileSync(join(root, "components", "command-palette.tsx"), "utf8"), "command-palette.tsx")
  .replace(/["']next\/navigation["']/g, '"./next-navigation-stub.mjs"');

writeFileSync(join(tmpDir, "next-navigation-stub.mjs"), "export function useRouter() { return { push() {} }; }\n");
writeFileSync(join(tmpDir, "command-palette.mjs"), componentJs);

const { filterPaletteEntries, CommandPalette } = await import(
  pathToFileURL(join(tmpDir, "command-palette.mjs")).href
);

const ENTRIES = [
  { id: "analytics", path: "/analytics", label: "Analytics", section: "Command Center", searchAliases: ["business value", "roi"] },
  { id: "quotes", path: "/quotes", label: "Draft Quotes", section: "Transactions", searchAliases: [] },
  { id: "audit", path: "/audit-log", label: "Audit / Security", section: "Control Center", searchAliases: [] }
];

test("empty query returns all entries (bounded to the registry)", () => {
  assert.equal(filterPaletteEntries(ENTRIES, "").length, ENTRIES.length);
  assert.equal(filterPaletteEntries(ENTRIES, "   ").length, ENTRIES.length);
});

test("filter matches label case-insensitively", () => {
  const r = filterPaletteEntries(ENTRIES, "draft");
  assert.deepEqual(r.map((e) => e.path), ["/quotes"]);
});

test("filter matches section", () => {
  const r = filterPaletteEntries(ENTRIES, "control center");
  assert.deepEqual(r.map((e) => e.path), ["/audit-log"]);
});

test("filter matches search synonyms (e.g. 'business value' -> Analytics)", () => {
  const r = filterPaletteEntries(ENTRIES, "business value");
  assert.deepEqual(r.map((e) => e.path), ["/analytics"]);
});

test("no-match query returns an empty result set", () => {
  assert.deepEqual(filterPaletteEntries(ENTRIES, "zzz-nonexistent"), []);
});

test("filter never returns a path outside the supplied entries (no arbitrary URLs)", () => {
  const allowed = new Set(ENTRIES.map((e) => e.path));
  for (const q of ["", "a", "e", "http", "/", "select"]) {
    for (const entry of filterPaletteEntries(ENTRIES, q)) {
      assert.ok(allowed.has(entry.path), `unexpected path ${entry.path}`);
    }
  }
});

test("palette is closed by default: trigger only, no dialog or free-form command input", () => {
  const html = renderToStaticMarkup(createElement(CommandPalette, { entries: ENTRIES }));
  assert.match(html, /command-trigger/);
  assert.match(html, /aria-haspopup="dialog"/);
  assert.match(html, /aria-expanded="false"/);
  // Closed dialog must not be in the DOM, so no combobox/listbox input is present at rest.
  assert.equal(html.includes('role="dialog"'), false);
  assert.equal(html.includes("command-input"), false);
});

test("shell-derived palette entries exclude staff and customer routes (plane safety)", () => {
  const entries = paletteDestinations("TENANT").map((d) => ({
    id: d.id,
    path: d.path,
    label: d.label,
    section: d.section,
    searchAliases: d.searchAliases
  }));
  const paths = entries.map((e) => e.path);
  assert.ok(paths.length > 0);
  assert.equal(paths.includes("/internal-support"), false, "staff route must not be in palette");
  assert.equal(paths.includes("/internal-support/operations"), false, "staff route must not be in palette");
  assert.equal(paths.includes("/public/order-tracking"), false, "customer route must not be in palette");
  // Every derived entry is a searchable, navigable tenant path.
  for (const entry of entries) {
    assert.match(entry.path, /^\//);
    assert.equal(typeof entry.label, "string");
  }
});
