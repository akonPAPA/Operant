import assert from "node:assert/strict";
import test from "node:test";
import { IDEMPOTENCY_KEY_CONTRACT } from "../lib/bff/bff-idempotency-key.ts";
import {
  persistOperatorSession,
  replaceOperatorSessionPermissions,
  resetSessionStoreForTesting
} from "../lib/bff/bff-session-store.ts";
import { idempotencyKeyForCreateDraftFromRfq } from "../lib/quote-mutation-idempotency.ts";

const ENV_KEYS = [
  "NODE_ENV",
  "ORDERPILOT_DEPLOY_PROFILE",
  "ORDERPILOT_BFF_ENABLED",
  "ORDERPILOT_BFF_SESSION_STORE",
  "ORDERPILOT_BFF_LOCAL_TEST_BOOTSTRAP"
];

async function withEnv(fn) {
  const prior = {};
  for (const key of ENV_KEYS) {
    prior[key] = process.env[key];
  }
  Object.assign(process.env, {
    NODE_ENV: "test",
    ORDERPILOT_DEPLOY_PROFILE: "local-test",
    ORDERPILOT_BFF_ENABLED: "true",
    ORDERPILOT_BFF_SESSION_STORE: "memory",
    ORDERPILOT_BFF_LOCAL_TEST_BOOTSTRAP: "true"
  });
  resetSessionStoreForTesting();
  try {
    return await fn();
  } finally {
    for (const key of ENV_KEYS) {
      if (prior[key] === undefined) delete process.env[key];
      else process.env[key] = prior[key];
    }
    resetSessionStoreForTesting();
  }
}

test("local-test session permission patch revokes QUOTE_ACTION", async () => {
  await withEnv(async () => {
    const { sessionId } = await persistOperatorSession({
      tenantId: "11111111-1111-4111-8111-111111111111",
      actorId: "22222222-2222-4222-8222-222222222222",
      permissions: ["QUOTE_READ", "QUOTE_ACTION"]
    });
    const updated = await replaceOperatorSessionPermissions(sessionId, ["QUOTE_READ"]);
    assert.equal(updated, true);
  });
});

test("RFQ idempotency keys satisfy BFF canonical grammar", () => {
  const key = new RegExp(`^${IDEMPOTENCY_KEY_CONTRACT.pattern.slice(1, -1)}$`);
  const sample = idempotencyKeyForCreateDraftFromRfq({
    customerExternalRef: "CUST-001",
    requestedLocation: "WH-ALM",
    requestedDiscountPercent: 0,
    requestedItems: [
      {
        rawSkuOrAlias: "PAD-OE-04465",
        description: "Original brake pads for Toyota Camry 2018",
        quantity: 2,
        uom: "EA"
      }
    ]
  });
  assert.match(sample, key);
});
