import { defineConfig } from "@playwright/test";

/**
 * P1-B browser E2E. Run `npm run build` first — both app servers start from the same
 * production build (`next start`) with different runtime env:
 *  - :3100 explicit local/test profile with bounded bootstrap identity (memory sessions)
 *  - :3101 production profile — sign-in must fail closed (no P1-C trusted identity yet)
 *  - :18080 bounded fake Core recording every request that crosses the BFF boundary
 */
const SESSION_SECRET = "p1b-e2e-session-secret-0123456789abcdef01234567";
const GATEWAY_SECRET = "p1b-e2e-gateway-secret-test-only";

const sharedEnv = {
  // explicit runtime override: a local .env.local demo profile must not leak into E2E
  NEXT_PUBLIC_ORDERPILOT_DEMO_MODE: "false",
  ORDERPILOT_DEMO_MODE: "false",
  ORDERPILOT_BFF_ENABLED: "true",
  ORDERPILOT_BFF_SESSION_SECRET: SESSION_SECRET,
  ORDERPILOT_GATEWAY_HEADER_AUTH_SHARED_SECRET: GATEWAY_SECRET,
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
      command: "npx next start -p 3100",
      url: "http://localhost:3100/api/bff/health",
      reuseExistingServer: false,
      timeout: 120_000,
      env: {
        ...sharedEnv,
        ORDERPILOT_DEPLOY_PROFILE: "local-test",
        ORDERPILOT_BFF_LOCAL_TEST_BOOTSTRAP: "true",
        ORDERPILOT_BFF_SESSION_STORE: "memory",
        ORDERPILOT_BFF_BOOTSTRAP_TENANT_ID: "11111111-1111-4111-8111-111111111111",
        ORDERPILOT_BFF_BOOTSTRAP_ACTOR_ID: "22222222-2222-4222-8222-222222222222",
        ORDERPILOT_BFF_BOOTSTRAP_PERMISSIONS: "REVIEW_READ,REVIEW_ACTION,ANALYTICS_READ"
      }
    },
    {
      command: "npx next start -p 3101",
      url: "http://localhost:3101/api/bff/health",
      reuseExistingServer: false,
      timeout: 120_000,
      env: {
        ...sharedEnv,
        // production profile: bootstrap flags are present but MUST be ignored (fail closed)
        ORDERPILOT_DEPLOY_PROFILE: "production",
        ORDERPILOT_BFF_LOCAL_TEST_BOOTSTRAP: "true",
        ORDERPILOT_BFF_SESSION_STORE: "memory",
        ORDERPILOT_BFF_REDIS_URL: "redis://127.0.0.1:63999",
        ORDERPILOT_BFF_BOOTSTRAP_TENANT_ID: "11111111-1111-4111-8111-111111111111",
        ORDERPILOT_BFF_BOOTSTRAP_ACTOR_ID: "22222222-2222-4222-8222-222222222222",
        ORDERPILOT_BFF_BOOTSTRAP_PERMISSIONS: "REVIEW_READ"
      }
    }
  ]
});
