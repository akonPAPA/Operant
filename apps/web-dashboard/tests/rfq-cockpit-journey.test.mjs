import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

// PR #255 Operator Cockpit v1 — source-inspection proof for the guided RFQ-to-quote cockpit.
// Matches the established repo convention (see rfq-handoffs.test.mjs): the .tsx is read as text and
// asserted for what it must render and what it must never render. No mutation surface is added here.

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const journey = readFileSync(join(root, "components", "rfq-cockpit-journey.tsx"), "utf8");
const workspace = readFileSync(join(root, "components", "rfq-handoff-workspace.tsx"), "utf8");

// --- Cockpit renders the coherent journey ---

test("cockpit renders source channel, detected intent, request text, and handoff status", () => {
  assert.match(journey, /Source channel/);
  assert.match(journey, /detail\.sourceChannel/);
  assert.match(journey, /Detected intent/);
  assert.match(journey, /detail\.detectedIntent/);
  assert.match(journey, /Request text/);
  assert.match(journey, /detail\.requestText/);
  assert.match(journey, /detail\.requestPreview/);
  assert.match(journey, /Handoff status/);
  assert.match(journey, /statusLabel\(detail\.status\)/);
});

test("cockpit renders advisory, draft, and safe terminal state", () => {
  assert.match(journey, /AI advisory status/);
  assert.match(journey, /aiSuggestion \?/);
  assert.match(journey, /Draft quote status/);
  assert.match(journey, /draftResult\.draftQuote\.status/);
  assert.match(journey, /Safe terminal state/);
  assert.match(journey, /decisionResult\.terminalState/);
});

test("cockpit shows honest NOT_* state tokens and no fake counters", () => {
  assert.match(journey, /NOT_GENERATED/);
  assert.match(journey, /NOT_CREATED/);
  assert.match(journey, /NOT_RECORDED/);
  assert.match(journey, /NOT_MEASURED/);
  // The draft line count comes from the real draft length, never a fabricated number.
  assert.match(journey, /draftResult\.draftQuote\.lines\.length/);
});

test("cockpit surfaces the external execution disabled safety posture", () => {
  assert.match(journey, /Safety posture/);
  assert.match(journey, /External execution/);
  assert.match(journey, /DISABLED/);
  assert.match(journey, /NOT_INVOKED/);
  assert.match(journey, /NOT_REQUESTED/);
  assert.match(journey, /NO_EXTERNAL_WRITE/);
});

test("cockpit makes the next operator action obvious", () => {
  assert.match(journey, /Next operator action/);
  assert.match(journey, /function nextAction/);
  assert.match(journey, /PENDING_REVIEW/);
  assert.match(journey, /IN_REVIEW/);
});

test("cockpit links back to commerce intelligence and runtime control", () => {
  assert.match(journey, /href="\/commerce-intelligence"/);
  assert.match(journey, /href="\/runtime-control"/);
  assert.match(journey, /Commerce Intelligence/);
  assert.match(journey, /Runtime Control/);
});

// --- Forbidden / internal fields are never rendered ---

test("cockpit renders no client-owned authority or internal identifiers", () => {
  assert.doesNotMatch(
    journey,
    /reviewerUserId|inboundChannelEventId|channelConnectionId|sourceExternalEventId/
  );
  assert.doesNotMatch(
    journey,
    /decisionResult\.(tenantId|actorId|idempotencyKey|auditEventId|rawAiPayload|connectorCredentials)/
  );
  assert.doesNotMatch(
    journey,
    /draftResult\.(tenantId|actorId|sourceId|auditEventId|rawPayload)/
  );
  assert.doesNotMatch(journey, /tenantId|actorId|userId|createdBy|approvedBy/);
  assert.doesNotMatch(journey, /secretRef|botToken|webhookSecret|prompt|apiKey|stackTrace/i);
});

test("cockpit introduces no mutation, ERP, or connector action", () => {
  assert.doesNotMatch(
    journey,
    /(createOrder|approveQuote|approveOrder|syncErp|erpSync|updateInventory|updatePrice|updateCustomer|connectorCommand)\s*\(/i
  );
  assert.doesNotMatch(journey, /"use client"/);
  assert.doesNotMatch(journey, /onClick=/);
});

// --- Workspace wires the cockpit safely ---

test("workspace renders the cockpit journey with safe props only", () => {
  assert.match(workspace, /import \{ RfqCockpitJourney \}/);
  assert.match(workspace, /<RfqCockpitJourney/);
  assert.match(workspace, /detail=\{detail\}/);
  assert.match(workspace, /aiSuggestion=\{aiSuggestion\}/);
  assert.match(workspace, /draftResult=\{draftResult\}/);
  assert.match(workspace, /decisionResult=\{decisionResult\}/);
});
