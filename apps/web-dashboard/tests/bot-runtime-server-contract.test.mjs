import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import test from "node:test";
import { listBotConversationDetailsWithReaders } from "../lib/bot-conversation-details.ts";

const testDir = dirname(fileURLToPath(import.meta.url));
const root = join(testDir, "..");
const controller = readFileSync(
  join(root, "..", "core-api", "src", "main", "java", "com", "orderpilot", "api", "rest", "BotRuntimeController.java"),
  "utf8"
);

function summary(id, updatedAt = "2026-01-01T00:00:00Z") {
  return {
    id,
    channel: "TELEGRAM",
    externalChatId: `chat-${id}`,
    status: "OPEN",
    requiresHumanReview: false,
    createdAt: "2026-01-01T00:00:00Z",
    updatedAt
  };
}

function detail(id, messageText = `message-${id}`) {
  return {
    conversation: summary(id),
    messages: [{
      id: `message-${id}`,
      conversationId: id,
      channel: "TELEGRAM",
      externalChatId: `chat-${id}`,
      externalMessageId: `external-${id}`,
      rawText: messageText,
      detectedIntent: "REQUEST_QUOTE",
      status: "RECEIVED",
      requiresHumanReview: true,
      createdAt: "2026-01-01T00:00:01Z"
    }],
    handoffs: [],
    responseDrafts: []
  };
}

function readersFor(summaries, detailById) {
  const calls = [];
  return {
    calls,
    readers: {
      getJson: async (path) => {
        calls.push({ kind: "list", path });
        return { data: summaries };
      },
      getNullable: async (path) => {
        calls.push({ kind: "detail", path });
        const id = decodeURIComponent(path.split("/").at(-1));
        const value = detailById.get(id);
        return typeof value === "function" ? value(path) : value;
      }
    }
  };
}

test("backend list and detail conversation DTOs are distinct", () => {
  assert.match(controller, /@GetMapping\("\/conversations"\)[\s\S]*List<BotConversationResponse> conversations\(\)/);
  assert.match(controller, /@GetMapping\("\/conversations\/\{id\}"\)[\s\S]*BotConversationDetail conversationDetail/);
});

test("server adapter returns empty detail list for empty summaries", async () => {
  const { calls, readers } = readersFor([], new Map());
  const result = await listBotConversationDetailsWithReaders(readers);
  assert.deepEqual(result.data, []);
  assert.equal(result.error, undefined);
  assert.deepEqual(calls.map((call) => call.kind), ["list"]);
});

test("server adapter fetches two real details and preserves summary order", async () => {
  const summaries = [summary("b", "2026-01-02T00:00:00Z"), summary("a", "2026-01-01T00:00:00Z")];
  const { calls, readers } = readersFor(summaries, new Map([
    ["a", { data: detail("a") }],
    ["b", { data: detail("b") }]
  ]));
  const result = await listBotConversationDetailsWithReaders(readers);
  assert.deepEqual(result.data.map((item) => item.conversation.id), ["b", "a"]);
  assert.equal(result.error, undefined);
  assert.deepEqual(calls.map((call) => call.kind), ["list", "detail", "detail"]);
  assert.deepEqual(calls.filter((call) => call.kind === "detail").map((call) => call.path), [
    "/api/v1/bot-runtime/conversations/b",
    "/api/v1/bot-runtime/conversations/a"
  ]);
});

test("server adapter omits one failed detail and returns bounded aggregated error", async () => {
  const { readers } = readersFor([summary("ok"), summary("bad")], new Map([
    ["ok", { data: detail("ok") }],
    ["bad", { data: null, error: "Core API returned 500." }]
  ]));
  const result = await listBotConversationDetailsWithReaders(readers);
  assert.deepEqual(result.data.map((item) => item.conversation.id), ["ok"]);
  assert.match(result.error ?? "", /Core API returned 500/);
});

test("server adapter omits malformed detail shapes", async () => {
  const { readers } = readersFor([summary("ok"), summary("malformed")], new Map([
    ["ok", { data: detail("ok") }],
    ["malformed", { data: summary("malformed") }]
  ]));
  const result = await listBotConversationDetailsWithReaders(readers);
  assert.deepEqual(result.data.map((item) => item.conversation.id), ["ok"]);
  assert.match(result.error ?? "", /invalid detail shape/);
});

test("server adapter bounds detail fan-out to 20 for 21 summaries", async () => {
  const summaries = Array.from({ length: 21 }, (_, index) => summary(`c${index + 1}`));
  const detailById = new Map(summaries.map((item) => [item.id, { data: detail(item.id) }]));
  const { calls, readers } = readersFor(summaries, detailById);
  const result = await listBotConversationDetailsWithReaders(readers);
  assert.equal(result.data.length, 20);
  assert.equal(calls.filter((call) => call.kind === "detail").length, 20);
  assert.equal(result.data.at(-1).conversation.id, "c20");
});

test("server adapter never inserts summary objects into detail output", async () => {
  const { readers } = readersFor([summary("summary-only")], new Map([
    ["summary-only", { data: summary("summary-only") }]
  ]));
  const result = await listBotConversationDetailsWithReaders(readers);
  assert.deepEqual(result.data, []);
  assert.match(result.error ?? "", /invalid detail shape/);
});