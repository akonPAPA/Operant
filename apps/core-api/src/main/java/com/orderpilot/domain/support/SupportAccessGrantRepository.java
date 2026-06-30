package com.orderpilot.domain.support;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
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

  // OP-CAP-57 — tenant locator + JIT boundary. Grants are looked up ONLY for the specific staff principal,
  // so a locator can never surface a tenant the actor holds no grant for. The database filters ACTIVE,
  // approved, unexpired rows; the service defensively revalidates staff role and grant usability. Bounded
  // by how many grants one staff principal holds — never a full-tenant-table scan.
  List<SupportAccessGrant>
      findByStaffUserIdAndStatusAndApprovalStatusInAndExpiresAtAfter(
          UUID staffUserId,
          SupportAccessGrant.Status status,
          Collection<SupportAccessGrant.ApprovalStatus> approvalStatuses,
          Instant now);

  List<SupportAccessGrant>
      findByStaffUserIdAndTenantIdAndStatusAndApprovalStatusInAndExpiresAtAfter(
          UUID staffUserId,
          UUID tenantId,
          SupportAccessGrant.Status status,
          Collection<SupportAccessGrant.ApprovalStatus> approvalStatuses,
          Instant now);

  /** Tenant-scoped registry listing, newest first. Never returns another tenant's grants. */
  List<SupportAccessGrant> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);

  // OP-CAP-55 — read-only operations visibility (bounded, tenant-scoped via idx_support_access_grant_tenant).
  long countByTenantIdAndStatus(UUID tenantId, SupportAccessGrant.Status status);

  long countByTenantIdAndStatusAndApprovalStatusInAndExpiresAtAfter(
      UUID tenantId,
      SupportAccessGrant.Status status,
      Collection<SupportAccessGrant.ApprovalStatus> approvalStatuses,
      Instant now);

  long countByTenantIdAndApprovalStatus(UUID tenantId, SupportAccessGrant.ApprovalStatus approvalStatus);

  Optional<SupportAccessGrant> findFirstByTenantIdOrderByCreatedAtDesc(UUID tenantId);

  List<SupportAccessGrant> findByTenantIdOrderByCreatedAtDesc(UUID tenantId, Pageable pageable);
}
