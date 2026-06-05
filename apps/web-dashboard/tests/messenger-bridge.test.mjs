import { readFileSync } from "node:fs";
import { join } from "node:path";
import test from "node:test";
import assert from "node:assert/strict";

const root = process.cwd();
const apiClient = readFileSync(join(root, "lib", "channel-bot-api.ts"), "utf8");
const page = readFileSync(join(root, "app", "(dashboard)", "messenger-bridge", "page.tsx"), "utf8");
const navigation = readFileSync(join(root, "components", "navigation.ts"), "utf8");

test("messenger bridge client uses the read-only bridged-events endpoint with tenant scoping", () => {
  assert.match(apiClient, /getChannelBotEvents/);
  assert.match(apiClient, /\/api\/v1\/channels\/bot-events/);
  assert.match(apiClient, /X-Tenant-Id/);
  assert.match(apiClient, /ChannelBotEvent/);
});

test("messenger bridge client type declares no secret or raw token/payload fields", () => {
  // Guard against unsafe field declarations leaking into the response model.
  assert.doesNotMatch(apiClient, /secretRef|secretReference|botToken|webhookSecret/i);
  assert.doesNotMatch(apiClient, /rawPayloadJson|rawPayload\b|payloadHash/i);
});

test("messenger bridge route is registered in navigation", () => {
  assert.match(navigation, /Messenger Bridge/);
  assert.match(navigation, /\/messenger-bridge/);
});

test("messenger bridge page surfaces connection-to-conversation linkage and safety posture", () => {
  assert.match(page, /getChannelBotEvents/);
  assert.match(page, /Linked bot conversation/);
  assert.match(page, /Bot runtime status/);
  assert.match(page, /externalExecution=DISABLED/);
});

test("messenger bridge page renders empty and error states", () => {
  assert.match(page, /Backend data unavailable/);
  assert.match(page, /No bridged messenger events yet/);
});

test("messenger bridge page is read-only and exposes no secret values or action handlers", () => {
  // No secret/token field accessors are read from the event model.
  assert.doesNotMatch(page, /event\.(secret|token|secretRef|rawPayload)/i);
  // No interactive mutation/send mechanisms.
  assert.doesNotMatch(page, /onClick|<button|<form/i);
  // Honest read-only posture is stated.
  assert.match(page, /read-only/i);
});
