import assert from "node:assert/strict";

/** Demo/dev clients send X-Tenant-Id; BFF clients must not and use session transport instead. */
export function assertTenantScopedClientSource(source) {
  const bffAware =
    /usesBffTransport\(\)/.test(source)
    && /dashboardCoreApiBaseUrl/.test(source)
    && (/isDashboardApiAuthorityAvailable/.test(source) || /clientTenantHeaders/.test(source));
  const demoHeaders = /X-Tenant-Id/.test(source);
  assert.ok(bffAware || demoHeaders, "expected BFF-aware transport or demo X-Tenant-Id headers");
}

export function assertApiBaseUrlSource(source) {
  assert.ok(
    /dashboardCoreApiBaseUrl/.test(source) || /CORE_API_BASE_URL/.test(source),
    "expected dashboardCoreApiBaseUrl or CORE_API_BASE_URL"
  );
}

export function assertPermissionBoundary(source, permissionLiteral) {
  const usesClientHeaders =
    /clientTenantHeaders\(/.test(source) && new RegExp(permissionLiteral).test(source);
  const usesLiteralHeader = new RegExp(`X-OrderPilot-Permissions.*${permissionLiteral}`).test(source);
  assert.ok(
    usesClientHeaders || usesLiteralHeader,
    `expected ${permissionLiteral} via clientTenantHeaders or X-OrderPilot-Permissions`
  );
}

export function assertAuthorityGuardBeforeFetch(source, configSymbol = /[A-Za-z0-9]+Config|[A-Za-z0-9]+Client/) {
  assert.ok(
    /isDashboardApiAuthorityAvailable\(/.test(source),
    "expected BFF-aware isDashboardApiAuthorityAvailable guard before fetch"
  );
  assert.doesNotMatch(
    source,
    /if\s*\(\s*![A-Za-z0-9_.]+tenantId\s*\)/,
    "BFF-aware clients must not block production session transport with raw tenantId truthiness"
  );
  assert.match(source, configSymbol);
}
