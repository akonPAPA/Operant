package com.orderpilot.domain.support;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * OP-CAP-51 — tenant- and scope-scoped lookup for support access grants. Every lookup is keyed on the
 * staff principal AND the tenant AND the scope, so a grant minted for one tenant/scope can never satisfy
 * another (no cross-tenant or cross-scope reuse). Expiry is then enforced in the service against the clock.
 */
public interface SupportAccessGrantRepository extends JpaRepository<SupportAccessGrant, UUID> {
  /**
   * Active grants for a specific staff principal, tenant, and scope, newest-expiry first. The service
   * picks the first still-usable grant; an empty result means no grant (denied). The tenant/scope keys
   * make a wrong-tenant or wrong-scope request return empty by construction.
   */
  List<SupportAccessGrant> findByStaffUserIdAndTenantIdAndScopeAndStatusOrderByExpiresAtDesc(
      UUID staffUserId, UUID tenantId, StaffSupportScope scope, SupportAccessGrant.Status status);

  /** Tenant-scoped management lookup: a grant can only be found (and revoked) within its own tenant. */
  Optional<SupportAccessGrant> findByIdAndTenantId(UUID id, UUID tenantId);

  /** Tenant-scoped registry listing, newest first. Never returns another tenant's grants. */
  List<SupportAccessGrant> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);
}
