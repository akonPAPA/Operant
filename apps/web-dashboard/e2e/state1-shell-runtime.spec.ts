import { expect, test, type Page } from "@playwright/test";

/**
 * State 1 shell runtime proof — genuine browser behavior for the WP3 command palette and the
 * responsive/accessible application shell. Reuses the existing P1-B dev runtime (:3100) and its
 * local-test bootstrap session; it starts no new server harness.
 *
 * Bootstrap actor permissions at :3100 are REVIEW_READ, REVIEW_ACTION, ANALYTICS_READ, so
 * capability-gated tenant destinations are offered here. These tests assert live DOM behavior and
 * DOM measurements — not screenshots — for opening/closing, keyboard navigation, focus management,
 * plane isolation, and responsiveness. `next dev` compiles routes on demand and hydrates the client
 * island asynchronously, so palette opening is wrapped in a retry (mirroring the login helper) to be
 * robust against hydration timing rather than racing it.
 */
const LOCAL_APP = "http://localhost:3100";
const SHELL_PAGE = `${LOCAL_APP}/command-center`;

const dialog = (page: Page) => page.getByRole("dialog", { name: /command palette/i });
const searchInput = (page: Page) => page.getByRole("combobox", { name: /search navigation/i });
const searchTrigger = (page: Page) => page.getByRole("button", { name: /search/i });

async function signIn(page: Page) {
  await page.goto(`${LOCAL_APP}/login`);
  const continueButton = page.getByRole("button", { name: /continue/i });
  await expect(async () => {
    await continueButton.click();
    await page.waitForURL((url) => url.origin === LOCAL_APP && url.pathname !== "/login", { timeout: 3000 });
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

test.describe("command palette — open/close and focus", () => {
  test("Ctrl+K opens, Escape closes, and focus returns to the trigger", async ({ page }) => {
    await gotoShell(page);
    await expect(dialog(page)).toHaveCount(0);

    await openWithCtrlK(page);
    await expect(searchInput(page)).toBeFocused();

    await page.keyboard.press("Escape");
    await expect(dialog(page)).toHaveCount(0);
    const activeHasPopup = await page.evaluate(() => document.activeElement?.getAttribute("aria-haspopup"));
    expect(activeHasPopup).toBe("dialog");
  });

  test("trigger click opens and an outside click closes", async ({ page }) => {
    await gotoShell(page);
    await openPaletteWith(page, () => searchTrigger(page).click());
    await page.mouse.click(5, 5); // overlay outside the dialog card — deterministic close
    await expect(dialog(page)).toHaveCount(0);
  });

  test("a plain 'k' with no modifier does not open the palette (no typing hijack)", async ({ page }) => {
    await gotoShell(page);
    await openWithCtrlK(page); // prove the palette is hydrated and openable...
    await page.keyboard.press("Escape");
    await expect(dialog(page)).toHaveCount(0);
    await page.keyboard.press("k"); // ...then prove a bare key does NOT open it
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

      // Trigger reachable within the viewport.
      const triggerBox = await searchTrigger(page).boundingBox();
      expect(triggerBox).not.toBeNull();
      expect(triggerBox!.x).toBeGreaterThanOrEqual(0);
      expect(triggerBox!.x + triggerBox!.width).toBeLessThanOrEqual(vp.width + 1);

      // Palette opens and fits inside the viewport.
      await openWithCtrlK(page);
      const box = await page.locator(".command-dialog").boundingBox();
      expect(box).not.toBeNull();
      expect(box!.width).toBeLessThanOrEqual(vp.width + 1);
      expect(box!.x).toBeGreaterThanOrEqual(-1);
      await page.keyboard.press("Escape");

      // Breadcrumb does not cover the H1 (they must not vertically overlap).
      const crumb = await page.getByRole("navigation", { name: /breadcrumb/i }).boundingBox();
      const h1 = await page.getByRole("heading", { level: 1, name: "Command Center" }).boundingBox();
      expect(crumb).not.toBeNull();
      expect(h1).not.toBeNull();
      expect(crumb!.y + crumb!.height).toBeLessThanOrEqual(h1!.y + 1);
    });
  }
});
