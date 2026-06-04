import { readFileSync } from "node:fs";
import { join } from "node:path";
import test from "node:test";
import assert from "node:assert/strict";

const root = process.cwd();
const apiClient = readFileSync(join(root, "lib", "bot-runtime-config-api.ts"), "utf8");
const page = readFileSync(join(root, "app", "(dashboard)", "bot-runtime", "page.tsx"), "utf8");
const workspace = readFileSync(join(root, "components", "bot-runtime-config-workspace.tsx"), "utf8");
const navigation = readFileSync(join(root, "components", "navigation.ts"), "utf8");

// --- API client ------------------------------------------------------------------------------------

test("config client targets the permissioned bot-runtime endpoints with tenant scoping", () => {
  assert.match(apiClient, /getBotRuntimeConfigurations/);
  assert.match(apiClient, /getBotRuntimeConfiguration\b/);
  assert.match(apiClient, /\/api\/v1\/bot-runtime\/configurations/);
  assert.match(apiClient, /X-Tenant-Id/);
  assert.match(apiClient, /BotRuntimeConfig\b/);
});

test("config client exposes update and reset writes via PUT and reset-defaults with BOT_ACTION", () => {
  assert.match(apiClient, /export function updateBotRuntimeConfiguration/);
  assert.match(apiClient, /export function resetBotRuntimeConfiguration/);
  assert.match(apiClient, /method:\s*"PUT"/);
  assert.match(apiClient, /reset-defaults/);
  assert.match(apiClient, /X-OrderPilot-Permissions/);
  assert.match(apiClient, /BOT_ACTION/);
  // Backend error message is surfaced (not swallowed) for the editor to display.
  assert.match(apiClient, /"message" in data/);
});

test("config client declares no token/secret/credential or raw payload fields", () => {
  assert.doesNotMatch(apiClient, /secretRef|secretReference|botToken|webhookSecret|accessToken|refreshToken|providerCredential/i);
  assert.doesNotMatch(apiClient, /rawPayload/i);
  // The mutable update payload type carries no tenant id field (tenant is resolved server-side from
  // the X-Tenant-Id header). Note: reading botRuntimeConfigClient.tenantId for that header is expected.
  assert.doesNotMatch(apiClient, /BotRuntimeConfigUpdate = \{[^}]*tenantId/);
});

// --- Page (server loader + safety posture) ---------------------------------------------------------

test("page renders the editor and the safety posture, with no input fields of its own", () => {
  assert.match(page, /Bot Runtime Configuration/);
  assert.match(page, /BotRuntimeConfigWorkspace/);
  assert.match(page, /externalExecution=DISABLED/);
  assert.match(page, /can only constrain/i);
  assert.match(page, /cannot approve final orders/i);
  assert.match(page, /ERP\/1C/);
  // The server page itself renders no editable inputs or secret fields.
  assert.doesNotMatch(page, /<input|<textarea|type="password"/i);
});

test("bot runtime route stays registered in navigation", () => {
  assert.match(navigation, /Bot Runtime/);
  assert.match(navigation, /\/bot-runtime/);
});

// --- Editor workspace ------------------------------------------------------------------------------

test("workspace is a client component wired to the read + write API", () => {
  assert.match(workspace, /^"use client";/);
  assert.match(workspace, /getBotRuntimeConfiguration\b/);
  assert.match(workspace, /updateBotRuntimeConfiguration/);
  assert.match(workspace, /resetBotRuntimeConfiguration/);
});

test("workspace uses the exact backend enum values for every editable policy", () => {
  for (const mode of ["DISABLED", "OPERATOR_REVIEW_ONLY", "CONTROLLED_DRAFT", "CONTROLLED_RESPONSE"]) {
    assert.match(workspace, new RegExp(`"${mode}"`));
  }
  for (const visibility of ["NEVER", "IDENTIFIED_CUSTOMER_ONLY", "AUTHORIZED_CUSTOMER_ONLY"]) {
    assert.match(workspace, new RegExp(`"${visibility}"`));
  }
  for (const unknown of ["HANDOFF", "SAFE_GENERIC_REPLY", "REJECT"]) {
    assert.match(workspace, new RegExp(`"${unknown}"`));
  }
  for (const freshness of ["STRICT", "WARN_AND_HANDOFF", "ALLOW_WITH_WARNING"]) {
    assert.match(workspace, new RegExp(`"${freshness}"`));
  }
});

test("workspace renders a connection selector and controls for every supported flow", () => {
  assert.match(workspace, /selectConnection/);
  assert.match(workspace, /<select/);
  assert.match(workspace, /Greeting/);
  assert.match(workspace, /Availability check/);
  assert.match(workspace, /Price check/);
  assert.match(workspace, /RFQ capture/);
  assert.match(workspace, /Substitute suggestion/);
  assert.match(workspace, /Order status/);
  assert.match(workspace, /Human handoff/);
});

test("workspace edits global safety policies", () => {
  assert.match(workspace, /priceVisibilityPolicy/);
  assert.match(workspace, /unknownCustomerMode/);
  assert.match(workspace, /inventoryFreshnessPolicy/);
  assert.match(workspace, /handoffQueueKey/);
});

test("save builds a backend-shaped payload that preserves unchanged fields and omits non-mutable data", () => {
  // The payload includes the exact mutable DTO fields.
  for (const field of [
    "enabled", "greetingEnabled", "availabilityCheckEnabled", "priceCheckMode", "rfqCaptureMode",
    "substituteSuggestionMode", "orderStatusMode", "unknownCustomerMode", "humanHandoffEnabled",
    "handoffQueueKey", "inventoryFreshnessMaxMinutes", "inventoryFreshnessPolicy", "priceVisibilityPolicy",
    "safeGreetingTemplate", "safeFallbackTemplate", "handoffTemplate"
  ]) {
    assert.match(workspace, new RegExp(`${field}:`));
  }
  assert.match(workspace, /updateBotRuntimeConfiguration\(\s*draft\.channelConnectionId,\s*buildUpdatePayload\(draft\)\)/);
  // Non-mutable / server-owned fields are never part of the update payload.
  assert.doesNotMatch(workspace, /tenantId/);
  assert.doesNotMatch(workspace, /buildUpdatePayload[\s\S]*revision:[\s\S]*\}/);
});

test("workspace implements dirty-state save UX", () => {
  assert.match(workspace, /isDirty/);
  assert.match(workspace, /configsEqual/);
  // Save is disabled when there are no changes (or while busy / on blocking error).
  assert.match(workspace, /disabled=\{!isDirty/);
  assert.match(workspace, /isSaving \? "Saving\.\.\." : "Save configuration"/);
});

test("workspace surfaces backend errors without discarding local edits", () => {
  assert.match(workspace, /status: "error"/);
  assert.match(workspace, /form-message/);
  // On save error we set an error message and return without overwriting the draft.
  assert.match(workspace, /Keep the user's local edits/);
});

test("workspace implements reset-to-defaults with explicit confirmation", () => {
  assert.match(workspace, /confirmingReset/);
  assert.match(workspace, /Reset to safe defaults/);
  assert.match(workspace, /Confirm reset/);
});

test("workspace shows an actionable empty state when no connection exists", () => {
  assert.match(workspace, /No bot-capable channel connection found/);
  assert.match(workspace, /\/channels/);
  assert.match(workspace, /\/messenger-bridge/);
});

test("editor exposes no token/secret fields and no unsafe action affordances", () => {
  // No secret/token/credential input fields or accessors.
  assert.doesNotMatch(workspace, /type="password"/i);
  assert.doesNotMatch(workspace, /secretRef|botToken|accessToken|refreshToken|providerCredential|webhookSecret/i);
  assert.doesNotMatch(workspace, /draft\.(secret|token|credential)/i);
  // No button affords outbound send, approval, execution, or external/ERP writes.
  // Scoped to <button> elements so it never collides with safety prose elsewhere.
  assert.doesNotMatch(workspace, /<button[^>]*>[^<]*(send|approve|execute|deploy|erp|1c)/i);
});

test("workspace states the safety boundaries in product language", () => {
  assert.match(workspace, /can only constrain/i);
  assert.match(workspace, /draft\/review-only/i);
  assert.match(workspace, /final authority/i);
  assert.match(workspace, /External writes require separate integration approval/i);
});
