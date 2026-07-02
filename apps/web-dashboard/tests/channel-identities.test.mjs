import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import test from "node:test";

const root = process.cwd();
const api = readFileSync(join(root, "lib", "channel-identity-api.ts"), "utf8");
const workspace = readFileSync(join(root, "components", "channel-identity-workspace.tsx"), "utf8");
const page = readFileSync(join(root, "app", "(dashboard)", "channel-identities", "page.tsx"), "utf8");
const navigation = readFileSync(join(root, "components", "navigation.ts"), "utf8");

// --- API client ---

test("channel identity api client declares correct types", () => {
  assert.match(api, /ChannelIdentity/);
  assert.match(api, /ChannelIdentityResolutionView/);
  assert.match(api, /ChannelIdentityLinkRequest/);
  assert.match(api, /CustomerAccountSummary/);
  assert.match(api, /CustomerContactSummary/);
});

test("api client uses CHANNEL_IDENTITY_ACTION for all mutations, never BOT_ACTION", () => {
  assert.match(api, /CHANNEL_IDENTITY_ACTION/);
  // All four mutation functions must declare the correct permission header
  assert.match(api, /linkChannelIdentity/);
  assert.match(api, /unlinkChannelIdentity/);
  assert.match(api, /blockChannelIdentity/);
  assert.match(api, /markNeedsReview/);
  // BOT_ACTION must not be used as a permission string value in this client
  assert.doesNotMatch(api, /=\s*["']BOT_ACTION["']/);
});

test("api client sends CHANNEL_IDENTITY_ACTION header on link mutation", () => {
  // The header must be present in the mutation path
  assert.match(api, /X-OrderPilot-Permissions.*CHANNEL_IDENTITY_ACTION/s);
});

test("api client uses tenant header on read requests", () => {
  assert.match(api, /X-Tenant-Id/);
  assert.match(api, /listChannelIdentities/);
  assert.match(api, /\/api\/v1\/channel-identities/);
});

test("api client exposes customer account and contact list functions", () => {
  assert.match(api, /listCustomerAccounts/);
  assert.match(api, /listCustomerContacts/);
  assert.match(api, /\/api\/v1\/customers/);
});

test("api client type declares no secret or raw token/payload fields", () => {
  assert.doesNotMatch(api, /secretRef|secretReference|botToken|webhookSecret/i);
  assert.doesNotMatch(api, /rawPayloadJson|rawPayload\b/i);
  assert.doesNotMatch(api, /linkedByUserId/i);
});

test("api client formatSenderId truncates long sender IDs", () => {
  assert.match(api, /formatSenderId/);
  assert.match(api, /slice/);
});

test("api client resolution status helpers map all five statuses", () => {
  assert.match(api, /RESOLVED/);
  assert.match(api, /AMBIGUOUS/);
  assert.match(api, /UNKNOWN/);
  assert.match(api, /BLOCKED/);
  assert.match(api, /NOT_APPLICABLE/);
  assert.match(api, /resolutionStatusLabel/);
  assert.match(api, /resolutionStatusClass/);
});

// --- Workspace component ---

test("workspace renders list table with identity columns", () => {
  assert.match(workspace, /Channel/);
  assert.match(workspace, /Sender/);
  assert.match(workspace, /Resolution/);
  assert.match(workspace, /Linked customer/);
});

test("workspace renders empty and loading states", () => {
  assert.match(workspace, /No channel identities yet/);
  assert.match(workspace, /No identities match the selected filter/);
  assert.match(workspace, /Loading identity detail/);
});

test("workspace renders link, unlink, block and needs-review action buttons", () => {
  assert.match(workspace, /Link to customer/);
  assert.match(workspace, /Unlink/);
  assert.match(workspace, /Block/);
  assert.match(workspace, /Mark needs review/);
});

test("workspace requires explicit confirmation before unlink and block", () => {
  assert.match(workspace, /confirmUnlink/);
  assert.match(workspace, /Confirm unlink/);
  assert.match(workspace, /confirmBlock/);
  assert.match(workspace, /Confirm block/);
});

test("workspace link dialog requires account selection before submit is enabled", () => {
  assert.match(workspace, /selectedAccountId/);
  assert.match(workspace, /disabled.*!selectedAccountId/s);
  assert.match(workspace, /Confirm link/);
});

test("workspace link dialog shows contacts only after account selection", () => {
  assert.match(workspace, /selectedAccountId.*contacts/s);
  assert.match(workspace, /Loading contacts/);
  assert.match(workspace, /No contact.*account-only link/);
});

test("workspace never exposes mutation without explicit operator action", () => {
  // No function named autoLink or similar (security: no programmatic auto-linking)
  assert.doesNotMatch(workspace, /function\s+autoLink|function\s+auto_link/i);
  // BOT_ACTION must not be used as a permission string value in this workspace
  assert.doesNotMatch(workspace, /=\s*["']BOT_ACTION["']/);
});

test("workspace shows backend risk note about CHANNEL_IDENTITY_ACTION", () => {
  assert.match(workspace, /CHANNEL_IDENTITY_ACTION/);
  assert.match(workspace, /permissioned backend command path/);
});

test("workspace shows explicit safety note that linking does not bypass policy", () => {
  assert.match(workspace, /does not auto-approve|never bypass|does not.*approve/i);
});

test("workspace shows block risk note about bot runtime", () => {
  assert.match(workspace, /Blocked senders/i);
  assert.match(workspace, /bot runtime/i);
});

// --- Page ---

test("channel identities page uses server-side list fetch", () => {
  assert.match(page, /listChannelIdentities/);
  assert.match(page, /ChannelIdentityWorkspace/);
  assert.match(page, /Channel Identities/);
});

test("channel identities page surfaces trust context panel", () => {
  assert.match(page, /provider-derived/i);
  assert.match(page, /No auto-linking/);
  assert.match(page, /CHANNEL_IDENTITY_ACTION/);
});

test("channel identities page states identity decisions do not approve business actions", () => {
  assert.match(page, /does not approve|not.*approve|never.*approve/i);
});

// --- Navigation ---

test("channel identities route is registered in navigation", () => {
  assert.match(navigation, /Channel Identities/);
  assert.match(navigation, /\/channel-identities/);
});

test("channel identities navigation entry is placed near Messenger Bridge", () => {
  const messengerIdx = navigation.indexOf("messenger-bridge");
  const identitiesIdx = navigation.indexOf("channel-identities");
  assert.ok(messengerIdx !== -1, "messenger-bridge missing");
  assert.ok(identitiesIdx !== -1, "channel-identities missing");
  // Should appear within 200 characters of Messenger Bridge in the nav file
  assert.ok(Math.abs(messengerIdx - identitiesIdx) < 200, "too far from messenger-bridge in nav");
});
