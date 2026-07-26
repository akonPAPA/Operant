package com.orderpilot.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * OP-CAP-57 — response contracts for the internal support <b>tenant locator + JIT support-grant boundary</b>
 * surface (read-only). This stage replaces the demo-tenant assumption of OP-CAP-56 with a safe locator: a
 * staff actor can only discover/select tenants for which they hold an active, approved, unexpired support
 * grant. It adds NO new mutation and NO new executor.
 *
 * <p>Contract law (proven by {@code SupportTenantLocatorContractTest}):
 * <ul>
 *   <li>Every field is an operator-safe scalar/enum/handle/timestamp. There is NO secret, credential,
 *       token, api key, password, connector capability, raw payload, raw customer/business data, audit
 *       internal, storage/source id, or internal actor/grant id.</li>
 *   <li>{@code tenantId} is exposed ONLY as a navigation/resource handle — it is never authority. The
 *       backend re-resolves the staff actor and re-validates an active grant on every support API call.</li>
 *   <li>A safe display name/slug is the primary human-facing identity; the raw tenant id is a handle.</li>
 *   <li>Results are bounded by page/size and are filtered to tenants the staff actor may actually support.</li>
 * </ul>
 */
public final class SupportTenantLocatorDtos {
  private SupportTenantLocatorDtos() {}

  /**
   * OP-CAP-57 — one safe tenant locator result. Carries a safe display handle/name, lifecycle status, the
   * staff actor's active support scope names for this tenant, and the gating diagnostics grant expiry. It
   * exposes no raw customer/business data, no grant id, no actor id, and no secret/connector internal.
   */
  public record SupportTenantLocatorResult(
      UUID tenantId,
      String displayName,
      String slug,
      String status,
      List<String> supportScopes,
      Instant grantExpiresAt,
      boolean readOnly,
      String externalExecution) {}

  /**
   * OP-CAP-57 — a bounded page of locator results. The page is always bounded (capped page size); it never
   * streams the full tenant table and never returns a tenant the staff actor cannot support.
   */
  public record SupportTenantSearchResponse(
      String query,
      int page,
      int pageSize,
      int returnedCount,
      boolean hasMore,
      List<SupportTenantLocatorResult> results,
      Instant generatedAt) {}

  /**
   * OP-CAP-57 — safe read-only support context for a single selected tenant. Returned only when the staff
   * actor has an active, approved, unexpired diagnostics grant for that tenant; otherwise the request is
   * denied with a generic message that does not reveal whether the tenant exists. Exposes only safe display
   * fields, the active scope names, the grant expiry, and read-only capability flags — no secret, no raw
   * customer data, no actor/grant id, no connector credential, and no audit internal.
   */
  public record SupportTenantContextResponse(
      UUID tenantId,
      String displayName,
      String slug,
      String status,
      List<String> supportScopes,
      Instant grantExpiresAt,
      boolean readOnly,
      boolean canViewOperations,
      String externalExecution,
      Instant generatedAt) {}
}
