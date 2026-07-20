// WP5 — RUNTIME RENDER PROOF for the shared page-state language and StatusBadge.
//
// Compiles the REAL components (`components/page-states.tsx`, `components/status-badge.tsx`) with the
// installed TypeScript compiler (no new dependencies) and renders each with `react-dom/server`,
// asserting on the produced HTML. Proves: each state has the correct accessibility role, the states
// are semantically distinct (empty != error != unavailable != access-denied), status is never
// color-only (icon glyph + text present), and NO state primitive fabricates a mutation control
// (no <button>/<input>/<form>) — they are read-only presentational surfaces.

import assert from "node:assert/strict";
import { createElement } from "react";
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { pathToFileURL } from "node:url";
import test from "node:test";
import ts from "typescript";
import { renderToStaticMarkup } from "react-dom/server";

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

const tmpDir = mkdtempSync(join(root, ".wp5-states-"));
test.after(() => rmSync(tmpDir, { recursive: true, force: true }));

const statesSrc = readFileSync(join(root, "components", "page-states.tsx"), "utf8");
const badgeSrc = readFileSync(join(root, "components", "status-badge.tsx"), "utf8");

writeFileSync(join(tmpDir, "page-states.mjs"), transpile(statesSrc, "page-states.tsx"));
writeFileSync(join(tmpDir, "status-badge.mjs"), transpile(badgeSrc, "status-badge.tsx"));

const states = await import(pathToFileURL(join(tmpDir, "page-states.mjs")).href);
const { StatusBadge } = await import(pathToFileURL(join(tmpDir, "status-badge.mjs")).href);

const { LoadingState, EmptyState, ErrorState, UnavailableState, AccessDeniedState, Skeleton } = states;

function assertNoMutationControl(html, label) {
  for (const control of ["<button", "<input", "<form", "<select", "<textarea"]) {
    assert.equal(html.includes(control), false, `${label} must not contain ${control} (read-only)`);
  }
}

test("LoadingState is a polite live region with a screen-reader label", () => {
  const html = renderToStaticMarkup(createElement(LoadingState, { label: "Loading orders…" }));
  assert.match(html, /role="status"/);
  assert.match(html, /aria-live="polite"/);
  assert.match(html, /visually-hidden/);
  assert.match(html, /Loading orders…/);
  assertNoMutationControl(html, "LoadingState");
});

test("EmptyState is a status region, distinct from an error", () => {
  const html = renderToStaticMarkup(
    createElement(EmptyState, { title: "No documents", description: "Nothing to review yet." })
  );
  assert.match(html, /role="status"/);
  assert.match(html, /class="empty-state"/);
  assert.equal(html.includes('role="alert"'), false);
  assert.match(html, /No documents/);
  assertNoMutationControl(html, "EmptyState");
});

test("ErrorState is an alert and renders only the caller-supplied description", () => {
  const html = renderToStaticMarkup(
    createElement(ErrorState, { description: "The report could not be loaded. Try again." })
  );
  assert.match(html, /role="alert"/);
  assert.match(html, /page-state--error/);
  assert.match(html, /The report could not be loaded/);
  // No internal URL/stack leakage helper here — the component renders exactly what it is given.
  assertNoMutationControl(html, "ErrorState");
});

test("ErrorState renders a caller-supplied action node (never a fabricated control)", () => {
  const retry = createElement("a", { href: "/analytics" }, "Retry");
  const html = renderToStaticMarkup(
    createElement(ErrorState, { description: "Failed.", action: retry })
  );
  assert.match(html, /page-state-actions/);
  assert.match(html, /href="\/analytics"/);
});

test("UnavailableState is distinct from empty and access-denied", () => {
  const html = renderToStaticMarkup(
    createElement(UnavailableState, { description: "This area is not part of the current build." })
  );
  assert.match(html, /page-state--unavailable/);
  assert.equal(html.includes("page-state--denied"), false);
  assert.equal(html.includes('class="empty-state"'), false);
  assertNoMutationControl(html, "UnavailableState");
});

test("AccessDeniedState is distinct from unavailable and offers no retry-mutation", () => {
  const html = renderToStaticMarkup(createElement(AccessDeniedState, {}));
  assert.match(html, /page-state--denied/);
  assert.equal(html.includes("page-state--unavailable"), false);
  assertNoMutationControl(html, "AccessDeniedState");
});

test("Skeleton is decorative and hidden from assistive tech", () => {
  const html = renderToStaticMarkup(createElement(Skeleton, { width: 120 }));
  assert.match(html, /aria-hidden="true"/);
  assert.match(html, /class="skeleton"/);
});

test("StatusBadge conveys tone by glyph + text, never color alone, for every tone", () => {
  for (const [tone, word] of [
    ["neutral", "Status"],
    ["success", "Success"],
    ["warning", "Warning"],
    ["danger", "Error"],
    ["info", "Information"]
  ]) {
    const html = renderToStaticMarkup(createElement(StatusBadge, { tone, label: "Synced" }));
    // Non-color redundancy: a screen-reader tone word and a visible label are always present.
    assert.match(html, new RegExp(`${word}`), `${tone} badge must include the tone word`);
    assert.match(html, /Synced/, `${tone} badge must include the label`);
    assert.match(html, /status-badge-glyph/, `${tone} badge must include an icon glyph`);
    assert.match(html, /visually-hidden/);
  }
});

test("StatusBadge defaults to neutral tone", () => {
  const html = renderToStaticMarkup(createElement(StatusBadge, { label: "Idle" }));
  assert.match(html, /data-tone="neutral"/);
});
