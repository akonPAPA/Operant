import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { join } from "node:path";
import { pathToFileURL } from "node:url";
import ts from "typescript";
import {
  clientTenantHeaders,
  dashboardCoreApiBaseUrl,
  enrichDashboardRequestInit,
  isDashboardApiAuthorityAvailable,
  toProxiedCorePath,
  usesBffTransport
} from "../lib/api-transport.ts";
import {
  isUploadAvailable,
  uploadCapability
} from "../lib/upload-capability.ts";
import { navigationGroupsForUploadCapability } from "../components/navigation.ts";

const ENV_KEYS = [
  "NODE_ENV",
  "NEXT_PUBLIC_ORDERPILOT_DEMO_MODE",
  "NEXT_PUBLIC_CORE_API_URL",
  "CORE_API_BASE_URL",
  "ORDERPILOT_BFF_ENABLED"
];

function withEnv(vars, fn) {
  const prior = {};
  for (const key of ENV_KEYS) {
    prior[key] = process.env[key];
    delete process.env[key];
  }
  Object.assign(process.env, vars);
  try {
    return fn();
  } finally {
    for (const key of ENV_KEYS) {
      if (prior[key] === undefined) {
        delete process.env[key];
      } else {
        process.env[key] = prior[key];
      }
    }
  }
}

function inBrowser(fn) {
  const hadWindow = Object.prototype.hasOwnProperty.call(globalThis, "window");
  const priorWindow = globalThis.window;
  globalThis.window = {};
  try {
    return fn();
  } finally {
    if (hadWindow) {
      globalThis.window = priorWindow;
    } else {
      delete globalThis.window;
    }
  }
}

test("production browser bundle deterministically uses same-origin /api/bff", () => {
  inBrowser(() =>
    withEnv(
      {
        NODE_ENV: "production",
        // even hostile/leftover public env cannot redirect the production browser transport
        NEXT_PUBLIC_CORE_API_URL: "http://evil.example:9999",
        CORE_API_BASE_URL: "http://internal-core:8080"
      },
      () => {
        assert.equal(usesBffTransport(), true);
        assert.equal(dashboardCoreApiBaseUrl(), "/api/bff");
        assert.equal(
          toProxiedCorePath("/api/v1/quote-review/queue"),
          "/api/bff/api/v1/quote-review/queue"
        );
      }
    )
  );
});

test("production BFF mode disables upload capability and navigation", () => {
  inBrowser(() =>
    withEnv({ NODE_ENV: "production" }, () => {
      assert.equal(uploadCapability(), "NOT_AVAILABLE_PRODUCTION_BFF");
      assert.equal(isUploadAvailable(), false);
      const hrefs = navigationGroupsForUploadCapability(uploadCapability()).flatMap((group) =>
        group.items.map((item) => item.href)
      );
      assert.equal(hrefs.includes("/upload"), false);
    })
  );
});


test("production server with unavailable BFF configuration disables upload capability", () => {
  withEnv({ NODE_ENV: "production", ORDERPILOT_BFF_ENABLED: "false", ORDERPILOT_DEMO_MODE: "false" }, () => {
    assert.equal(uploadCapability(), "NOT_AVAILABLE_PRODUCTION_CONFIGURATION");
    assert.equal(isUploadAvailable(), false);
    const hrefs = navigationGroupsForUploadCapability(uploadCapability()).flatMap((group) =>
      group.items.map((item) => item.href)
    );
    assert.equal(hrefs.includes("/upload"), false);
  });
});
test("local demo mode keeps upload capability available", () => {
  withEnv({ NODE_ENV: "development" }, () => {
    assert.equal(uploadCapability(), "AVAILABLE_LOCAL_DEMO");
    assert.equal(isUploadAvailable(), true);
    const hrefs = navigationGroupsForUploadCapability(uploadCapability()).flatMap((group) =>
      group.items.map((item) => item.href)
    );
    assert.equal(hrefs.includes("/upload"), true);
  });
});
test("production browser bundle never falls back to localhost:8080 without env", () => {
  inBrowser(() =>
    withEnv({ NODE_ENV: "production" }, () => {
      assert.equal(dashboardCoreApiBaseUrl(), "/api/bff");
      assert.ok(!dashboardCoreApiBaseUrl().includes("localhost"));
    })
  );
});

test("production browser transport does not depend on private ORDERPILOT_BFF_ENABLED", () => {
  inBrowser(() =>
    withEnv({ NODE_ENV: "production" }, () => {
      // private server env is not inlined into browser bundles; absent here — still BFF
      assert.equal(process.env.ORDERPILOT_BFF_ENABLED, undefined);
      assert.equal(usesBffTransport(), true);
    })
  );
});

test("BFF transport sends no client-owned authority headers", () => {
  inBrowser(() =>
    withEnv({ NODE_ENV: "production" }, () => {
      const headers = clientTenantHeaders("evil-tenant", "ADMIN_SETTINGS_MANAGE");
      assert.equal(headers["X-Tenant-Id"], undefined);
      assert.equal(headers["X-OrderPilot-Permissions"], undefined);
    })
  );
});
test("BFF authority accepts empty browser tenant and strips forged authority headers", () => {
  inBrowser(() =>
    withEnv({ NODE_ENV: "production" }, () => {
      assert.equal(isDashboardApiAuthorityAvailable(""), true);
      const init = enrichDashboardRequestInit({
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "X-Tenant-Id": "evil-tenant",
          "x-orderpilot-permissions": "INTERNAL_ADMIN"
        }
      });
      const headers = new Headers(init.headers);
      assert.equal(headers.get("X-Tenant-Id"), null);
      assert.equal(headers.get("X-OrderPilot-Permissions"), null);
      assert.equal(headers.get("Content-Type"), "application/json");
    })
  );
});

async function withProductionBrowserFetch(fn) {
  const prior = {
    NODE_ENV: process.env.NODE_ENV,
    NEXT_PUBLIC_ORDERPILOT_DEMO_MODE: process.env.NEXT_PUBLIC_ORDERPILOT_DEMO_MODE,
    NEXT_PUBLIC_CORE_API_URL: process.env.NEXT_PUBLIC_CORE_API_URL,
    CORE_API_BASE_URL: process.env.CORE_API_BASE_URL,
    fetch: globalThis.fetch,
    window: globalThis.window,
    document: globalThis.document,
    hadWindow: Object.prototype.hasOwnProperty.call(globalThis, "window"),
    hadDocument: Object.prototype.hasOwnProperty.call(globalThis, "document")
  };
  const calls = [];
  process.env.NODE_ENV = "production";
  delete process.env.NEXT_PUBLIC_ORDERPILOT_DEMO_MODE;
  process.env.NEXT_PUBLIC_CORE_API_URL = "http://evil.example:9999";
  process.env.CORE_API_BASE_URL = "http://internal-core:8080";
  globalThis.window = {};
  globalThis.document = { cookie: "op_csrf=csrf-token-0123456789abcdef012345" };
  globalThis.fetch = async (url, init = {}) => {
    calls.push({ url: String(url), init });
    return new Response("{}", { status: 200, headers: { "Content-Type": "application/json" } });
  };
  try {
    await fn(calls);
  } finally {
    if (prior.NODE_ENV === undefined) delete process.env.NODE_ENV; else process.env.NODE_ENV = prior.NODE_ENV;
    if (prior.NEXT_PUBLIC_ORDERPILOT_DEMO_MODE === undefined) delete process.env.NEXT_PUBLIC_ORDERPILOT_DEMO_MODE; else process.env.NEXT_PUBLIC_ORDERPILOT_DEMO_MODE = prior.NEXT_PUBLIC_ORDERPILOT_DEMO_MODE;
    if (prior.NEXT_PUBLIC_CORE_API_URL === undefined) delete process.env.NEXT_PUBLIC_CORE_API_URL; else process.env.NEXT_PUBLIC_CORE_API_URL = prior.NEXT_PUBLIC_CORE_API_URL;
    if (prior.CORE_API_BASE_URL === undefined) delete process.env.CORE_API_BASE_URL; else process.env.CORE_API_BASE_URL = prior.CORE_API_BASE_URL;
    globalThis.fetch = prior.fetch;
    if (prior.hadWindow) globalThis.window = prior.window; else delete globalThis.window;
    if (prior.hadDocument) globalThis.document = prior.document; else delete globalThis.document;
  }
}

async function importFreshClient(relativePath) {
  const sourcePath = join(process.cwd(), relativePath);
  const apiTransportUrl = pathToFileURL(join(process.cwd(), "lib", "api-transport.ts")).href;
  const frontendAuthorityUrl = pathToFileURL(join(process.cwd(), "lib", "frontend-authority.mjs")).href;
  const dashboardHttpUrl = pathToFileURL(join(process.cwd(), "lib", "dashboard-http.ts")).href;
  const source = readFileSync(sourcePath, "utf8")
    .replaceAll('from "./api-transport"', `from "${apiTransportUrl}"`)
    .replaceAll('from "./api-transport.ts"', `from "${apiTransportUrl}"`)
    .replaceAll('from "./dashboard-http"', `from "${dashboardHttpUrl}"`)
    .replaceAll('from "./dashboard-http.ts"', `from "${dashboardHttpUrl}"`)
    .replaceAll('from "./frontend-authority.mjs"', `from "${frontendAuthorityUrl}"`);
  const transpiled = ts.transpileModule(source, {
    compilerOptions: {
      module: ts.ModuleKind.ES2022,
      target: ts.ScriptTarget.ES2022
    }
  });
  const encoded = Buffer.from(transpiled.outputText, "utf8").toString("base64");
  return import(`data:text/javascript;base64,${encoded}#${Date.now()}-${Math.random()}`);
}

function assertNoBrowserAuthorityHeaders(init) {
  const headers = new Headers(init.headers);
  assert.equal(headers.get("X-Tenant-Id"), null);
  assert.equal(headers.get("X-OrderPilot-Permissions"), null);
}

test("production BFF read clients continue with empty browser tenantId", async () => {
  await withProductionBrowserFetch(async (calls) => {
    const { listRecentAiWork } = await importFreshClient("lib/ai-work-api.ts");
    const result = await listRecentAiWork(7);
    assert.equal(result.error, undefined);
    assert.equal(calls.length, 1);
    assert.equal(calls[0].url, "/api/bff/api/v1/ai-work/suggestions?limit=7");
    assert.equal(calls[0].init.method, "GET");
    assertNoBrowserAuthorityHeaders(calls[0].init);
  });
});

test("production BFF mutation clients attach CSRF without browser authority", async () => {
  await withProductionBrowserFetch(async (calls) => {
    const { acceptAiWorkSuggestion } = await importFreshClient("lib/ai-work-api.ts");
    const result = await acceptAiWorkSuggestion("suggestion-1", { reason: "looks good" });
    assert.equal(result.error, undefined);
    assert.equal(calls.length, 1);
    assert.equal(calls[0].url, "/api/bff/api/v1/ai-work/suggestions/suggestion-1/accept");
    assert.equal(calls[0].init.method, "POST");
    const headers = new Headers(calls[0].init.headers);
    assert.equal(headers.get("X-OP-CSRF-Token"), "csrf-token-0123456789abcdef012345");
    assertNoBrowserAuthorityHeaders(calls[0].init);
  });
});

test("explicit static demo bundle keeps demo transport (not a production tenant surface)", () => {
  inBrowser(() =>
    withEnv(
      {
        NODE_ENV: "production",
        NEXT_PUBLIC_ORDERPILOT_DEMO_MODE: "true",
        NEXT_PUBLIC_CORE_API_URL: "http://demo-core.example"
      },
      () => {
        assert.equal(usesBffTransport(), false);
        assert.equal(dashboardCoreApiBaseUrl(), "http://demo-core.example");
      }
    )
  );
});

test("local development (server runtime) keeps demo-dev transport", () => {
  withEnv({ NODE_ENV: "development" }, () => {
    assert.equal(usesBffTransport(), false);
    assert.equal(dashboardCoreApiBaseUrl(), "http://localhost:8080");
  });
});

test("no NEXT_PUBLIC gateway or session secrets exist anywhere in the dashboard", async () => {
  const { readFileSync, readdirSync } = await import("node:fs");
  const { join } = await import("node:path");
  const offenders = [];
  const scan = (dir) => {
    let entries;
    try {
      entries = readdirSync(dir, { withFileTypes: true });
    } catch (error) {
      if (error?.code === "ENOENT" || error?.code === "ENOTDIR") return;
      throw error;
    }
    for (const entry of entries) {
      if (entry.isSymbolicLink()) continue;
      const full = join(dir, entry.name);
      if (entry.isDirectory()) {
        if (entry.name === "node_modules" || entry.name === ".next") continue;
        scan(full);
      } else if (entry.isFile() && /\.(ts|tsx|mjs)$/.test(entry.name)) {
        let source;
        try {
          source = readFileSync(full, "utf8");
        } catch (error) {
          if (error?.code === "ENOENT" || error?.code === "ENOTDIR") continue;
          throw error;
        }
        if (/NEXT_PUBLIC_[A-Z_]*(SECRET|GATEWAY|SESSION)/.test(source)) {
          offenders.push(full);
        }
      }
    }
  };
  scan(join(process.cwd(), "lib"));
  scan(join(process.cwd(), "app"));
  assert.deepEqual(offenders, []);
});
