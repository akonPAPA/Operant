import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import test from "node:test";
import {
  PERMISSION_TO_UI_CAPABILITY,
  projectPermissionsToUiCapabilities,
  projectionFromPermissions
} from "../lib/ui-capability-model.ts";
import {
  assertSurfaceContractInvariants,
  findRegisteredBffRoute,
  surfaceContractForPath
} from "../lib/frontend-surface-contract.ts";

const ROOT = join(process.cwd());

test("QUOTE_ACTION maps only from backend QUOTE_ACTION permission", () => {
  assert.equal(PERMISSION_TO_UI_CAPABILITY.QUOTE_ACTION, "QUOTE_ACTION");
  assert.equal(PERMISSION_TO_UI_CAPABILITY.QUOTE_READ, "VIEW_QUOTES");
  const viewOnly = projectPermissionsToUiCapabilities(["QUOTE_READ"]);
  assert.ok(viewOnly.has("VIEW_QUOTES"));
  assert.equal(viewOnly.has("QUOTE_ACTION"), false);
  const withAction = projectPermissionsToUiCapabilities(["QUOTE_READ", "QUOTE_ACTION"]);
  assert.ok(withAction.has("VIEW_QUOTES"));
  assert.ok(withAction.has("QUOTE_ACTION"));
});

test("VIEW and review permissions do not imply QUOTE_ACTION", () => {
  const caps = projectPermissionsToUiCapabilities([
    "REVIEW_READ",
    "REVIEW_ACTION",
    "VALIDATION_READ",
    "ANALYTICS_READ"
  ]);
  assert.equal(caps.has("QUOTE_ACTION"), false);
});

test("unknown permissions fail closed for QUOTE_ACTION", () => {
  const caps = projectPermissionsToUiCapabilities(["QUOTE_MUTATE", "ADMIN_QUOTE_WRITE"]);
  assert.equal(caps.has("QUOTE_ACTION"), false);
});

test("quotes surface declares read and mutation BFF routes with split capability rules", () => {
  const surface = surfaceContractForPath("/quotes");
  assert.notEqual(surface, null);
  assert.equal(surface.requiredCapabilityRule.kind, "ANY_OF");
  assert.deepEqual(surface.requiredCapabilityRule.capabilities, ["VIEW_QUOTES"]);
  assert.equal(surface.mutationCapabilityRule?.kind, "ANY_OF");
  assert.deepEqual(surface.mutationCapabilityRule?.capabilities, ["QUOTE_ACTION"]);
  assert.ok(surface.mutationRoutes.length >= 5);
  assert.doesNotThrow(() => assertSurfaceContractInvariants(surface));
  for (const ref of surface.mutationRoutes) {
    const rule = findRegisteredBffRoute(ref);
    assert.equal(rule?.permission, "QUOTE_ACTION", ref.pattern);
  }
});

test("denied and empty projections offer no QUOTE_ACTION", () => {
  assert.equal(projectionFromPermissions(null).capabilities.includes("QUOTE_ACTION"), false);
  assert.equal(projectionFromPermissions([]).capabilities.includes("QUOTE_ACTION"), false);
});

test("quotes page server-gates read vs mutation capability projection", () => {
  const page = readFileSync(join(ROOT, "app/(dashboard)/quotes/page.tsx"), "utf8");
  assert.match(page, /loadUiCapabilityProjection/);
  assert.match(page, /VIEW_QUOTES/);
  assert.match(page, /QUOTE_ACTION/);
  assert.match(page, /canPerformQuoteAction/);
  assert.match(page, /UnavailableState/);
});

test("quote workspace mounts mutation controls only when canPerformQuoteAction", () => {
  const workspace = readFileSync(join(ROOT, "components/quote-workspace.tsx"), "utf8");
  assert.match(workspace, /canPerformQuoteAction/);
  assert.match(workspace, /Read-only quote workspace/);
  assert.match(workspace, /useOperatorAction/);
  assert.match(workspace, /mapOperatorActionError/);
  assert.match(workspace, /aria-live="polite"/);
  assert.match(workspace, /aria-busy=\{busy\}/);
  assert.doesNotMatch(workspace, /if \(loadingRef\.current\)/);
});

test("quote transaction API maps HTTP status to bounded BoundedUiError", () => {
  const api = readFileSync(join(ROOT, "lib/quote-transaction-api.ts"), "utf8");
  assert.match(api, /uiErrorForStatus/);
  assert.match(api, /new BoundedUiError\(mapped\.message, response\.status\)/);
  assert.doesNotMatch(api, /safeErrorMessage/);
});
