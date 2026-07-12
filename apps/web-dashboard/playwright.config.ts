import { defineConfig } from "@playwright/test";

/**
 * P1-B browser E2E. Run `npm run build` first — both app servers start from the same
 * production standalone artifact with different runtime env:
 *  - :3100 ORDERPILOT_E2E_RUNTIME_NODE_ENV=test + local/test bootstrap bridge (harness only)
 *  - :3101 real production Node runtime with malicious local-test vars — bootstrap must stay denied
 *  - :18080 bounded fake Core recording every request that crosses the BFF boundary
 */
const GATEWAY_SECRET = "a3f91c7e2b4d8056e1a9c0d4f7b26385e6a1d9c2b4f70835a6e9c1d2b3f40517";

const sharedEnv = {
  // explicit runtime override: a local .env.local demo profile must not leak into E2E
  NEXT_PUBLIC_ORDERPILOT_DEMO_MODE: "false",
  ORDERPILOT_DEMO_MODE: "false",
  ORDERPILOT_BFF_ENABLED: "true",
  ORDERPILOT_GATEWAY_SHARED_SECRET: GATEWAY_SECRET,
  CORE_API_BASE_URL: "http://127.0.0.1:18080"
};

export default defineConfig({
  testDir: "./e2e",
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [["list"], ["html", { outputFolder: "playwright-report", open: "never" }]],
  use: {
    baseURL: "http://localhost:3100",
    trace: "retain-on-failure"
  },
  webServer: [
    {
      command: "node e2e/fake-core-server.mjs",
      url: "http://127.0.0.1:18080/__test/requests",
      reuseExistingServer: true,
      timeout: 30_000
    },
    {
      command: "node e2e/standalone-server.mjs --port 3100",
      url: "http://localhost:3100/api/bff/health",
      reuseExistingServer: false,
      timeout: 120_000,
      env: {
        ...sharedEnv,
        // E2E-only: non-production security semantics without rewriting Next standalone NODE_ENV.
        ORDERPILOT_E2E_RUNTIME_NODE_ENV: "test",
        ORDERPILOT_DEPLOY_PROFILE: "local-test",
        ORDERPILOT_PUBLIC_ORIGIN: "http://localhost:3100",
        ORDERPILOT_BFF_LOCAL_TEST_BOOTSTRAP: "true",
        ORDERPILOT_BFF_SESSION_STORE: "memory",
        ORDERPILOT_BFF_BOOTSTRAP_TENANT_ID: "11111111-1111-4111-8111-111111111111",
        ORDERPILOT_BFF_BOOTSTRAP_ACTOR_ID: "22222222-2222-4222-8222-222222222222",
        ORDERPILOT_BFF_BOOTSTRAP_PERMISSIONS: "REVIEW_READ,REVIEW_ACTION,ANALYTICS_READ"
      }
    },
    {
      command: "node e2e/standalone-server.mjs --port 3101",
      url: "http://localhost:3101/api/bff/health",
      reuseExistingServer: false,
      timeout: 120_000,
      env: {
        ...sharedEnv,
        // Production Node runtime: malicious local-test/bootstrap vars must be ignored.
        NODE_ENV: "production",
        ORDERPILOT_DEPLOY_PROFILE: "local-test",
        ORDERPILOT_PUBLIC_ORIGIN: "https://operant.example.com",
        ORDERPILOT_BFF_LOCAL_TEST_BOOTSTRAP: "true",
        ORDERPILOT_BFF_SESSION_STORE: "memory",
        ORDERPILOT_BFF_REDIS_URL: "redis://127.0.0.1:63999",
        ORDERPILOT_BFF_SESSION_SECRET: "legacy-secret-must-not-enable-bootstrap-0123456789ab",
        ORDERPILOT_LOCAL_BOOTSTRAP_SECRET: "legacy-local-secret-must-not-enable-0123456789abcdef",
        ORDERPILOT_BFF_BOOTSTRAP_TENANT_ID: "11111111-1111-4111-8111-111111111111",
        ORDERPILOT_BFF_BOOTSTRAP_ACTOR_ID: "22222222-2222-4222-8222-222222222222",
        ORDERPILOT_BFF_BOOTSTRAP_PERMISSIONS: "REVIEW_READ"
      }
    }
  ]
});
