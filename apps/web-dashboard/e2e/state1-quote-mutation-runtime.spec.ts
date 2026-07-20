import { expect, test, type APIRequestContext, type Page } from "@playwright/test";

const QUOTE_ACTOR_APP = "http://127.0.0.1:3107";
const QUOTE_READONLY_APP = "http://127.0.0.1:3108";
const NO_QUOTES_APP = "http://127.0.0.1:3109";
const UNAVAILABLE_APP = "http://127.0.0.1:3105";
const FAKE_CORE = "http://127.0.0.1:18080";
const IDEMPOTENCY_KEY = "550e8400-e29b-41d4-a716-446655440000";

const VALID_RFQ = {
  customerExternalRef: "CUST-001",
  requestedLocation: "WH-ALM",
  requestedDiscountPercent: 0,
  requestedItems: [{ rawSkuOrAlias: "SKU", description: "d", quantity: 1, uom: "EA" }]
};

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

async function authCookies(page: Page, appOrigin: string) {
  const cookies = await page.context().cookies(appOrigin);
  const session = cookies.find((cookie) => cookie.name === "op_session")?.value;
  const csrf = cookies.find((cookie) => cookie.name === "op_csrf")?.value;
  expect(session).toBeTruthy();
  expect(csrf).toBeTruthy();
  return { session: session!, csrf: csrf! };
}

test.describe("Profile A — QUOTE_READ + QUOTE_ACTION", () => {
  test("rapid double-click produces exactly one upstream mutation", async ({ page, request }) => {
    await resetCoreRequests(request);
    await signIn(page, QUOTE_ACTOR_APP);
    await page.goto(`${QUOTE_ACTOR_APP}/quotes`);
    await expect(page.getByRole("heading", { name: /Draft Quote Workspace/i })).toBeVisible();
    const createButton = page.getByRole("button", { name: /Create Draft Quote/i });
    await expect(createButton).toBeVisible();
    const mutationResponsePromise = page.waitForResponse(
      (response) => response.url().includes("/quotes/from-rfq") && response.request().method() === "POST",
      { timeout: 30_000 }
    );
    await createButton.dblclick({ delay: 5 });
    const mutationResponse = await mutationResponsePromise;
    expect(mutationResponse.ok(), `mutation HTTP ${mutationResponse.status()}`).toBeTruthy();
    await expect(page.locator("#quote-workspace-status")).toContainText(/Draft quote created/i);
    const mutations = quoteMutationCalls(await coreRequests(request)).filter((call) => call.method === "POST");
    expect(mutations).toHaveLength(1);
  });
});

test.describe("Profile B — QUOTE_READ without QUOTE_ACTION", () => {
  test("read-only workspace and valid crafted mutation are permission-denied with zero Core mutations", async ({
    page,
    request
  }) => {
    await resetCoreRequests(request);
    await signIn(page, QUOTE_READONLY_APP);
    await page.goto(`${QUOTE_READONLY_APP}/quotes`);
    await expect(page.getByRole("heading", { name: /Read-only quote workspace/i })).toBeVisible();
    await expect(page.getByRole("button", { name: /Create Draft Quote/i })).toHaveCount(0);
    const { csrf } = await authCookies(page, QUOTE_READONLY_APP);
    const bffResponse = await page.request.post(`${QUOTE_READONLY_APP}/api/bff/api/v1/quotes/from-rfq`, {
      headers: {
        Origin: QUOTE_READONLY_APP,
        Referer: `${QUOTE_READONLY_APP}/quotes`,
        "Content-Type": "application/json",
        "X-OP-CSRF-Token": csrf,
        "Idempotency-Key": IDEMPOTENCY_KEY
      },
      data: VALID_RFQ
    });
    expect(bffResponse.status()).toBe(403);
    const mutations = quoteMutationCalls(await coreRequests(request)).filter((call) => call.method === "POST");
    expect(mutations).toHaveLength(0);
  });
});

test.describe("Profile C — no quote visibility", () => {
  test("protected quote workspace unavailable, zero Core quote traffic", async ({ page, request }) => {
    await resetCoreRequests(request);
    await signIn(page, NO_QUOTES_APP);
    await page.goto(`${NO_QUOTES_APP}/quotes`);
    await expect(page.getByText(/Quote workspace unavailable/i)).toBeVisible();
    expect(quoteMutationCalls(await coreRequests(request))).toHaveLength(0);
  });
});

test.describe("Profile D — revoked server session", () => {
  test("the old opaque session ID is denied after server-side logout revocation", async ({ page, request }) => {
    await resetCoreRequests(request);
    await signIn(page, QUOTE_ACTOR_APP);
    const { session, csrf } = await authCookies(page, QUOTE_ACTOR_APP);
    const logout = await page.request.post(`${QUOTE_ACTOR_APP}/api/auth/logout`, {
      headers: {
        Origin: QUOTE_ACTOR_APP,
        Referer: `${QUOTE_ACTOR_APP}/quotes`,
        "X-OP-CSRF-Token": csrf
      }
    });
    expect(logout.ok()).toBeTruthy();

    const revokedAttempt = await request.post(`${QUOTE_ACTOR_APP}/api/bff/api/v1/quotes/from-rfq`, {
      headers: {
        Origin: QUOTE_ACTOR_APP,
        Referer: `${QUOTE_ACTOR_APP}/quotes`,
        Cookie: `op_session=${session}; op_csrf=${csrf}`,
        "X-OP-CSRF-Token": csrf,
        "Content-Type": "application/json",
        "Idempotency-Key": IDEMPOTENCY_KEY
      },
      data: VALID_RFQ
    });
    expect(revokedAttempt.status()).toBe(401);
    expect(quoteMutationCalls(await coreRequests(request)).filter((call) => call.method === "POST")).toHaveLength(0);
  });
});

test.describe("Profile E — capability projection unavailable", () => {
  test("fail closed without mutation controls", async ({ page, request }) => {
    await resetCoreRequests(request);
    await signIn(page, UNAVAILABLE_APP);
    await page.goto(`${UNAVAILABLE_APP}/quotes`);
    await expect(page.getByRole("heading", { level: 2, name: /Quote workspace unavailable/i })).toBeVisible();
    await expect(page.getByRole("button", { name: /Create Draft Quote/i })).toHaveCount(0);
    expect(quoteMutationCalls(await coreRequests(request))).toHaveLength(0);
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
      if (!response.ok) throw new Error(`permission patch failed: ${response.status}`);
    });
    await page.getByRole("button", { name: /Create Draft Quote/i }).click();
    await expect(page.locator("#quote-workspace-status")).not.toContainText(/Draft quote created/i, { timeout: 15_000 });
    await expect(page.locator("#quote-workspace-status")).toContainText(/not available|permission|validation|failed|do not have/i);
    expect(quoteMutationCalls(await coreRequests(request)).filter((call) => call.method === "POST")).toHaveLength(0);
  });
});
