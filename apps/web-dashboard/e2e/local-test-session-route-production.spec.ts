import { expect, test } from "@playwright/test";

const PRODUCTION_APP = "http://127.0.0.1:3101";

test("local-test permission mutation route is unavailable in the production artifact", async ({ request }) => {
  const response = await request.patch(`${PRODUCTION_APP}/api/auth/session/permissions`, {
    headers: {
      Origin: PRODUCTION_APP,
      Referer: `${PRODUCTION_APP}/login`,
      "Content-Type": "application/json"
    },
    data: { permissions: ["QUOTE_READ", "QUOTE_ACTION"] }
  });
  expect(response.status()).toBe(404);
  expect(response.headers()["set-cookie"]).toBeUndefined();
  expect(await response.text()).not.toMatch(/QUOTE_ACTION|sessionId|actorId|tenantId/i);
});
