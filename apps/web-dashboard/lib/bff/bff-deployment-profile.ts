const PRODUCTION_LIKE_DEPLOY_PROFILES = new Set(["prod", "production", "cloud", "staging"]);
const LOCAL_TEST_DEPLOY_PROFILES = new Set(["local", "test", "local-test"]);

export function deployProfile(): string {
  return process.env.ORDERPILOT_DEPLOY_PROFILE?.trim().toLowerCase() ?? "";
}

/**
 * Runtime NODE_ENV read that must not be compile-time inlined by Next/SWC.
 * Production builds replace bare `process.env.NODE_ENV` with the string "production";
 * security gates that depend on the live process environment must use this helper.
 */
export function runtimeNodeEnv(): string {
  const key = ["NODE", "ENV"].join("_");
  return String(process.env[key] ?? "");
}

/** True only for a real production Node runtime. */
export function isProductionNodeRuntime(): boolean {
  return runtimeNodeEnv() === "production";
}

/**
 * Production-like deployment: bootstrap session issuance and memory session storage must fail
 * closed.
 *
 * Invariant: a production Node runtime is always production-like. A deploy-profile environment
 * variable must never downgrade a production Node runtime to local/test semantics.
 *
 * Non-production Node runtimes:
 * - explicit prod/production/cloud/staging profile -> production-like
 * - explicit local/test/local-test profile -> local/test
 * - missing profile -> local development (not production-like)
 */
export function isProductionLikeDeployment(): boolean {
  if (isProductionNodeRuntime()) {
    return true;
  }
  const profile = deployProfile();
  if (PRODUCTION_LIKE_DEPLOY_PROFILES.has(profile)) {
    return true;
  }
  if (LOCAL_TEST_DEPLOY_PROFILES.has(profile)) {
    return false;
  }
  return false;
}

/**
 * Local/test bootstrap may mint a session only when explicitly enabled and the Node runtime is not
 * production. Bootstrap identity always comes from server env - never the request body, query,
 * headers, or cookies.
 */
export function isLocalTestBootstrapAllowed(): boolean {
  if (isProductionNodeRuntime()) {
    return false;
  }
  if (isProductionLikeDeployment()) {
    return false;
  }
  if (!LOCAL_TEST_DEPLOY_PROFILES.has(deployProfile())) {
    return false;
  }
  if (process.env.ORDERPILOT_BFF_LOCAL_TEST_BOOTSTRAP !== "true") {
    return false;
  }
  if (process.env.ORDERPILOT_BFF_ENABLED !== "true") {
    return false;
  }
  return true;
}
