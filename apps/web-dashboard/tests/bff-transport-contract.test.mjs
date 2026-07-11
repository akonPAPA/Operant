import assert from "node:assert/strict";
import test from "node:test";
import {
  clientTenantHeaders,
  dashboardCoreApiBaseUrl,
  toProxiedCorePath,
  usesBffTransport
} from "../lib/api-transport.ts";

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
