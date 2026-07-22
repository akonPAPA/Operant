import { expect, test, type APIRequestContext, type Page } from "@playwright/test";

const LOCAL_APP = "http://localhost:3100";
const LOCAL_DEMO_APP = "http://localhost:3103";
const PROD_APP = "http://localhost:3101";
const PROD_UNAVAILABLE_APP = "http://localhost:3102";
const FAKE_CORE = "http://127.0.0.1:18080";
const BOOTSTRAP_TENANT = "11111111-1111-4111-8111-111111111111";

async function coreRequests(request: APIRequestContext) {
  const response = await request.get(`${FAKE_CORE}/__test/requests`);
  return (await response.json()) as { method: string; path: string; headers: Record<string, string> }[];
}

async function resetCoreRequests(request: APIRequestContext) {
  await request.delete(`${FAKE_CORE}/__test/requests`);
}

async function signIn(page: Page) {
  await page.context().clearCookies();
  const bootstrap = await page.request.post(`${LOCAL_APP}/api/auth/session`, {
    headers: { Origin: LOCAL_APP, Referer: `${LOCAL_APP}/login` }
  });
  expect(bootstrap.ok()).toBeTruthy();
  await page.goto(`${LOCAL_APP}/login`);
}

function csrfFromCookies(cookies: { name: string; value: string }[]): string {
  const csrf = cookies.find((c) => c.name === "op_csrf");
  if (!csrf) {
    throw new Error("op_csrf cookie missing");
  }
  return csrf.value;
}

test.beforeEach(async ({ request }) => {
  await resetCoreRequests(request);
});

test("protected page redirects to /login when no session exists", async ({ page }) => {
  await page.goto(`${LOCAL_APP}/order-journey`);
  await expect(page).toHaveURL(/\/login\?next=%2Forder-journey/);
});

test("production Node runtime denies bootstrap even with malicious local-test vars", async ({ request }) => {
  const response = await request.post(`${PROD_APP}/api/auth/session`, {
    headers: {
      host: "localhost:3101",
      origin: PROD_APP,
      "X-Tenant-Id": "evil-tenant",
      "X-OrderPilot-Permissions": "STAFF_SUPPORT_READ",
      cookie: "op_session=forged-session-cookie-value-0123456789abcdef"
    },
    data: { tenantId: "evil", permissions: ["ADMIN"] }
  });
  expect(response.status()).toBe(404);
  expect(response.headers()["set-cookie"]).toBeUndefined();
});

test("production deploy-profile downgrade cannot enable bootstrap session issuance", async ({ request }) => {
  // :3101 runs NODE_ENV=production with ORDERPILOT_DEPLOY_PROFILE=local-test intentionally.
  const response = await request.post(`${PROD_APP}/api/auth/session`, {
    headers: { origin: "https://operant.example.com" }
  });
  expect(response.status()).toBe(404);
  expect(response.headers()["set-cookie"]).toBeUndefined();
});

test("explicit local/test bootstrap creates a session with HttpOnly opaque cookie", async ({ page, context }) => {
  await signIn(page);
  const cookies = await context.cookies(LOCAL_APP);
  const session = cookies.find((c) => c.name === "op_session");
  expect(session).toBeDefined();
  expect(session!.httpOnly).toBe(true);
  expect(session!.value.length).toBeGreaterThanOrEqual(32);
  expect(session!.value).not.toContain(BOOTSTRAP_TENANT);
});

test("tenant read travels only through /api/bff with server-signed authority", async ({ page, request }) => {
  await signIn(page);
  const coreLeaks: string[] = [];
  page.on("request", (req) => {
    const url = new URL(req.url());
    if (url.port === "18080" || (url.pathname.startsWith("/api/v1/") && !url.pathname.startsWith("/api/bff/"))) {
      coreLeaks.push(req.url());
    }
  });
  await resetCoreRequests(request);
  const status = await page.evaluate(async () => {
    const response = await fetch("/api/bff/api/v1/quote-review/queue");
    return response.status;
  });
  expect(status).toBe(200);
  expect(coreLeaks).toEqual([]);
  const upstream = (await coreRequests(request)).filter((r) => r.path === "/api/v1/quote-review/queue");
  expect(upstream).toHaveLength(1);
  const call = upstream[upstream.length - 1]!;
  expect(call.headers["x-tenant-id"]).toBe(BOOTSTRAP_TENANT);
  expect(call.headers["x-orderpilot-gateway-signature"]).toBeTruthy();
  expect(call.headers["cookie"]).toBeUndefined();
  expect(call.headers["authorization"]).toBeUndefined();
});

test("invalid CSRF mutation is denied and never reaches Core", async ({ page, request }) => {
  await signIn(page);
  const status = await page.evaluate(async () => {
    const response = await fetch("/api/bff/api/v1/quote-review/33333333-3333-4333-8333-333333333333/assemble-draft", {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-OP-CSRF-Token": "wrong-token-0123456789abcdef" },
      body: JSON.stringify({ reasonCode: "READY" })
    });
    return response.status;
  });
  expect(status).toBe(403);
  const upstream = (await coreRequests(request)).filter((r) => r.method === "POST");
  expect(upstream).toHaveLength(0);
});

test("valid CSRF mutation reaches the bounded test Core exactly once", async ({ page, context, request }) => {
  await signIn(page);
  const csrf = csrfFromCookies(await context.cookies(LOCAL_APP));
  const status = await page.evaluate(async (token) => {
    const response = await fetch("/api/bff/api/v1/quote-review/33333333-3333-4333-8333-333333333333/assemble-draft", {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-OP-CSRF-Token": token },
      body: JSON.stringify({ reasonCode: "READY" })
    });
    return response.status;
  }, csrf);
  expect(status).toBe(200);
  const upstream = (await coreRequests(request)).filter(
    (r) => r.method === "POST" && r.path === "/api/v1/quote-review/33333333-3333-4333-8333-333333333333/assemble-draft"
  );
  expect(upstream).toHaveLength(1);
});

test("logout revokes the session and reusing the old cookie fails", async ({ page, context }) => {
  await signIn(page);
  const cookiesBefore = await context.cookies(LOCAL_APP);
  const oldSession = cookiesBefore.find((c) => c.name === "op_session")!.value;
  const csrf = csrfFromCookies(cookiesBefore);
  const logoutStatus = await page.evaluate(async (token) => {
    const response = await fetch("/api/auth/logout", {
      method: "POST",
      headers: { "X-OP-CSRF-Token": token }
    });
    return response.status;
  }, csrf);
  expect(logoutStatus).toBe(200);
  // reuse the revoked cookie directly against the BFF
  const replay = await page.request.get(`${LOCAL_APP}/api/bff/api/v1/quote-review/queue`, {
    headers: { cookie: `op_session=${oldSession}` }
  });
  expect(replay.status()).toBe(401);
});

test("internal support routes cannot be reached through the tenant BFF", async ({ page, request }) => {
  await signIn(page);
  const status = await page.evaluate(async () => {
    const response = await fetch("/api/bff/api/v1/internal/support/tenants/search?q=acme");
    return response.status;
  });
  expect(status).toBe(404);
  const upstream = (await coreRequests(request)).filter((r) => r.path.startsWith("/api/v1/internal/"));
  expect(upstream).toHaveLength(0);
});


test("production BFF upload is unavailable and issues zero upload requests", async ({ page, context, request }) => {
  await context.addCookies([{ name: "op_session", value: "opaque-session-cookie-value-0123456789abcdef", domain: "localhost", path: "/", httpOnly: true, sameSite: "Lax" }]);
  await page.goto(`${PROD_APP}/upload`);
  await expect(page.getByRole("heading", { name: "Not available" })).toBeVisible();
  await expect(page.locator('input[type="file"]')).toHaveCount(0);
  await expect(page.getByRole("button", { name: /^upload$/i })).toHaveCount(0);
  await expect(page.locator('a[href="/upload"]')).toHaveCount(0);
  const upstream = (await coreRequests(request)).filter((r) => r.path.includes("/intake/documents/upload"));
  expect(upstream).toHaveLength(0);
});

test("production with unavailable BFF configuration keeps upload fail-closed", async ({ page, request }) => {
  await page.goto(`${PROD_UNAVAILABLE_APP}/upload`);
  await expect(page.getByRole("heading", { name: "Not available" })).toBeVisible();
  await expect(page.locator('input[type="file"]')).toHaveCount(0);
  await expect(page.getByRole("button", { name: /^upload$/i })).toHaveCount(0);
  await expect(page.locator('a[href="/upload"]')).toHaveCount(0);
  const upstream = (await coreRequests(request)).filter((r) => r.path.includes("/intake/documents/upload"));
  expect(upstream).toHaveLength(0);
});

test("local demo upload stays unavailable without projected documents capability", async ({ page, request }) => {
  // Deployment alone is not authority: local-demo form requires VIEW_DOCUMENTS projection.
  // :3103 runs without BFF session bootstrap, so capability projection cannot ALLOW documents.
  await page.goto(`${LOCAL_DEMO_APP}/upload`);
  await expect(page.getByRole("heading", { name: /upload not available|not available/i })).toBeVisible();
  await expect(page.locator('input[type="file"]')).toHaveCount(0);
  await expect(page.getByRole("button", { name: /^upload$/i })).toHaveCount(0);
  const upstream = (await coreRequests(request)).filter((r) => r.path.includes("/intake/documents/upload"));
  expect(upstream).toHaveLength(0);
});
test("raw Core errors and internal headers are not exposed to the browser", async ({ page }) => {
  await signIn(page);
  const result = await page.evaluate(async () => {
    const response = await fetch("/api/bff/api/v1/analytics/overview");
    return {
      status: response.status,
      body: await response.text(),
      internalTrace: response.headers.get("X-Internal-Trace"),
      poweredBy: response.headers.get("X-Powered-By"),
      cacheControl: response.headers.get("Cache-Control")
    };
  });
  expect(result.status).toBe(502);
  expect(result.body).not.toContain("NullPointerException");
  expect(result.body).not.toContain("com.orderpilot");
  expect(result.internalTrace).toBeNull();
  expect(result.poweredBy).toBeNull();
  expect(result.cacheControl).toBe("no-store");
  const cookies = await page.context().cookies(LOCAL_APP);
  expect(cookies.find((c) => c.name === "core_internal_session")).toBeUndefined();
});
