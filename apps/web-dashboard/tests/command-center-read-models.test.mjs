import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import test from "node:test";
import { assertApiBaseUrlSource, assertTenantScopedClientSource } from "./lib/api-client-contract.mjs";

const root = process.cwd();
const apiClientPath = join(root, "lib", "command-center-api.ts");
const componentPath = join(root, "components", "operant-command-center.tsx");
const routePath = join(root, "app", "(dashboard)", "command-center", "page.tsx");
const navPath = join(root, "components", "navigation.ts");
const brandPath = join(root, "lib", "brand.ts");

const apiClient = readFileSync(apiClientPath, "utf8");
const component = readFileSync(componentPath, "utf8");
const route = readFileSync(routePath, "utf8");

test("command center API client targets the read-only summary endpoint with tenant + permission boundary", () => {
  assert.equal(existsSync(apiClientPath), true);
  assert.match(apiClient, /\/api\/v1\/command-center\/summary/);
  assertTenantScopedClientSource(apiClient);
  assertApiBaseUrlSource(apiClient);
  assert.match(apiClient, /ANALYTICS_READ/);
  // Read-only client: GET only, no mutation verbs.
  assert.doesNotMatch(apiClient, /method:\s*"(POST|PUT|PATCH|DELETE)"/);
});

test("command center component renders API-backed metric, queue, runtime, audit and reconciliation sections", () => {
  assert.equal(existsSync(componentPath), true);
  assert.match(component, /getCommandCenterSummary/);
  assert.match(component, /Command Center metrics/);
  assert.match(component, /Work queue preview/);
  assert.match(component, /Runtime &amp; outbox health/);
  assert.match(component, /Audit timeline preview/);
  assert.match(component, /Reconciliation preview/);
});

test("command center component renders honest empty/partial/unavailable states", () => {
  assert.match(component, /not connected yet|projection not connected/i);
  assert.match(component, /No open review cases/);
  assert.match(component, /No audit events/);
  assert.match(component, /Unavailable/);
  assert.match(component, /projection unavailable/i);
});

test("command center component exposes no mutation or external-write controls", () => {
  assert.doesNotMatch(component, /sendMessage|approve quote|finalize order|executeConnector|<form|onSubmit/i);
});

test("audit preview surface does not reference raw metadata payloads", () => {
  assert.doesNotMatch(component, /metadata|payload/i);
  assert.doesNotMatch(apiClient, /\bmetadata\b/);
});

test("command center route wires the Operant component and keeps the shell", () => {
  assert.match(route, /OperantCommandCenter/);
  assert.match(route, /DashboardShell/);
});

test("navigation still renders Operant branding, not OrderPilot", () => {
  const brand = readFileSync(brandPath, "utf8");
  assert.match(brand, /Operant/);
  assert.doesNotMatch(brand, /OrderPilot/);
  // Technical permission identifiers remain in client helpers (BFF injects via gateway session).
  assert.match(apiClient, /ANALYTICS_READ/);
  assert.equal(existsSync(navPath), true);
});
