package com.orderpilot.integration.testdb;

import static com.orderpilot.support.DatabaseIntegrationTestBase.CLEAN;
import static com.orderpilot.support.DatabaseIntegrationTestBase.TENANTS;
import static com.orderpilot.support.DatabaseIntegrationTestBase.USERS_ROLES;
import static com.orderpilot.support.TestTenantFixtures.TENANT_A;
import static com.orderpilot.support.TestTenantFixtures.TENANT_B;
import static com.orderpilot.support.TestUserFixtures.USER_A;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderpilot.application.services.support.SupportAccessDeniedException;
import com.orderpilot.application.services.support.SupportAccessService;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.support.StaffRole;
import com.orderpilot.domain.support.StaffSupportScope;
import com.orderpilot.domain.support.StaffUser;
import com.orderpilot.domain.support.StaffUserRepository;
import com.orderpilot.domain.support.SupportAccessGrant;
import com.orderpilot.domain.support.SupportAccessGrantRepository;
import com.orderpilot.support.DatabaseIntegrationTestBase;
import com.orderpilot.support.RequiresPostgresIntegration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

/**
 * OP-CAP-51/52 support-plane persistence proof against real PostgreSQL (PR #248, proof area G).
 *
 * <p>#247 proved the {@code StaffIdentityResolver} seam and the {@code SupportAccessGrant} lifecycle matrix
 * on H2 (schema built from JPA entities via {@code ddl-auto}, Flyway disabled). This class re-proves the
 * <b>persistence</b> side under the real Flyway/Postgres schema: UUID primary keys, {@code TIMESTAMPTZ}
 * expiry, {@code VARCHAR}-mapped enums ({@code status}/{@code approval_status}/{@code scope}), the tenant +
 * staff foreign keys, and the tenant-/scope-scoped repository queries. It is one compact storage-layer proof,
 * not a re-run of the 12-case lifecycle matrix.
 *
 * <p><b>PG-248-01 (found AND fixed in PR #248):</b> {@code audit_event.actor_id} originally carried a Flyway
 * FK {@code REFERENCES user_account(id)} that H2 never creates. {@code InternalSupportController} records
 * every support audit with the acting <i>staff principal</i> id ({@code staffIdentityResolver…staffUserId()}),
 * which is not a {@code user_account} row, so on real Postgres the support-plane audit writes (allow AND deny)
 * violated that FK and aborted. Migration {@code V66__audit_event_actor_id_polymorphic_principal.sql} drops
 * that FK — {@code actor_id} is an opaque/polymorphic principal id spanning multiple identity domains.
 * {@link #supportServiceAuditWritesPersistForStaffActorsUnderPostgres()} and
 * {@link #auditActorIdFkToUserAccountIsDroppedAndAcceptsStaffAndTenantActors()} are the positive proof of the
 * fix; the earlier defect-characterization test was converted to them.
 */
@Sql(scripts = {CLEAN, TENANTS, USERS_ROLES})
@RequiresPostgresIntegration
class SupportGrantPersistencePostgresIntegrationTest extends DatabaseIntegrationTestBase {

  @Autowired private SupportAccessService supportAccessService;
  @Autowired private StaffUserRepository staffUserRepository;
  @Autowired private SupportAccessGrantRepository grantRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  private static final Instant CREATED_AT = Instant.parse("2026-01-01T00:00:00Z");
  private static final Instant FAR_FUTURE = Instant.parse("2099-01-01T00:00:00Z");

  // staff_user has no tenant FK and is not covered by the tenant-CASCADE in clean.sql; handle is UNIQUE, so
  // each principal uses a random handle to stay isolated across methods and repeat runs on a persistent cluster.
  private StaffUser persistActiveStaff(StaffRole role) {
    return staffUserRepository.save(new StaffUser("pg-proof-" + UUID.randomUUID(), role, CREATED_AT));
  }

  private SupportAccessGrant saveGrant(
      UUID staffUserId, UUID tenantId, StaffSupportScope scope, String caseRef, Instant expiresAt) {
    // created_by is a plain nullable UUID (no FK in V62); USER_A is a real, seeded user_account only so the
    // row is representative — the grant row itself never depends on it.
    return grantRepository.save(
        new SupportAccessGrant(staffUserId, tenantId, scope, caseRef, expiresAt, USER_A, CREATED_AT));
  }

  @Test
  void staffAndGrantRowsPersistWithBackendOwnedColumnsUnderPostgres() {
    StaffUser staff = persistActiveStaff(StaffRole.SUPPORT_ENGINEER);
    SupportAccessGrant grant =
        saveGrant(staff.getId(), TENANT_A, StaffSupportScope.DIAGNOSTICS, "SUP-1001", FAR_FUTURE);

    assertThat(staffUserRepository.findById(staff.getId())).get().extracting(StaffUser::isActive).isEqualTo(true);

    // Read the persisted columns straight from Postgres so we assert the physical UUID/VARCHAR/TIMESTAMPTZ
    // mapping and the enum-as-string persistence, not the JPA first-level cache.
    var row =
        jdbcTemplate.queryForMap(
            "SELECT tenant_id, staff_user_id, scope, status, approval_status, support_case_ref, expires_at"
                + " FROM support_access_grant WHERE id = ?",
            grant.getId());
    assertThat(row.get("tenant_id")).hasToString(TENANT_A.toString());
    assertThat(row.get("staff_user_id")).hasToString(staff.getId().toString());
    assertThat(row.get("scope")).isEqualTo("DIAGNOSTICS");
    assertThat(row.get("status")).isEqualTo("ACTIVE");
    assertThat(row.get("approval_status")).isEqualTo("AUTO_APPROVED"); // DIAGNOSTICS auto-approves
    assertThat(row.get("support_case_ref")).isEqualTo("SUP-1001");
    assertThat(row.get("expires_at")).isNotNull();
  }

  @Test
  void grantLookupsStayTenantAndScopeScopedUnderPostgres() {
    StaffUser staff = persistActiveStaff(StaffRole.SUPPORT_ENGINEER);
    SupportAccessGrant grantA =
        saveGrant(staff.getId(), TENANT_A, StaffSupportScope.DIAGNOSTICS, "SUP-A", FAR_FUTURE);

    // Correct staff+tenant+scope loads; wrong tenant and wrong scope return empty by construction.
    assertThat(
            grantRepository.findByStaffUserIdAndTenantIdAndScopeAndStatusOrderByExpiresAtDesc(
                staff.getId(), TENANT_A, StaffSupportScope.DIAGNOSTICS, SupportAccessGrant.Status.ACTIVE))
        .extracting(SupportAccessGrant::getId)
        .containsExactly(grantA.getId());
    assertThat(
            grantRepository.findByStaffUserIdAndTenantIdAndScopeAndStatusOrderByExpiresAtDesc(
                staff.getId(), TENANT_B, StaffSupportScope.DIAGNOSTICS, SupportAccessGrant.Status.ACTIVE))
        .isEmpty();
    assertThat(
            grantRepository.findByStaffUserIdAndTenantIdAndScopeAndStatusOrderByExpiresAtDesc(
                staff.getId(), TENANT_A, StaffSupportScope.MAINTENANCE, SupportAccessGrant.Status.ACTIVE))
        .isEmpty();

    // Tenant-scoped management + registry queries never surface the other tenant's grant.
    assertThat(grantRepository.findByIdAndTenantId(grantA.getId(), TENANT_A)).isPresent();
    assertThat(grantRepository.findByIdAndTenantId(grantA.getId(), TENANT_B)).isEmpty();
    assertThat(grantRepository.findByTenantIdOrderByCreatedAtDesc(TENANT_A))
        .extracting(SupportAccessGrant::getId)
        .containsExactly(grantA.getId());
    assertThat(grantRepository.findByTenantIdOrderByCreatedAtDesc(TENANT_B)).isEmpty();
  }

  @Test
  void expiryAndApprovalSemanticsArePersistedAndEnforcedUnderPostgres() {
    StaffUser staff = persistActiveStaff(StaffRole.SUPPORT_ENGINEER);

    // Expired ACTIVE grant persists but is not usable (deterministic expiry against the clock).
    SupportAccessGrant expired =
        saveGrant(
            staff.getId(),
            TENANT_A,
            StaffSupportScope.DIAGNOSTICS,
            "SUP-EXPIRED",
            Instant.parse("2026-01-02T00:00:00Z"));
    assertThat(grantRepository.findById(expired.getId()).orElseThrow().isUsable(Instant.now())).isFalse();

    // A sensitive MAINTENANCE grant is born PENDING_APPROVAL and is not usable until approved; the approval
    // columns persist and the reloaded row then becomes usable.
    SupportAccessGrant pending =
        saveGrant(staff.getId(), TENANT_A, StaffSupportScope.MAINTENANCE, "SUP-MAINT", FAR_FUTURE);
    assertThat(pending.getApprovalStatus()).isEqualTo(SupportAccessGrant.ApprovalStatus.PENDING_APPROVAL);
    assertThat(pending.isUsable(CREATED_AT)).isFalse();

    UUID approver = UUID.randomUUID();
    pending.approve(approver, "Approved for PG proof", CREATED_AT);
    grantRepository.save(pending);

    var row =
        jdbcTemplate.queryForMap(
            "SELECT approval_status, approved_by, approval_note FROM support_access_grant WHERE id = ?",
            pending.getId());
    assertThat(row.get("approval_status")).isEqualTo("APPROVED");
    assertThat(row.get("approved_by")).hasToString(approver.toString());
    assertThat(row.get("approval_note")).isEqualTo("Approved for PG proof");
    assertThat(grantRepository.findById(pending.getId()).orElseThrow().isUsable(Instant.now())).isTrue();
  }

  /**
   * PG-248-01 positive proof: after V66 drops the {@code audit_event.actor_id -> user_account(id)} FK, the
   * support plane can record audit events whose actor is a {@code staff_user} id — for BOTH the allow path
   * ({@code SUPPORT_ACCESS_GRANTED}) and the fail-closed deny path ({@code SUPPORT_ACCESS_DENIED}). Also proves
   * tenant-scoping, that no raw grant internal (the support case reference) leaks into audit metadata, and that
   * the denied path triggers no maintenance/data-repair side effect.
   */
  @Test
  void supportServiceAuditWritesPersistForStaffActorsUnderPostgres() {
    TenantContext.setTenantId(TENANT_A);
    StaffUser staff = persistActiveStaff(StaffRole.SUPPORT_ENGINEER);

    // Allow path: a usable DIAGNOSTICS grant authorizes, returns a backend-owned session, and the
    // SUPPORT_ACCESS_GRANTED audit persists with actor_id = the STAFF principal id (not a user_account).
    SupportAccessGrant grant =
        saveGrant(staff.getId(), TENANT_A, StaffSupportScope.DIAGNOSTICS, "SUP-CASE-REF-1234", FAR_FUTURE);
    SupportAccessService.SupportSession session =
        supportAccessService.authorize(staff.getId(), TENANT_A, StaffSupportScope.DIAGNOSTICS);
    assertThat(session.grantId()).isEqualTo(grant.getId());
    assertThat(session.staffUserId()).isEqualTo(staff.getId());

    Map<String, Object> granted = singleAudit(TENANT_A, "SUPPORT_ACCESS_GRANTED");
    assertThat(granted.get("actor_id")).hasToString(staff.getId().toString());
    assertThat(granted.get("entity_type")).isEqualTo("SUPPORT_ACCESS");
    // Safe metadata only: ids/scope/decision — never the raw support case reference (a grant internal).
    assertThat(granted.get("metadata").toString()).doesNotContain("SUP-CASE-REF-1234");

    // Deny path: a permitted staff principal with NO grant now gets a proper DOMAIN denial (not a DB failure),
    // and the SUPPORT_ACCESS_DENIED audit persists with the staff actor id.
    StaffUser other = persistActiveStaff(StaffRole.SUPPORT_ENGINEER);
    assertThatThrownBy(
            () -> supportAccessService.authorize(other.getId(), TENANT_A, StaffSupportScope.DIAGNOSTICS))
        .isInstanceOf(SupportAccessDeniedException.class);

    Map<String, Object> denied = singleAudit(TENANT_A, "SUPPORT_ACCESS_DENIED");
    assertThat(denied.get("actor_id")).hasToString(other.getId().toString());

    // Both support-access audits are tenant-scoped to TENANT_A; TENANT_B saw none.
    assertThat(countSupportAccessAudits(TENANT_A)).isEqualTo(2L);
    assertThat(countSupportAccessAudits(TENANT_B)).isZero();

    // Denied support access mutated no maintenance/data-repair state for the tenant.
    assertThat(countForTenant("maintenance_action_record", TENANT_A)).isZero();
    assertThat(countForTenant("data_repair_request", TENANT_A)).isZero();
  }

  /**
   * Schema proof for V66: the {@code audit_event_actor_id_fkey} FK is gone, so {@code actor_id} accepts both a
   * staff-domain principal id (absent from {@code user_account}) and a real tenant {@code user_account} id —
   * i.e. the fix removes the false constraint without breaking existing tenant-user audit.
   */
  @Test
  void auditActorIdFkToUserAccountIsDroppedAndAcceptsStaffAndTenantActors() {
    Long fk =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM pg_constraint WHERE conrelid = 'audit_event'::regclass"
                + " AND conname = 'audit_event_actor_id_fkey'",
            Long.class);
    assertThat(fk).isZero();

    // A staff-domain actor id (not a user_account row) now inserts cleanly.
    UUID staffActor = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO audit_event (tenant_id, actor_id, action, entity_type, entity_id, metadata, occurred_at)"
            + " VALUES (?, ?, 'SUPPORT_ACCESS_GRANTED', 'SUPPORT_ACCESS', 'grant-x', '{\"safe\":true}', now())",
        TENANT_A,
        staffActor);

    // Existing tenant-user actor (a real user_account) still inserts — the fix did not break tenant-user audit.
    jdbcTemplate.update(
        "INSERT INTO audit_event (tenant_id, actor_id, action, entity_type, entity_id, metadata, occurred_at)"
            + " VALUES (?, ?, 'QUOTE_REVIEWED', 'QUOTE', 'quote-x', '{\"safe\":true}', now())",
        TENANT_A,
        USER_A);

    assertThat(countForTenant("audit_event", TENANT_A)).isEqualTo(2L);
  }

  private Map<String, Object> singleAudit(UUID tenantId, String action) {
    List<Map<String, Object>> rows =
        jdbcTemplate.queryForList(
            "SELECT actor_id, entity_type, metadata FROM audit_event WHERE tenant_id = ? AND action = ?",
            tenantId,
            action);
    assertThat(rows).hasSize(1);
    return rows.get(0);
  }

  private long countSupportAccessAudits(UUID tenantId) {
    Long count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM audit_event WHERE tenant_id = ? AND entity_type = 'SUPPORT_ACCESS'",
            Long.class,
            tenantId);
    return count == null ? 0L : count;
  }

  private long countForTenant(String table, UUID tenantId) {
    Long count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM " + table + " WHERE tenant_id = ?", Long.class, tenantId);
    return count == null ? 0L : count;
  }
}
