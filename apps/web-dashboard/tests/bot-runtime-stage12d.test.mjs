import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import test from "node:test";

const root = process.cwd();
const settingsPage = readFileSync(join(root, "app/(dashboard)/bot-settings/page.tsx"), "utf8");
const settingsWorkspace = readFileSync(join(root, "components/bot-settings-workspace.tsx"), "utf8");
const api = readFileSync(join(root, "lib/bot-runtime-api.ts"), "utf8");
const conversations = readFileSync(join(root, "components/bot-conversations-workspace.tsx"), "utf8");

test("bot settings render connection and allowed flows", () => {
  assert.match(settingsPage, /Controlled Bot Runtime/);
  assert.match(settingsWorkspace, /Telegram Runtime/);
  assert.match(settingsWorkspace, /Allowed Flows/);
  assert.match(settingsWorkspace, /CHECK_AVAILABILITY/);
  assert.match(settingsWorkspace, /REQUEST_QUOTE/);
});

test("disabled flow and policy denial states render clearly", () => {
  assert.match(settingsWorkspace, /Disabled/);
  assert.match(settingsWorkspace, /backend policy/);
  assert.doesNotMatch(settingsWorkspace, /AI agent online/i);
  assert.doesNotMatch(settingsWorkspace, /auto-approve/i);
});

test("handoff queue renders reason status and source context", () => {
  assert.match(settingsWorkspace, /Handoff Queue/);
  assert.match(settingsWorkspace, /handoff.reason/);
  assert.match(settingsWorkspace, /handoff.status/);
  assert.match(settingsWorkspace, /channel message/);
  assert.match(api, /listBotHandoffs/);
});

test("conversation audit renders linked quote review context", () => {
  assert.match(settingsWorkspace, /Conversation Audit/);
  assert.match(settingsWorkspace, /Open linked review/);
  assert.match(settingsWorkspace, /linkedReviewCaseId/);
  assert.match(conversations, /Create operator review handoff/);
});
