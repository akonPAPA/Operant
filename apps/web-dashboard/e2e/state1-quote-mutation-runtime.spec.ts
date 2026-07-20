import { expect, test, type APIRequestContext, type Page } from "@playwright/test";

const QUOTE_ACTOR_APP = "http://127.0.0.1:3107";
const QUOTE_READONLY_APP = "http://127.0.0.1:3108";
const NO_QUOTES_APP = "http://127.0.0.1:3109";
const UNAVAILABLE_APP = "http://127.0.0.1:3105";
const FAKE_CORE = "http://127.0.0.1:18080";

async function coreRequests(request: APIRequestContext) {
  const response = await request.get(`${FAKE_CORE}/__test/requests`);
  return (await response.json()) as { method: string; path: string; headers: Record<string, string> }[];
}

async function resetCoreRequests(request: APIRequestContext) {
  await request.delete(`${FAKE_CORE}/__test/requests`);
}

function quoteMutationCalls(requests: Awaited<ReturnType<typeof coreRequests>>) {
  return requests.filter(
    (entry) =>
      entry.path.startsWith("/api/v1/quotes/") &&
      (entry.method === "POST" || entry.path.includes("/approval-state"))
  );
}

async function signIn(page: Page, appOrigin: string) {
  await page.context().clearCookies();
  await page.goto(`${appOrigin}/login`);
  const continueButton = page.getByRole("button", { name: /continue/i });
  await expect(async () => {
    await continueButton.click();
    await page.waitForURL((url) => url.origin === appOrigin && url.pathname !== "/login", { timeout: 5000 });
  }).toPass({ timeout: 30_000 });
}

test.describe("Profile A — QUOTE_READ + QUOTE_ACTION", () => {
  test("reach workspace, render mutation control, one upstream mutation on success", async ({ page, request }) => {
    await resetCoreRequests(request);
    await signIn(page, QUOTE_ACTOR_APP);
    await page.goto(`${QUOTE_ACTOR_APP}/quotes`);
    await expect(page.getByRole("heading", { name: /Draft Quote Workspace/i })).toBeVisible();
    await expect(page.getByRole("button", { name: /Create Draft Quote/i })).toBeVisible();
    const [mutationResponse] = await Promise.all([
      page.waitForResponse(
        (response) =>
          response.url().includes("/quotes/from-rfq") && response.request().method() === "POST",
        { timeout: 30_000 }
      ),
      page.getByRole("button", { name: /Create Draft Quote/i }).click()
    ]);
    expect(mutationResponse.ok(), `mutation HTTP ${mutationResponse.status()}`).toBeTruthy();
    await expect(page.locator("#quote-workspace-status")).toContainText(/Draft quote created/i);
    const mutations = quoteMutationCalls(await coreRequests(request)).filter((c) => c.method === "POST");
    expect(mutations.length).toBeGreaterThanOrEqual(1);
    expect(mutations.length).toBeLessThanOrEqual(2);
  });
});

test.describe("Profile B — QUOTE_READ without QUOTE_ACTION", () => {
  test("read-only workspace, no mutation button, crafted POST denied with zero Core mutations", async ({
    page,
    request
  }) => {
    await resetCoreRequests(request);
    await signIn(page, QUOTE_READONLY_APP);
    await page.goto(`${QUOTE_READONLY_APP}/quotes`);
    await expect(page.getByRole("heading", { name: /Read-only quote workspace/i })).toBeVisible();
    await expect(page.getByRole("button", { name: /Create Draft Quote/i })).toHaveCount(0);
    const bffResponse = await page.request.post(`${QUOTE_READONLY_APP}/api/bff/api/v1/quotes/from-rfq`, {
      headers: { "Content-Type": "application/json", "X-OP-CSRF-Token": "forged" },
      data: {
        customerExternalRef: "CUST-001",
        requestedLocation: "WH-ALM",
        requestedDiscountPercent: 0,
        requestedItems: [{ rawSkuOrAlias: "SKU", description: "d", quantity: 1, uom: "EA" }]
      }
    });
    expect(bffResponse.status()).toBeGreaterThanOrEqual(400);
    const mutations = quoteMutationCalls(await coreRequests(request)).filter((c) => c.method === "POST");
    expect(mutations.length).toBe(0);
  });
});

test.describe("Profile C — no quote visibility", () => {
  test("protected quote workspace unavailable, zero Core quote traffic", async ({ page, request }) => {
    await resetCoreRequests(request);
    await signIn(page, NO_QUOTES_APP);
    await page.goto(`${NO_QUOTES_APP}/quotes`);
    await expect(page.getByText(/Quote workspace unavailable/i)).toBeVisible();
    expect(quoteMutationCalls(await coreRequests(request)).length).toBe(0);
  });
});

test.describe("Profile D — revoked session", () => {
  test("denied without false success", async ({ page, request }) => {
    await resetCoreRequests(request);
    await signIn(page, QUOTE_ACTOR_APP);
    await page.context().clearCookies();
    await page.goto(`${QUOTE_ACTOR_APP}/quotes`);
    await expect(page.getByText(/Quote workspace unavailable|Sign-in|unavailable/i)).toBeVisible();
    expect(quoteMutationCalls(await coreRequests(request)).length).toBe(0);
  });
});

test.describe("Profile E — capability projection unavailable", () => {
  test("fail closed without mutation controls", async ({ page, request }) => {
    await resetCoreRequests(request);
    await signIn(page, UNAVAILABLE_APP);
    await page.goto(`${UNAVAILABLE_APP}/quotes`);
    await expect(page.getByRole("heading", { level: 2, name: /Quote workspace unavailable/i })).toBeVisible();
    await expect(page.getByRole("button", { name: /Create Draft Quote/i })).toHaveCount(0);
    expect(quoteMutationCalls(await coreRequests(request)).length).toBe(0);
  });
});

test.describe("Profile F — stale UI after permission revocation", () => {
  test("backend denies mutation after QUOTE_ACTION revoked; no Core mutation; no success UI", async ({
    page,
    request
  }) => {
    await resetCoreRequests(request);
    await signIn(page, QUOTE_ACTOR_APP);
    await page.goto(`${QUOTE_ACTOR_APP}/quotes`);
    await expect(page.getByRole("button", { name: /Create Draft Quote/i })).toBeVisible();
    await page.evaluate(async () => {
      const response = await fetch("/api/auth/session/permissions", {
        method: "PATCH",
        credentials: "include",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ permissions: ["QUOTE_READ"] })
      });
      if (!response.ok) {
        throw new Error(`permission patch failed: ${response.status}`);
      }
    });
    await page.getByRole("button", { name: /Create Draft Quote/i }).click();
    await expect(page.locator("#quote-workspace-status")).not.toContainText(/Draft quote created/i, { timeout: 15_000 });
    await expect(page.locator("#quote-workspace-status")).toContainText(/not available|permission|validation|failed|do not have/i);
    const mutations = quoteMutationCalls(await coreRequests(request)).filter((c) => c.method === "POST");
    expect(mutations.length).toBe(0);
  });
});
