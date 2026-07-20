export const FRONTEND_AUTHORITY_UNAVAILABLE_MESSAGE =
  "Authenticated dashboard access is unavailable.";

const unavailable = (reason) =>
  Object.freeze({
    available: false,
    mode: "unavailable",
    reason
  });

function isExplicitBffEnabled() {
  return String(process.env.NEXT_PUBLIC_ORDERPILOT_BFF_ENABLED ?? "").trim() === "true";
}

export function resolveFrontendAuthority({
  nodeEnv,
  demoMode,
  demoTenantId,
  bffEnabled = isExplicitBffEnabled()
} = {}) {
  if (nodeEnv === "production") {
    if (demoMode === "true") {
      return unavailable("DEMO_MODE_FORBIDDEN_IN_PRODUCTION");
    }
    return Object.freeze({
      available: true,
      mode: "bff-session",
      tenantId: ""
    });
  }

  if (nodeEnv !== "development" && nodeEnv !== "test") {
    return unavailable("UNSUPPORTED_RUNTIME");
  }

  if (demoMode !== "true") {
    if (bffEnabled) {
      return Object.freeze({
        available: true,
        mode: "bff-session",
        tenantId: ""
      });
    }
    return unavailable("DEMO_MODE_NOT_ENABLED");
  }

  const tenantId = typeof demoTenantId === "string" ? demoTenantId.trim() : "";
  if (!tenantId) {
    return unavailable("DEMO_TENANT_NOT_CONFIGURED");
  }

  return Object.freeze({
    available: true,
    mode: "demo",
    tenantId
  });
}

export function frontendAuthority() {
  return resolveFrontendAuthority({
    nodeEnv: process.env.NODE_ENV,
    demoMode: process.env.NEXT_PUBLIC_ORDERPILOT_DEMO_MODE,
    demoTenantId: process.env.NEXT_PUBLIC_DEMO_TENANT_ID
  });
}

export function demoTenantId() {
  const authority = frontendAuthority();
  return authority.available ? authority.tenantId : "";
}

export function hasDemoAuthority() {
  return frontendAuthority().available;
}

export function requireDemoTenantId() {
  const authority = frontendAuthority();
  if (!authority.available) {
    throw new Error(FRONTEND_AUTHORITY_UNAVAILABLE_MESSAGE);
  }
  if (authority.mode === "bff-session") {
    return "";
  }
  return authority.tenantId;
}

export function missingFrontendAuthorityMessage(area) {
  return `${FRONTEND_AUTHORITY_UNAVAILABLE_MESSAGE} Cannot ${area}.`;
}
