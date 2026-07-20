import { expect, test } from "@playwright/test";

const TENANT_APP = "http://127.0.0.1:3107";
const FAKE_CORE = "http://127.0.0.1:18080";

async function signIn(page: import("@playwright/test").Page) {
  await page.goto(`${TENANT_APP}/login`);
  await page.getByRole("button", { name: /continue/i }).click();
  await page.waitForURL((url) => url.pathname !== "/login", { timeout: 30_000 });
}

test.describe("P1-E tenant runtime exclusion", () => {
  test("deep link to internal control path does not reach fake Core internal API", async ({ page, request }) => {
    await request.delete(`${FAKE_CORE}/__test/requests`);
    await signIn(page);
    const response = await page.request.get(`${TENANT_APP}/api/bff/api/v1/internal/control/lifecycle/operations`);
    expect(response.status()).toBeGreaterThanOrEqual(400);
    const recorded = (await (await request.get(`${FAKE_CORE}/__test/requests`)).json()) as { path: string }[];
    expect(recorded.some((entry) => entry.path.includes("/internal/control"))).toBe(false);
  });

  test("arbitrary upstream segment cannot proxy to internal control", async ({ page, request }) => {
    await request.delete(`${FAKE_CORE}/__test/requests`);
    await signIn(page);
    const response = await page.request.get(
      `${TENANT_APP}/api/bff/api/v1/quote-review/queue/../../../internal/control/operations`
    );
    expect(response.status()).toBeGreaterThanOrEqual(400);
    const recorded = (await (await request.get(`${FAKE_CORE}/__test/requests`)).json()) as { path: string }[];
    expect(recorded.some((entry) => entry.path.includes("/internal/control"))).toBe(false);
  });
});
