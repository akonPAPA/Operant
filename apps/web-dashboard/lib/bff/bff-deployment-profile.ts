const PRODUCTION_LIKE_DEPLOY_PROFILES = new Set(["prod", "production", "cloud", "staging"]);
const LOCAL_TEST_DEPLOY_PROFILES = new Set(["local", "test", "local-test"]);

export function deployProfile(): string {
  return process.env.ORDERPILOT_DEPLOY_PROFILE?.trim().toLowerCase() ?? "";
}

/**
 * Production-like deployment: bootstrap session issuance and memory session storage must fail
 * closed. An explicit production profile always wins; an explicit local/test profile marks the
 * runtime as non-production (used only for local E2E against a production build); with no
 * explicit profile, a production NODE_ENV is treated as production-like.
 */
export function isProductionLikeDeployment(): boolean {
  const profile = deployProfile();
  if (PRODUCTION_LIKE_DEPLOY_PROFILES.has(profile)) {
    return true;
  }
  if (LOCAL_TEST_DEPLOY_PROFILES.has(profile)) {
    return false;
  }
  return process.env.NODE_ENV === "production";
}

/**
 * Local/test bootstrap may mint a session only when explicitly enabled and the deployment is not
 * production-like. Bootstrap identity always comes from server env — never the request body,
 * query, headers, or cookies.
 */
export function isLocalTestBootstrapAllowed(): boolean {
  if (isProductionLikeDeployment()) {
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
