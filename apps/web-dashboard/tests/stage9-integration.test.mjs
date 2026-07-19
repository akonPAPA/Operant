import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import test from "node:test";

const root = process.cwd();
const apiPath = join(root, "lib", "stage9-integration-api.ts");
const api = readFileSync(apiPath, "utf8");
const page = readFileSync(join(root, "app", "(dashboard)", "integrations", "page.tsx"), "utf8");
const controlPath = join(root, "components", "integration-control.tsx");
const control = readFileSync(controlPath, "utf8");
const queue = readFileSync(join(root, "components", "change-request-queue.tsx"), "utf8");
const syncRuns = readFileSync(join(root, "components", "connector-sync-runs.tsx"), "utf8");
const auditTimeline = readFileSync(join(root, "components", "connector-audit-timeline.tsx"), "utf8");

test("stage9 integration API client exposes tenant-scoped control endpoints", () => {
  assert.equal(existsSync(apiPath), true);
  assert.match(api, /\/api\/stage9\/integrations/);
  assert.match(api, /\/api\/stage9\/change-requests/);
  assert.match(api, /\/api\/stage9\/connector-sync-runs/);
  assert.match(api, /\/api\/stage9\/connectors\/policies/);
  assert.match(api, /\/api\/stage9\/connector-audit/);
  assert.match(api, /dashboardRequestHeaders\(stage9IntegrationConfig\.tenantId\)/);
  // Wave 01H Category D: the change-request type is operator-safe — no internal connector/execution
  // machinery declared in the contract (field declarations checked, not prose).
  assert.doesNotMatch(api, /executionStatus\s*:/);
  assert.doesNotMatch(api, /connectorFailureType\s*\??\s*:/);
  assert.doesNotMatch(api, /connectorRetryable\s*:/);
  assert.doesNotMatch(api, /connectorIdempotencyKeyHash\s*\??\s*:/);
});

test("integrations page is unsupported and does not mount IntegrationControl", () => {
  assert.equal(existsSync(controlPath), true);
  assert.match(page, /UnavailableState/);
  assert.doesNotMatch(page, /IntegrationControl/);
  assert.match(control, /Integration Control/);
  assert.match(control, /Demo ERP connection/);
  assert.match(queue, /ChangeRequest queue/);
  assert.match(syncRuns, /Connector Sync Runs/);
  assert.match(syncRuns, /Connector audit timeline/);
  assert.match(auditTimeline, /Connector Audit Timeline/);
  assert.match(queue, /External reference/);
  // Wave 01H Category D: the queue shows the safe business `status` rollup, not raw connector internals.
  assert.match(queue, /request\.status/);
});

test("stage9 integration UI preserves external write safety boundary", () => {
  assert.match(control, /Demo ERP only/);
  assert.match(control, /Execution mode/);
  assert.match(control, /Production writes/);
  assert.match(control, /Production connector disabled/);
  assert.doesNotMatch(control, /credentialStatus|maskedCredentialRef|capabilities/);
  // Wave 01H Category D: the queue no longer surfaces raw connector retry/execution internals; it
  // states the external-execution-disabled safety boundary in business terms.
  assert.match(queue, /External execution stays disabled/);
  assert.doesNotMatch(queue, /connectorRetryable|connectorFailureType|executionStatus/);
  assert.match(control, /Network calls/);
  assert.match(control, /Disabled/);
  assert.match(control, /without production ERP or 1C writes/);
  assert.match(queue, /Only approved validation-backed draft quote\/order ChangeRequests can execute/);
  assert.doesNotMatch(control + queue + syncRuns + auditTimeline, /sendMessage|reserveInventory|production 1C write|real ERP write|secretValue|connectorIdempotencyKey\)/i);
});
