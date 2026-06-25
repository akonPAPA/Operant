package com.orderpilot.integration.testdb;

import static com.orderpilot.support.DatabaseIntegrationTestBase.CLEAN;
import static com.orderpilot.support.DatabaseIntegrationTestBase.TENANTS;
import static com.orderpilot.support.DatabaseIntegrationTestBase.USERS_ROLES;
import static com.orderpilot.support.TestTenantFixtures.TENANT_A;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.api.dto.OrderJourneyDtos.CreateTrackingLinkRequest;
import com.orderpilot.api.dto.OrderJourneyDtos.PublicOrderTrackingView;
import com.orderpilot.api.dto.OrderJourneyDtos.RevokeTrackingLinkRequest;
import com.orderpilot.api.dto.OrderJourneyDtos.TrackingLinkCreatedDto;
import com.orderpilot.api.dto.OrderJourneyDtos.TrackingLinkListDto;
import com.orderpilot.api.dto.OrderJourneyDtos.TrackingLinkRevokedDto;
import com.orderpilot.application.services.journey.OrderJourneyService;
import com.orderpilot.application.services.journey.OrderJourneyTrackingLinkService;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.journey.JourneySourceType;
import com.orderpilot.domain.journey.OrderJourney;
import com.orderpilot.domain.journey.OrderJourneyTrackingLink;
import com.orderpilot.domain.journey.OrderJourneyTrackingLinkRepository;
import com.orderpilot.domain.workspace.DraftQuote;
import com.orderpilot.domain.workspace.DraftQuoteRepository;
import com.orderpilot.support.DatabaseIntegrationTestBase;
import com.orderpilot.support.RequiresPostgresIntegration;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

/**
 * OP-CAP-46I — real PostgreSQL proof for the secure tracking-link foundation (V60), its revocation
 * columns (V61), and the operator list / scoped-revoke / public-resolve query shapes.
 *
 * <p>The fast H2 suite runs with {@code spring.flyway.enabled=false} and {@code ddl-auto=create-drop},
 * so it never executes V60/V61 on PostgreSQL and proves none of the real schema: the
 * {@code TIMESTAMPTZ}/{@code UUID} column types, the {@code token_hash} uniqueness constraint, the
 * {@code (tenant_id, journey_id, created_at DESC)} list index, nor the foreign keys that real
 * PostgreSQL enforces (e.g. {@code audit_event.actor_id -> user_account(id)}, which H2 create-drop did
 * not). This test boots the full application context against a real PostgreSQL with Flyway applied (so
 * V60 and V61 actually run), and exercises the same services the operator and public endpoints use.
 *
 * <p>It follows the external-Postgres integration pattern ({@link DatabaseIntegrationTestBase} +
 * {@code @Sql} fixtures, like {@code QuoteReviewPostgresIntegrationTest} /
 * {@code AuditIdempotencyPostgresIntegrationTest}): it runs only under the explicit Postgres opt-in
 * system property against the local/CI {@code docker-compose.test.yml} database, never under the
 * default H2 unit suite.
 */
@Sql(scripts = {CLEAN, TENANTS, USERS_ROLES})
@RequiresPostgresIntegration
class OrderJourneyTrackingLinkPostgresIntegrationTest extends DatabaseIntegrationTestBase {

  /**
   * A real seeded operator for TENANT_A (from {@code users_roles.sql}). The revoke audit writes the
   * actor into {@code audit_event.actor_id}, which carries a foreign key to {@code user_account(id)} on
   * real PostgreSQL — so the actor MUST be a genuine user, not a random uuid (H2 create-drop never
   * enforced that FK; real PostgreSQL does).
   */
  private static final UUID OPERATOR_A = UUID.fromString("33333333-3333-4333-8333-333333333333");

  @Autowired private OrderJourneyService journeyService;
  @Autowired private OrderJourneyTrackingLinkService trackingLinkService;
  @Autowired private OrderJourneyTrackingLinkRepository trackingLinkRepository;
  @Autowired private DraftQuoteRepository draftQuoteRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  private static final Instant NOW = Instant.parse("2026-06-14T00:00:00Z");

  /**
   * Build an approved-quote-backed journey for the seeded TENANT_A. Unlike the H2 helper this passes
   * NULL for the optional source/customer ids — on real PostgreSQL {@code draft_quote.customer_account_id}
   * and the source ids carry foreign keys, so random uuids would be rejected; NULL is FK-safe.
   */
  private OrderJourney newApprovedQuoteJourney(String quoteNumber) {
    UUID quoteId = draftQuoteRepository.save(new DraftQuote(
        TENANT_A, quoteNumber, null, null, null, null, "APPROVED", "USD", null, NOW)).getId();
    return journeyService.refreshFromSource(JourneySourceType.DRAFT_QUOTE, quoteId);
  }

  private static String token(TrackingLinkCreatedDto created) {
    return created.trackingPath().substring(OrderJourneyTrackingLinkService.PUBLIC_PATH_PREFIX.length());
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  // ---- 1. Schema / migration proof ----------------------------------------------------------------

  @Test
  void migrationsApplyThroughV61AndColumnsExist() {
    // Flyway applied both the V60 foundation and the V61 revocation migration successfully.
    List<String> appliedThrough61 = jdbcTemplate.queryForList(
        "SELECT version FROM flyway_schema_history WHERE success = true AND version IN ('60','61') "
            + "ORDER BY version", String.class);
    assertThat(appliedThrough61).containsExactly("60", "61");

    // The table exists on real PostgreSQL (not just H2 create-drop).
    assertThat(jdbcTemplate.queryForObject(
        "SELECT to_regclass('public.order_journey_tracking_link')::text", String.class))
        .isEqualTo("order_journey_tracking_link");

    List<String> columns = jdbcTemplate.queryForList(
        "SELECT column_name FROM information_schema.columns WHERE table_name = 'order_journey_tracking_link'",
        String.class);
    // V60 base columns.
    assertThat(columns).contains(
        "id", "tenant_id", "journey_id", "token_hash", "expires_at", "created_at", "created_by");
    // V61 revocation columns.
    assertThat(columns).contains("revoked_at", "revoked_by", "revocation_reason");

    // The token-hash uniqueness constraint and the tenant+journey+createdAt list index are real.
    Integer tokenUnique = jdbcTemplate.queryForObject(
        "SELECT count(*) FROM pg_indexes WHERE tablename = 'order_journey_tracking_link' "
            + "AND indexname = 'ux_order_journey_tracking_link_token'", Integer.class);
    assertThat(tokenUnique).isEqualTo(1);
    Integer listIndex = jdbcTemplate.queryForObject(
        "SELECT count(*) FROM pg_indexes WHERE tablename = 'order_journey_tracking_link' "
            + "AND indexname = 'idx_order_journey_tracking_link_tenant_journey'", Integer.class);
    assertThat(listIndex).isEqualTo(1);
  }

  // ---- 2. Full lifecycle proof --------------------------------------------------------------------

  @Test
  void trackingLinkLifecycleWorksOnPostgres() {
    TenantContext.setTenantId(TENANT_A);
    OrderJourney journey = newApprovedQuoteJourney("Q-PG-LIFE");

    // Mint: only the token hash is persisted; the raw token is returned once embedded in the path.
    TrackingLinkCreatedDto created =
        trackingLinkService.create(journey.getId(), new CreateTrackingLinkRequest(48), null);
    assertThat(created.trackingPath()).startsWith(OrderJourneyTrackingLinkService.PUBLIC_PATH_PREFIX);
    assertThat(trackingLinkRepository.findAll()).singleElement()
        .satisfies(link -> assertThat(link.getTokenHash()).isNotEqualTo(token(created)));

    // Public resolve carries no tenant header — scope must come from the verified token row.
    TenantContext.clear();
    PublicOrderTrackingView view = trackingLinkService.resolvePublicTracking(token(created));
    assertThat(view.statusLabel()).isNotBlank();
    assertThat(view.milestones()).isNotEmpty();

    // Operator list: the tenant+journey+createdAt query returns the one ACTIVE link.
    TenantContext.setTenantId(TENANT_A);
    UUID listedLinkId = trackingLinkService.list(journey.getId()).links().get(0).linkId();
    assertThat(trackingLinkService.list(journey.getId()).links().get(0).status()).isEqualTo("ACTIVE");

    // Revoke by the LISTED link id (never the raw token) within tenant+journey scope.
    TrackingLinkRevokedDto revoked =
        trackingLinkService.revoke(journey.getId(), listedLinkId, new RevokeTrackingLinkRequest("dup"), OPERATOR_A);
    assertThat(revoked.status()).isEqualTo("REVOKED");
    assertThat(trackingLinkService.list(journey.getId()).links().get(0).status()).isEqualTo("REVOKED");

    // A revoked token is denied publicly with the same generic not-found (no validity oracle).
    TenantContext.clear();
    assertThatThrownBy(() -> trackingLinkService.resolvePublicTracking(token(created)))
        .isInstanceOf(NotFoundException.class)
        .hasMessage("Tracking link not found or no longer available");
  }

  // ---- 3. Operator list redaction proof -----------------------------------------------------------

  @Test
  void operatorListDoesNotExposeTokenHashOrTrackingPathOnPostgres() throws Exception {
    TenantContext.setTenantId(TENANT_A);
    OrderJourney journey = newApprovedQuoteJourney("Q-PG-RED");
    trackingLinkService.create(journey.getId(), new CreateTrackingLinkRequest(48), null);
    String storedHash = trackingLinkRepository.findAll().get(0).getTokenHash();

    TrackingLinkListDto list = trackingLinkService.list(journey.getId());
    String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(list);

    assertThat(json)
        .doesNotContain(storedHash)
        .doesNotContain("tokenHash")
        .doesNotContain("trackingPath")
        .doesNotContain(OrderJourneyTrackingLinkService.PUBLIC_PATH_PREFIX)
        .doesNotContain("/public/order-tracking/")
        .doesNotContain("tenantId")
        .doesNotContain("revocationReason")
        .doesNotContain("createdBy")
        .doesNotContain("revokedBy");
    // It DOES carry the safe operator surface.
    assertThat(json).contains("linkId").contains("createdAt").contains("expiresAt").contains("status");
  }

  // ---- 4. Expired token denial proof --------------------------------------------------------------

  @Test
  void expiredTokenDeniedOnPostgres() {
    TenantContext.setTenantId(TENANT_A);
    OrderJourney journey = newApprovedQuoteJourney("Q-PG-EXP");
    String rawToken = "pg-expired-raw-token-value";
    // expires_at strictly in the past — the TIMESTAMPTZ comparison must deny it on real PostgreSQL.
    trackingLinkRepository.save(new OrderJourneyTrackingLink(
        TENANT_A, journey.getId(), sha256(rawToken), NOW.minusSeconds(60), null, NOW.minusSeconds(3600)));

    TenantContext.clear();
    assertThatThrownBy(() -> trackingLinkService.resolvePublicTracking(rawToken))
        .isInstanceOf(NotFoundException.class)
        .hasMessage("Tracking link not found or no longer available");
  }

  // ---- 5. Revocation audit leakage proof ----------------------------------------------------------

  @Test
  void revokeAuditDoesNotPersistTokenHashOrReasonTextOnPostgres() {
    TenantContext.setTenantId(TENANT_A);
    OrderJourney journey = newApprovedQuoteJourney("Q-PG-AUD");
    trackingLinkService.create(journey.getId(), new CreateTrackingLinkRequest(48), null);
    OrderJourneyTrackingLink link = trackingLinkRepository.findAll().get(0);
    String tokenHash = link.getTokenHash();
    String reasonText = "operator-only-secret-reason-text";

    trackingLinkService.revoke(journey.getId(), link.getId(), new RevokeTrackingLinkRequest(reasonText), OPERATOR_A);

    // The reason IS persisted (operator-only) on the link row, but never leaks into the audit event.
    OrderJourneyTrackingLink revoked = trackingLinkRepository.findById(link.getId()).orElseThrow();
    assertThat(revoked.getRevocationReason()).isEqualTo(reasonText);

    // Read the persisted audit row straight from PostgreSQL: it carries the safe link id but never the
    // raw token hash nor the operator-only reason text.
    String metadata = jdbcTemplate.queryForObject(
        "SELECT metadata FROM audit_event WHERE tenant_id = ? AND action = 'ORDER_JOURNEY_TRACKING_LINK_REVOKED'",
        String.class, TENANT_A);
    assertThat(metadata)
        .contains(link.getId().toString())
        .doesNotContain(tokenHash)
        .doesNotContain("tokenHash")
        .doesNotContain(reasonText);
  }
}
