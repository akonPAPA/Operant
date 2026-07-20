import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import test from "node:test";

const root = process.cwd();
const api = readFileSync(join(root, "lib", "commerce-intelligence-api.ts"), "utf8");
const component = readFileSync(
  join(root, "components", "commerce-intelligence-demo-flow.tsx"),
  "utf8"
);
const page = readFileSync(
  join(root, "app", "(dashboard)", "commerce-intelligence", "page.tsx"),
  "utf8"
);
const navigation = readFileSync(join(root, "components", "navigation-registry.ts"), "utf8");

function typeBlock(source, typeName) {
  const match = source.match(new RegExp(`export type ${typeName} = \\{([\\s\\S]*?)\\n\\};`));
  assert.ok(match, `Missing ${typeName}`);
  return match[1];
}

test("API client performs the exact read-only request with ANALYTICS_READ", () => {
  assert.match(api, /\/api\/v1\/commerce-intelligence\/demo-flow/);
  assert.match(api, /method:\s*"GET"/);
  assert.match(api, /dashboardRequestHeaders\(commerceIntelligenceClient\.tenantId,\s*ANALYTICS_READ\)/);
  assert.match(api, /const ANALYTICS_READ = "ANALYTICS_READ"/);
  assert.doesNotMatch(api, /body\s*:/);
  assert.doesNotMatch(api, /URLSearchParams|\?tenantId|\?actorId|\?sourceId|\?sourceType/);
});

test("API client maps failures to bounded messages and never returns raw backend errors", () => {
  for (const status of ["403", "404", "429", "503"]) {
    assert.match(api, new RegExp(`case ${status}:`));
  }
  assert.match(api, /Raw backend bodies are deliberately ignored/);
  assert.doesNotMatch(api, /parsed\.message|error instanceof Error \? error\.message/);
});

test("public frontend types omit internal authority, provider, audit, and runtime fields", () => {
  const recentFlow = typeBlock(api, "CommerceIntelligenceRecentFlow");
  const response = typeBlock(api, "CommerceIntelligenceDemoFlow");
  const combined = `${recentFlow}\n${response}`;
  assert.doesNotMatch(
    combined,
    /\b(tenantId|actorId|reviewerUserId|createdByUserId|decidedByUserId|inboundChannelEventId|channelConnectionId|auditId|idempotencyKey|correlationId|rawPayload|payloadJson|prompt|token|secret|credential|stackTrace|quotaBucket|redisKey|jti|nonce|retryAfterSeconds)\??:/
  );
  assert.match(recentFlow, /\bhandoffId:/);
  assert.match(recentFlow, /\brequestPreview:/);
  assert.match(recentFlow, /\bblockingIssueCodes:/);
});

test("dashboard renders summary, safety, runtime posture, bottlenecks, and recent flows", () => {
  for (const label of [
    "RFQs captured",
    "Pending review",
    "In review",
    "AI advisory suggestions",
    "Review-required draft quotes",
    "Safe demo terminal decisions",
    "Safety state",
    "Runtime-control posture",
    "Blocking bottlenecks",
    "Recent demo flows",
    "Not proven by this read model"
  ]) {
    assert.match(component, new RegExp(label.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")));
  }
  assert.match(component, /CUSTOMER_NOT_RESOLVED|item\.code/);
  assert.match(component, /externalWriteStatus/);
  assert.match(component, /connectorCallStatus/);
  assert.match(component, /outboxStatus/);
  assert.match(component, /denialTelemetry/);
});

test("dashboard links to demo and RFQ workspace without rendering internal ids or raw objects", () => {
  assert.match(component, /href="\/demo"/);
  assert.match(component, /href="\/channels\/rfq-handoffs"/);
  assert.doesNotMatch(
    component,
    /\.(tenantId|actorId|reviewerUserId|auditId|idempotencyKey|correlationId|rawPayload|payloadJson|prompt|token|secret|credential|stackTrace|quotaBucket|redisKey|jti|nonce)\b/
  );
  assert.doesNotMatch(component, /JSON\.stringify|<pre/);
});

test("server page loads the read model and navigation exposes the tenant operator route", () => {
  assert.match(page, /getCommerceIntelligenceDemoFlow/);
  assert.match(page, /CommerceIntelligenceDemoFlowView/);
  assert.match(navigation, /\{ id: "commerce-intelligence", path: "\/commerce-intelligence", label: "Commerce Intelligence"/);
});
