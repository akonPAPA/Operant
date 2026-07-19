import { expect, test, type APIRequestContext, type Page } from "@playwright/test";

/**
 * State 1 shell runtime proof — genuine browser behavior for the WP3 command palette and the
 * responsive/accessible application shell. Reuses the existing P1-B dev runtime (:3100) and its
 * local-test bootstrap session; it starts no new server harness.
 *
 * Bootstrap actor permissions at :3100 are REVIEW_READ, REVIEW_ACTION, ANALYTICS_READ, so
 * capability-gated tenant destinations are offered here. :3104 is REVIEW_* only (no ANALYTICS_READ)
 * for denied-capability and direct-URL authorization proofs.
 */
const LOCAL_APP = "http://localhost:3100";
const DENIED_APP = "http://localhost:3104";
const UNAVAILABLE_APP = "http://localhost:3105";
const FAKE_CORE = "http://127.0.0.1:18080";
const SHELL_PAGE = `${LOCAL_APP}/command-center`;

const dialog = (page: Page) => page.getByRole("dialog", { name: /command palette/i });
const searchInput = (page: Page) => page.getByRole("combobox", { name: /search navigation/i });
const searchTrigger = (page: Page) => page.getByRole("button", { name: /search/i });

async function signIn(page: Page, appOrigin = LOCAL_APP) {
  await page.goto(`${appOrigin}/login`);
  const continueButton = page.getByRole("button", { name: /continue/i });
  await expect(async () => {
    await continueButton.click();
    await page.waitForURL((url) => url.origin === appOrigin && url.pathname !== "/login", { timeout: 3000 });
  }).toPass({ timeout: 25_000 });
}

async function gotoShell(page: Page) {
  await signIn(page);
  await page.goto(SHELL_PAGE);
  await page.waitForLoadState("networkidle").catch(() => {});
  await expect(page.getByRole("heading", { level: 1, name: "Command Center" })).toBeVisible();
  await expect(searchTrigger(page)).toBeVisible();
}

/** Open the palette with a hydration-robust retry using the given action (keyboard or click). */
async function openPaletteWith(page: Page, action: () => Promise<void>) {
  await expect(async () => {
    await action();
    await expect(dialog(page)).toBeVisible({ timeout: 1000 });
  }).toPass({ timeout: 15_000 });
}

const openWithCtrlK = (page: Page) => openPaletteWith(page, () => page.keyboard.press("Control+k"));

async function coreRequests(request: APIRequestContext) {
  const response = await request.get(`${FAKE_CORE}/__test/requests`);
  return (await response.json()) as { method: string; path: string; headers: Record<string, string> }[];
}

async function resetCoreRequests(request: APIRequestContext) {
  await request.delete(`${FAKE_CORE}/__test/requests`);
}

test.describe("command palette — open/close and focus", () => {
  test("Ctrl+K opens, Escape closes, and focus returns to the trigger", async ({ page }) => {
    await gotoShell(page);
    await expect(dialog(page)).toHaveCount(0);

    await openWithCtrlK(page);
    await expect(searchInput(page)).toBeFocused();

    await page.keyboard.press("Escape");
    await expect(dialog(page)).toHaveCount(0);
    await expect(searchTrigger(page)).toBeFocused();
  });

  test("trigger click opens and an outside click closes", async ({ page }) => {
    await gotoShell(page);
    await openPaletteWith(page, () => searchTrigger(page).click());
    await page.mouse.click(5, 5);
    await expect(dialog(page)).toHaveCount(0);
  });

  test("a plain 'k' with no modifier does not open the palette (no typing hijack)", async ({ page }) => {
    await gotoShell(page);
    await openWithCtrlK(page);
    await page.keyboard.press("Escape");
    await expect(dialog(page)).toHaveCount(0);
    await page.keyboard.press("k");
    await expect(dialog(page)).toHaveCount(0);
  });
});

test.describe("command palette — keyboard navigation and allowlisted destinations", () => {
  test("ArrowDown changes the single active option (aria-activedescendant)", async ({ page }) => {
    await gotoShell(page);
    await openWithCtrlK(page);
    const first = await searchInput(page).getAttribute("aria-activedescendant");
    await page.keyboard.press("ArrowDown");
    const second = await searchInput(page).getAttribute("aria-activedescendant");
    expect(first).toBeTruthy();
    expect(second).toBeTruthy();
    expect(second).not.toBe(first);
    await expect(page.locator('[role="option"][aria-selected="true"]')).toHaveCount(1);
  });

  test("Enter navigates only to the active allowlisted destination", async ({ page }) => {
    await gotoShell(page);
    await openWithCtrlK(page);
    await searchInput(page).fill("Reconciliation");
    await expect(page.locator('[role="option"]')).toHaveCount(1);
    await page.keyboard.press("Enter");
    await expect(page).toHaveURL(`${LOCAL_APP}/reconciliation`);
  });

  test("no-result state prevents navigation on Enter", async ({ page }) => {
    await gotoShell(page);
    await openWithCtrlK(page);
    await searchInput(page).fill("zzzq-nonexistent-destination");
    await expect(page.getByText(/no navigation matches/i)).toBeVisible();
    await expect(page.locator('[role="option"]')).toHaveCount(0);
    await page.keyboard.press("Enter");
    await expect(page).toHaveURL(SHELL_PAGE);
  });

  test("a typed path string is a filter, not a route — it cannot navigate off-allowlist", async ({ page }) => {
    await gotoShell(page);
    await openWithCtrlK(page);
    await searchInput(page).fill("/internal-support");
    await expect(page.locator('[role="option"]')).toHaveCount(0);
    await page.keyboard.press("Enter");
    await expect(page).toHaveURL(SHELL_PAGE);
  });
});

test.describe("command palette — plane isolation", () => {
  test("tenant palette never lists staff or customer routes", async ({ page }) => {
    await gotoShell(page);
    await openWithCtrlK(page);
    const listText = (await page.locator('[role="listbox"]').innerText()).toLowerCase();
    expect(listText).toContain("analytics");
    expect(listText).not.toContain("internal support");
    expect(listText).not.toContain("support operations");
    expect(listText).not.toContain("order tracking");

    for (const q of ["support", "internal", "incident", "tracking"]) {
      await searchInput(page).fill(q);
      const options = page.locator('[role="option"]');
      const count = await options.count();
      for (let i = 0; i < count; i += 1) {
        const optionText = (await options.nth(i).innerText()).toLowerCase();
        expect(optionText).not.toContain("internal support");
        expect(optionText).not.toContain("order tracking");
      }
    }
  });

  test("palette options are not anchors and expose no javascript:/data:/external href", async ({ page }) => {
    await gotoShell(page);
    await openWithCtrlK(page);
    await expect(page.locator('[role="option"] a[href^="javascript:"]')).toHaveCount(0);
    await expect(page.locator('[role="option"] a[href^="data:"]')).toHaveCount(0);
    await expect(page.locator('[role="option"] a[href^="http"]')).toHaveCount(0);
  });
});

test.describe("capability offer filtering — allowed session", () => {
  test("valid bootstrap session offers Analytics and keeps staff/customer routes absent", async ({ page }) => {
    await gotoShell(page);
    const nav = page.getByRole("navigation", { name: /primary navigation/i });
    await expect(nav.getByRole("link", { name: "Analytics" })).toBeVisible();
    await nav.locator("details.nav-group", { hasText: "Work Queue" }).locator("summary").click();
    await expect(nav.getByRole("link", { name: "Quote Review" })).toBeVisible();
    await expect(nav.getByRole("link", { name: /internal support/i })).toHaveCount(0);
    await expect(nav.getByRole("link", { name: /order tracking/i })).toHaveCount(0);

    const capabilityResponse = await page.request.get(`${LOCAL_APP}/api/ui/capabilities`);
    expect(capabilityResponse.ok()).toBeTruthy();
    expect(capabilityResponse.headers()["cache-control"]).toContain("no-store");
    const body = await capabilityResponse.json();
    expect(body.status).toBe("ALLOWED");
    expect(body.capabilities).toEqual(expect.arrayContaining(["VIEW_ANALYTICS", "VIEW_REVIEW_QUEUE"]));
    expect(body).not.toHaveProperty("tenantId");
    expect(body).not.toHaveProperty("actorId");
    expect(body).not.toHaveProperty("permissions");
    expect(JSON.stringify(body)).not.toContain("ANALYTICS_READ");
  });

  test("Analytics is searchable in the command palette and direct navigation works", async ({ page }) => {
    await gotoShell(page);
    await openWithCtrlK(page);
    await searchInput(page).fill("Analytics");
    await expect(page.locator('[role="option"]')).toHaveCount(1);
    await page.keyboard.press("Enter");
    await expect(page).toHaveURL(`${LOCAL_APP}/analytics`);
    await expect(page.getByRole("heading", { level: 1, name: "Analytics" })).toBeVisible();
  });
});

test.describe("capability offer filtering — denied session (:3104)", () => {
  test("VIEW_ANALYTICS absent from sidebar, mobile nav surface, and palette", async ({ page }) => {
    await page.setViewportSize({ width: 360, height: 800 });
    await signIn(page, DENIED_APP);
    await page.goto(`${DENIED_APP}/command-center`);
    await expect(page.getByRole("heading", { level: 1, name: "Command Center" })).toBeVisible();

    const capabilityResponse = await page.request.get(`${DENIED_APP}/api/ui/capabilities`);
    expect(capabilityResponse.ok()).toBeTruthy();
    const body = await capabilityResponse.json();
    expect(body.status).toBe("ALLOWED");
    expect(body.capabilities ?? []).not.toContain("VIEW_ANALYTICS");

    const nav = page.getByRole("navigation", { name: /primary navigation/i });
    await expect(nav.getByRole("link", { name: "Analytics" })).toHaveCount(0);
    await expect(nav.getByRole("link", { name: "Command Center" })).toBeVisible();

    await openWithCtrlK(page);
    await searchInput(page).fill("Analytics");
    await expect(page.locator('[role="option"]')).toHaveCount(0);
    await page.keyboard.press("Escape");
  });

  test("direct analytics BFF URL is denied by BFF with no Core side effect", async ({ page, request }) => {
    await resetCoreRequests(request);
    await signIn(page, DENIED_APP);
    const result = await page.evaluate(async () => {
      const response = await fetch("/api/bff/api/v1/analytics/overview");
      return { status: response.status, body: await response.text() };
    });
    expect(result.status).toBe(403);
    expect(result.body).not.toContain("NullPointerException");
    expect(result.body).not.toContain("tenantId");
    expect(result.body).not.toContain("11111111-1111-4111-8111-111111111111");
    const upstream = (await coreRequests(request)).filter((r) => r.path === "/api/v1/analytics/overview");
    expect(upstream).toHaveLength(0);
  });

  test("permitted session can call analytics BFF (authorization is not capability JSON)", async ({
    page,
    request
  }) => {
    await resetCoreRequests(request);
    await signIn(page, LOCAL_APP);
    const status = await page.evaluate(async () => {
      const response = await fetch("/api/bff/api/v1/analytics/overview");
      return response.status;
    });
    // Fake Core returns hostile 500; BFF maps to bounded 502 — proves the call was authorized.
    expect(status).toBe(502);
    const upstream = (await coreRequests(request)).filter((r) => r.path === "/api/v1/analytics/overview");
    expect(upstream).toHaveLength(1);
  });
});

test.describe("capability projection unavailable (:3105)", () => {
  test("gated destinations disappear; universal routes and degraded notice remain", async ({ page }) => {
    await signIn(page, UNAVAILABLE_APP);
    const capabilityResponse = await page.request.get(`${UNAVAILABLE_APP}/api/ui/capabilities`);
    expect(capabilityResponse.status()).toBe(503);
    expect(await capabilityResponse.json()).toEqual({ status: "UNAVAILABLE", capabilities: [] });

    await page.goto(`${UNAVAILABLE_APP}/command-center`);
    await expect(page.getByRole("heading", { level: 1, name: "Command Center" })).toBeVisible();
    await expect(page.getByRole("status")).toContainText(/temporarily unavailable/i);
    const nav = page.getByRole("navigation", { name: /primary navigation/i });
    await expect(nav.getByRole("link", { name: "Analytics" })).toHaveCount(0);
    await expect(nav.getByRole("link", { name: "Command Center" })).toBeVisible();
    await openWithCtrlK(page);
    await searchInput(page).fill("Analytics");
    await expect(page.locator('[role="option"]')).toHaveCount(0);
  });
});

test.describe("first-five-minutes tenant journey", () => {
  test("authenticate → shell → navigate → work page → logout invalidates session", async ({
    page,
    context,
    request
  }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await resetCoreRequests(request);

    await signIn(page);
    await page.goto(SHELL_PAGE);
    await expect(page.getByRole("heading", { level: 1, name: "Command Center" })).toBeVisible();
    await expect(page.getByText(/tenant scoped/i)).toBeVisible();

    await openWithCtrlK(page);
    await searchInput(page).fill("Reconciliation");
    await expect(page.getByRole("option", { name: /Reconciliation/i })).toHaveCount(1);
    await page.keyboard.press("Enter");
    await expect(page).toHaveURL(`${LOCAL_APP}/reconciliation`);
    await expect(page.getByRole("heading", { level: 1, name: "Reconciliation" })).toBeVisible();

    await page.goto(`${LOCAL_APP}/quote-review`);
    await expect(page.getByRole("heading", { level: 1, name: "Quote Review" })).toBeVisible();
    const mainText = await page.locator("main").innerText();
    expect(mainText).not.toContain("NullPointerException");
    expect(mainText).not.toContain("x-orderpilot-gateway");
    expect(mainText).not.toMatch(/eyJ[A-Za-z0-9_-]{10,}\./);

    const nav = page.getByRole("navigation", { name: /primary navigation/i });
    await expect(nav).toBeVisible();
    // Safe next action: return to Command Center via allowlisted navigation (group may be collapsed).
    await page.goto(SHELL_PAGE);
    await expect(page).toHaveURL(SHELL_PAGE);
    await expect(page.getByRole("heading", { level: 1, name: "Command Center" })).toBeVisible();
    await expect(nav.getByRole("link", { name: "Analytics" })).toBeVisible();

    const cookiesBefore = await context.cookies(LOCAL_APP);
    const session = cookiesBefore.find((c) => c.name === "op_session");
    const csrf = cookiesBefore.find((c) => c.name === "op_csrf");
    expect(session).toBeDefined();
    expect(csrf).toBeDefined();

    const logoutStatus = await page.evaluate(async (token) => {
      const response = await fetch("/api/auth/logout", {
        method: "POST",
        headers: { "X-OP-CSRF-Token": token }
      });
      return response.status;
    }, csrf!.value);
    expect(logoutStatus).toBe(200);

    const replay = await page.request.get(`${LOCAL_APP}/api/ui/capabilities`, {
      headers: { cookie: `op_session=${session!.value}` }
    });
    expect(replay.status()).toBe(401);
    const replayBody = await replay.json();
    expect(replayBody).toEqual({ status: "DENIED", capabilities: [] });
  });
});

test.describe("shell accessibility landmarks", () => {
  test("skip link is the first focusable and targets the main landmark", async ({ page }) => {
    await gotoShell(page);
    await page.keyboard.press("Tab");
    const skip = page.locator("a.skip-link");
    await expect(skip).toBeFocused();
    await expect(skip).toHaveAttribute("href", "#main-content");
    await expect(page.locator("main#main-content")).toHaveCount(1);
  });

  test("breadcrumb navigation is present and labelled", async ({ page }) => {
    await gotoShell(page);
    await expect(page.getByRole("navigation", { name: /breadcrumb/i })).toBeVisible();
  });
});

test.describe("responsive shell", () => {
  for (const vp of [
    { name: "mobile 360x800", width: 360, height: 800 },
    { name: "tablet 768x1024", width: 768, height: 1024 },
    { name: "desktop 1440x900", width: 1440, height: 900 }
  ]) {
    test(`${vp.name}: no horizontal overflow, palette reachable and fits`, async ({ page }) => {
      await page.setViewportSize({ width: vp.width, height: vp.height });
      await gotoShell(page);

      const overflow = await page.evaluate(() => ({
        scrollWidth: document.documentElement.scrollWidth,
        clientWidth: document.documentElement.clientWidth
      }));
      expect(overflow.scrollWidth).toBeLessThanOrEqual(overflow.clientWidth + 1);

      const triggerBox = await searchTrigger(page).boundingBox();
      expect(triggerBox).not.toBeNull();
      expect(triggerBox!.x).toBeGreaterThanOrEqual(0);
      expect(triggerBox!.x + triggerBox!.width).toBeLessThanOrEqual(vp.width + 1);

      await openWithCtrlK(page);
      const box = await page.locator(".command-dialog").boundingBox();
      expect(box).not.toBeNull();
      expect(box!.width).toBeLessThanOrEqual(vp.width + 1);
      expect(box!.x).toBeGreaterThanOrEqual(-1);
      await page.keyboard.press("Escape");

      const crumb = await page.getByRole("navigation", { name: /breadcrumb/i }).boundingBox();
      const h1 = await page.getByRole("heading", { level: 1, name: "Command Center" }).boundingBox();
      expect(crumb).not.toBeNull();
      expect(h1).not.toBeNull();
      // Narrow mobile widths can report 1–4px font-metric overlap; keep hierarchy, not pixel-perfect.
      const overlapSlack = vp.width <= 360 ? 4 : 1;
      expect(crumb!.y + crumb!.height).toBeLessThanOrEqual(h1!.y + overlapSlack);
    });
  }
});
