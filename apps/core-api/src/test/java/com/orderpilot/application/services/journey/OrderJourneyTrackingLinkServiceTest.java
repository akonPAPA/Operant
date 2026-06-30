package com.orderpilot.application.services.journey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.api.dto.OrderJourneyDtos.CreateTrackingLinkRequest;
import com.orderpilot.api.dto.OrderJourneyDtos.PublicOrderTrackingView;
import com.orderpilot.api.dto.OrderJourneyDtos.RecordFulfillmentSignalRequest;
import com.orderpilot.api.dto.OrderJourneyDtos.RevokeTrackingLinkRequest;
import com.orderpilot.api.dto.OrderJourneyDtos.TrackingLinkCreatedDto;
import com.orderpilot.api.dto.OrderJourneyDtos.TrackingLinkListDto;
import com.orderpilot.api.dto.OrderJourneyDtos.TrackingLinkRevokedDto;
import com.orderpilot.api.dto.OrderJourneyDtos.TrackingLinkSummaryDto;
import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.audit.AuditEventRepository;
import com.orderpilot.domain.journey.FulfillmentSignalRepository;
import com.orderpilot.domain.journey.JourneySourceType;
import com.orderpilot.domain.journey.OrderJourney;
import com.orderpilot.domain.journey.OrderJourneyMilestoneRepository;
import com.orderpilot.domain.journey.OrderJourneyRepository;
import com.orderpilot.domain.journey.OrderJourneyTrackingLink;
import com.orderpilot.domain.journey.OrderJourneyTrackingLinkRepository;
import com.orderpilot.domain.workspace.DraftQuote;
import com.orderpilot.domain.workspace.DraftQuoteRepository;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * OP-CAP-46C — secure tracking link foundation: tokenized, tenant/journey-scoped, expiring, read-only
 * resolution to the customer-safe tracking view, with denial, scope-isolation, mutation-safety, and
 * audit proofs.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({OrderJourneyService.class, OrderJourneyReadService.class, OrderJourneyTrackingLinkService.class,
    OrderJourneyProjectionPublisher.class, AuditEventService.class, CoreConfiguration.class})
class OrderJourneyTrackingLinkServiceTest {
  @Autowired private OrderJourneyService journeyService;
  @Autowired private OrderJourneyReadService readService;
  @Autowired private OrderJourneyTrackingLinkService trackingLinkService;
  @Autowired private DraftQuoteRepository draftQuoteRepository;
  @Autowired private OrderJourneyRepository journeyRepository;
  @Autowired private OrderJourneyMilestoneRepository milestoneRepository;
  @Autowired private FulfillmentSignalRepository signalRepository;
  @Autowired private OrderJourneyTrackingLinkRepository trackingLinkRepository;
  @Autowired private AuditEventRepository auditEventRepository;

  private static final Instant NOW = Instant.parse("2026-06-14T00:00:00Z");

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  private OrderJourney newApprovedQuoteJourney(UUID tenantId, String quoteNumber) {
    UUID quoteId = draftQuoteRepository.save(new DraftQuote(tenantId, quoteNumber, UUID.randomUUID(), UUID.randomUUID(),
        UUID.randomUUID(), UUID.randomUUID(), "APPROVED", "USD", null, NOW)).getId();
    return journeyService.refreshFromSource(JourneySourceType.DRAFT_QUOTE, quoteId);
  }

  private static String token(TrackingLinkCreatedDto created) {
    return created.trackingPath().substring(OrderJourneyTrackingLinkService.PUBLIC_PATH_PREFIX.length());
  }

  private static String sha256(String value) throws Exception {
    return HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void validTokenReturnsCustomerSafeTrackingView() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    OrderJourney journey = newApprovedQuoteJourney(tenantId, "Q-T1");
    TrackingLinkCreatedDto created = trackingLinkService.create(journey.getId(), new CreateTrackingLinkRequest(48), null);

    // The raw token is returned exactly once embedded in the path; only its hash is persisted.
    assertThat(created.trackingPath()).startsWith(OrderJourneyTrackingLinkService.PUBLIC_PATH_PREFIX);
    assertThat(trackingLinkRepository.findAll()).singleElement()
        .satisfies(link -> assertThat(link.getTokenHash()).isNotEqualTo(token(created)));

    TenantContext.clear(); // public resolve carries no tenant header — scope must come from the token
    PublicOrderTrackingView view = trackingLinkService.resolvePublicTracking(token(created));

    assertThat(view.statusLabel()).isNotBlank();
    assertThat(view.milestones()).isNotEmpty();
    // Only customer-visible milestones surface; the customer-safe status matches the journey projection.
    assertThat(view.statusLabel()).isEqualTo(journey.getCustomerVisibleStatus());
  }

  @Test
  void expiredTokenIsDenied() throws Exception {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    OrderJourney journey = newApprovedQuoteJourney(tenantId, "Q-T2");
    String rawToken = "expired-raw-token-value";
    trackingLinkRepository.save(new OrderJourneyTrackingLink(tenantId, journey.getId(), sha256(rawToken),
        NOW.minusSeconds(60), null, NOW.minusSeconds(3600)));

    TenantContext.clear();
    assertThatThrownBy(() -> trackingLinkService.resolvePublicTracking(rawToken))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void invalidOrBlankTokenIsDenied() {
    assertThatThrownBy(() -> trackingLinkService.resolvePublicTracking("not-a-real-token"))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> trackingLinkService.resolvePublicTracking("  "))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> trackingLinkService.resolvePublicTracking(null))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void tokenScopedToJourneyACannotAccessJourneyB() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    OrderJourney journeyA = newApprovedQuoteJourney(tenantId, "Q-A");
    OrderJourney journeyB = newApprovedQuoteJourney(tenantId, "Q-B");
    // Advance journey B with a verified DELIVERED so its customer-visible status differs from A.
    journeyService.recordSignal(journeyB.getId(), new RecordFulfillmentSignalRequest(
        "INTERNAL", "DELIVERED", "DELIVERED", null, "wh-deliver-b", null, true), UUID.randomUUID());
    OrderJourney refreshedB = journeyRepository.findByIdAndTenantId(journeyB.getId(), tenantId).orElseThrow();
    assertThat(refreshedB.getCustomerVisibleStatus()).isNotEqualTo(journeyA.getCustomerVisibleStatus());

    TrackingLinkCreatedDto linkA = trackingLinkService.create(journeyA.getId(), new CreateTrackingLinkRequest(48), null);

    TenantContext.clear();
    PublicOrderTrackingView view = trackingLinkService.resolvePublicTracking(token(linkA));
    // Token A resolves ONLY to journey A's data — never B's, even though B exists in the same tenant.
    assertThat(view.statusLabel()).isEqualTo(journeyA.getCustomerVisibleStatus());
    assertThat(view.statusLabel()).isNotEqualTo(refreshedB.getCustomerVisibleStatus());
  }

  @Test
  void tokenScopedToTenantACannotAccessTenantB() throws Exception {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    TenantContext.setTenantId(tenantA);
    OrderJourney journeyA = newApprovedQuoteJourney(tenantA, "Q-TA");

    String rawToken = "tenant-a-raw-token";
    // Far-future expiry (the service resolves against the real system clock, not the NOW fixture).
    OrderJourneyTrackingLink savedA = trackingLinkRepository.save(new OrderJourneyTrackingLink(
        tenantA, journeyA.getId(), sha256(rawToken), Instant.parse("2030-01-01T00:00:00Z"), null, NOW));
    assertThat(savedA.getTenantId()).isEqualTo(tenantA);

    // The token is bound to tenant A; resolving yields tenant A's journey, never tenant B's.
    TenantContext.clear();
    PublicOrderTrackingView view = trackingLinkService.resolvePublicTracking(rawToken);
    assertThat(view.statusLabel()).isEqualTo(journeyA.getCustomerVisibleStatus());

    // Defence in depth: even a swapped (tenantB, journeyA) scope is denied by the tenant-scoped read.
    assertThatThrownBy(() -> readService.publicTracking(tenantB, journeyA.getId()))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void resolvePublicTrackingIgnoresPreSetTenantContext() throws Exception {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    TenantContext.setTenantId(tenantA);
    OrderJourney journeyA = newApprovedQuoteJourney(tenantA, "Q-CTX");
    String rawToken = "ctx-tenant-token";
    trackingLinkRepository.save(new OrderJourneyTrackingLink(
        tenantA, journeyA.getId(), sha256(rawToken), Instant.parse("2030-01-01T00:00:00Z"), null, NOW));

    TenantContext.setTenantId(tenantB);
    PublicOrderTrackingView view = trackingLinkService.resolvePublicTracking(rawToken);
    assertThat(view.statusLabel()).isEqualTo(journeyA.getCustomerVisibleStatus());
  }

  @Test
  void publicTrackingViewDoesNotSerializeInternalFields() throws Exception {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    OrderJourney journey = newApprovedQuoteJourney(tenantId, "Q-RED");
    journeyService.recordSignal(journey.getId(), new RecordFulfillmentSignalRequest(
        "INTERNAL", "PACKED", "OK", null, "wh-red-1", "raw-payload-secret-ref", true), UUID.randomUUID());
    TrackingLinkCreatedDto created = trackingLinkService.create(journey.getId(), new CreateTrackingLinkRequest(48), null);

    TenantContext.clear();
    PublicOrderTrackingView view = trackingLinkService.resolvePublicTracking(token(created));
    String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(view);

    assertThat(json)
        .doesNotContain("sourceRef")
        .doesNotContain("sourceType")
        .doesNotContain("actorType")
        .doesNotContain("sortOrder")
        .doesNotContain("customerVisible")
        .doesNotContain("fulfillmentSignals")
        .doesNotContain("riskLevel")
        .doesNotContain("internalStatus")
        .doesNotContain("rawPayloadRef")
        .doesNotContain("raw-payload-secret-ref")
        .doesNotContain("connector")
        .doesNotContain("tenantId")
        .doesNotContain("journeyId")
        .doesNotContain("\"id\"");
    // It DOES carry the customer-safe surface.
    assertThat(json).contains("statusLabel").contains("milestones").contains("milestoneLabel");
  }

  @Test
  void secureLinkAccessDoesNotMutateJourneyMilestoneOrSignal() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    OrderJourney journey = newApprovedQuoteJourney(tenantId, "Q-MUT");
    journeyService.recordSignal(journey.getId(), new RecordFulfillmentSignalRequest(
        "INTERNAL", "PACKED", "OK", null, "wh-mut-1", null, true), UUID.randomUUID());
    TrackingLinkCreatedDto created = trackingLinkService.create(journey.getId(), new CreateTrackingLinkRequest(48), null);

    long journeysBefore = journeyRepository.count();
    long milestonesBefore = milestoneRepository.count();
    long signalsBefore = signalRepository.count();
    Instant updatedAtBefore = journeyRepository.findByIdAndTenantId(journey.getId(), tenantId).orElseThrow().getUpdatedAt();

    TenantContext.clear();
    trackingLinkService.resolvePublicTracking(token(created));
    trackingLinkService.resolvePublicTracking(token(created)); // repeat access stays read-only

    assertThat(journeyRepository.count()).isEqualTo(journeysBefore);
    assertThat(milestoneRepository.count()).isEqualTo(milestonesBefore);
    assertThat(signalRepository.count()).isEqualTo(signalsBefore);
    assertThat(journeyRepository.findByIdAndTenantId(journey.getId(), tenantId).orElseThrow().getUpdatedAt())
        .isEqualTo(updatedAtBefore);
  }

  @Test
  void carrierDeliveredSafetyRemainsUnchangedThroughTrackingLink() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    OrderJourney journey = newApprovedQuoteJourney(tenantId, "Q-CD");
    // Unverified carrier/WMS-mirrored DELIVERED must NOT present as delivered to the customer.
    journeyService.recordSignal(journey.getId(), new RecordFulfillmentSignalRequest(
        "CONNECTOR_MIRROR", "DELIVERED", "DELIVERED", null, "carrier-cd", null, true), UUID.randomUUID());
    TrackingLinkCreatedDto created = trackingLinkService.create(journey.getId(), new CreateTrackingLinkRequest(48), null);

    TenantContext.clear();
    PublicOrderTrackingView view = trackingLinkService.resolvePublicTracking(token(created));
    assertThat(view.statusLabel()).isNotEqualTo("Delivered");
    assertThat(view.milestones())
        .filteredOn(m -> "DELIVERED".equals(m.milestoneState()))
        .allSatisfy(m -> assertThat(m.evidenceLevel()).isNotIn("VERIFIED", "MANUAL"));
  }

  @Test
  void createAndAccessAreAudited() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    OrderJourney journey = newApprovedQuoteJourney(tenantId, "Q-AUD");
    UUID actorId = UUID.randomUUID();
    TrackingLinkCreatedDto created = trackingLinkService.create(journey.getId(), new CreateTrackingLinkRequest(48), actorId);

    TenantContext.clear();
    trackingLinkService.resolvePublicTracking(token(created));

    var audits = auditEventRepository.findByTenantIdOrderByOccurredAtDesc(tenantId);
    assertThat(audits).anyMatch(e -> "ORDER_JOURNEY_TRACKING_LINK_CREATED".equals(e.getAction())
        && actorId.equals(e.getActorId()));
    assertThat(audits).anyMatch(e -> "ORDER_JOURNEY_TRACKING_LINK_ACCESSED".equals(e.getAction())
        && e.getActorId() == null);
  }

  // ---- OP-CAP-46G — tracking link revocation foundation -------------------------------------------

  /** The single tracking link's internal id (revocation is keyed on it, never on the raw token). */
  private UUID onlyLinkId() {
    return trackingLinkRepository.findAll().get(0).getId();
  }

  @Test
  void validTokenResolvesThenRevokedTokenIsDeniedWithSameGenericNotFound() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    OrderJourney journey = newApprovedQuoteJourney(tenantId, "Q-REV1");
    TrackingLinkCreatedDto created = trackingLinkService.create(journey.getId(), new CreateTrackingLinkRequest(48), null);
    UUID linkId = onlyLinkId();

    // Valid before revocation.
    PublicOrderTrackingView before = trackingLinkService.resolvePublicTracking(token(created));
    assertThat(before.statusLabel()).isNotBlank();

    TenantContext.setTenantId(tenantId);
    TrackingLinkRevokedDto revoked = trackingLinkService.revoke(journey.getId(), linkId,
        new RevokeTrackingLinkRequest("customer asked to stop sharing"), UUID.randomUUID());
    assertThat(revoked.status()).isEqualTo("REVOKED");
    assertThat(revoked.revokedAt()).isNotNull();

    // Denied after revocation — identical generic message to the invalid/expired path (no oracle).
    TenantContext.clear();
    assertThatThrownBy(() -> trackingLinkService.resolvePublicTracking(token(created)))
        .isInstanceOf(NotFoundException.class)
        .hasMessage("Tracking link not found or no longer available");
  }

  @Test
  void crossTenantRevokeIsDeniedAndLeavesLinkActive() {
    UUID tenantA = UUID.randomUUID();
    TenantContext.setTenantId(tenantA);
    OrderJourney journeyA = newApprovedQuoteJourney(tenantA, "Q-XT");
    trackingLinkService.create(journeyA.getId(), new CreateTrackingLinkRequest(48), null);
    UUID linkId = onlyLinkId();

    UUID tenantB = UUID.randomUUID();
    TenantContext.setTenantId(tenantB);
    assertThatThrownBy(() -> trackingLinkService.revoke(journeyA.getId(), linkId, null, UUID.randomUUID()))
        .isInstanceOf(NotFoundException.class);

    assertThat(trackingLinkRepository.findById(linkId).orElseThrow().isRevoked()).isFalse();
  }

  @Test
  void crossJourneyRevokeIsDeniedAndLeavesLinkActive() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    OrderJourney journeyA = newApprovedQuoteJourney(tenantId, "Q-CJ-A");
    OrderJourney journeyB = newApprovedQuoteJourney(tenantId, "Q-CJ-B");
    trackingLinkService.create(journeyA.getId(), new CreateTrackingLinkRequest(48), null);
    UUID linkId = onlyLinkId();

    // Right tenant, wrong journey — the journey-scoped lookup still denies it.
    assertThatThrownBy(() -> trackingLinkService.revoke(journeyB.getId(), linkId, null, UUID.randomUUID()))
        .isInstanceOf(NotFoundException.class);

    assertThat(trackingLinkRepository.findById(linkId).orElseThrow().isRevoked()).isFalse();
  }

  @Test
  void revocationDoesNotMutateJourneyMilestoneOrSignal() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    OrderJourney journey = newApprovedQuoteJourney(tenantId, "Q-REVMUT");
    journeyService.recordSignal(journey.getId(), new RecordFulfillmentSignalRequest(
        "INTERNAL", "PACKED", "OK", null, "wh-revmut-1", null, true), UUID.randomUUID());
    trackingLinkService.create(journey.getId(), new CreateTrackingLinkRequest(48), null);
    UUID linkId = onlyLinkId();

    long journeysBefore = journeyRepository.count();
    long milestonesBefore = milestoneRepository.count();
    long signalsBefore = signalRepository.count();
    Instant updatedAtBefore = journeyRepository.findByIdAndTenantId(journey.getId(), tenantId).orElseThrow().getUpdatedAt();

    trackingLinkService.revoke(journey.getId(), linkId, new RevokeTrackingLinkRequest("stop"), UUID.randomUUID());

    assertThat(journeyRepository.count()).isEqualTo(journeysBefore);
    assertThat(milestoneRepository.count()).isEqualTo(milestonesBefore);
    assertThat(signalRepository.count()).isEqualTo(signalsBefore);
    assertThat(journeyRepository.findByIdAndTenantId(journey.getId(), tenantId).orElseThrow().getUpdatedAt())
        .isEqualTo(updatedAtBefore);
  }

  @Test
  void revocationIsAuditedWithIdsButNeverTokenOrHashOrReasonText() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    OrderJourney journey = newApprovedQuoteJourney(tenantId, "Q-REVAUD");
    trackingLinkService.create(journey.getId(), new CreateTrackingLinkRequest(48), null);
    OrderJourneyTrackingLink link = trackingLinkRepository.findAll().get(0);
    UUID linkId = link.getId();
    String tokenHash = link.getTokenHash();
    UUID actorId = UUID.randomUUID();

    trackingLinkService.revoke(journey.getId(), linkId, new RevokeTrackingLinkRequest("duplicate link sent"), actorId);

    var audits = auditEventRepository.findByTenantIdOrderByOccurredAtDesc(tenantId);
    var revoke = audits.stream()
        .filter(e -> "ORDER_JOURNEY_TRACKING_LINK_REVOKED".equals(e.getAction()))
        .findFirst().orElseThrow();
    assertThat(revoke.getActorId()).isEqualTo(actorId);
    assertThat(revoke.getEntityType()).isEqualTo("ORDER_JOURNEY");
    assertThat(revoke.getEntityId()).isEqualTo(journey.getId().toString());
    // Safe internal id present; raw token/hash and the operator-only reason text are NOT.
    assertThat(revoke.getMetadata())
        .contains(linkId.toString())
        .doesNotContain(tokenHash)
        .doesNotContain("tokenHash")
        .doesNotContain("duplicate link sent");
  }

  @Test
  void revocationReasonIsBoundedAndSanitizedAndBlankBecomesNull() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    OrderJourney journey = newApprovedQuoteJourney(tenantId, "Q-REVRSN");
    trackingLinkService.create(journey.getId(), new CreateTrackingLinkRequest(48), null);
    UUID linkId = onlyLinkId();

    String raw = "line-one\nline-two\t" + "x".repeat(400);
    trackingLinkService.revoke(journey.getId(), linkId, new RevokeTrackingLinkRequest(raw), UUID.randomUUID());

    OrderJourneyTrackingLink revoked = trackingLinkRepository.findById(linkId).orElseThrow();
    assertThat(revoked.getRevocationReason())
        .hasSizeLessThanOrEqualTo(OrderJourneyTrackingLink.MAX_REVOCATION_REASON_LENGTH)
        .doesNotContain("\n")
        .doesNotContain("\t");

    // A blank/whitespace-only reason is stored as null (first-write only applies once, so use a fresh link).
    OrderJourney journey2 = newApprovedQuoteJourney(tenantId, "Q-REVRSN2");
    trackingLinkService.create(journey2.getId(), new CreateTrackingLinkRequest(48), null);
    UUID link2 = trackingLinkRepository.findAll().stream()
        .filter(l -> l.getJourneyId().equals(journey2.getId())).findFirst().orElseThrow().getId();
    trackingLinkService.revoke(journey2.getId(), link2, new RevokeTrackingLinkRequest("   "), UUID.randomUUID());
    assertThat(trackingLinkRepository.findById(link2).orElseThrow().getRevocationReason()).isNull();
  }

  @Test
  void doubleRevokeIsIdempotentNoOpWithFirstWriteWins() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    OrderJourney journey = newApprovedQuoteJourney(tenantId, "Q-REVIDEM");
    trackingLinkService.create(journey.getId(), new CreateTrackingLinkRequest(48), null);
    UUID linkId = onlyLinkId();
    UUID firstActor = UUID.randomUUID();

    TrackingLinkRevokedDto first = trackingLinkService.revoke(journey.getId(), linkId,
        new RevokeTrackingLinkRequest("first"), firstActor);
    TrackingLinkRevokedDto second = trackingLinkService.revoke(journey.getId(), linkId,
        new RevokeTrackingLinkRequest("second"), UUID.randomUUID());

    assertThat(second.status()).isEqualTo("REVOKED");
    assertThat(second.revokedAt()).isEqualTo(first.revokedAt());

    OrderJourneyTrackingLink link = trackingLinkRepository.findById(linkId).orElseThrow();
    assertThat(link.getRevokedBy()).isEqualTo(firstActor);
    assertThat(link.getRevocationReason()).isEqualTo("first");

    // Idempotent: exactly one revoke audit event, never a duplicate.
    var audits = auditEventRepository.findByTenantIdOrderByOccurredAtDesc(tenantId);
    assertThat(audits.stream().filter(e -> "ORDER_JOURNEY_TRACKING_LINK_REVOKED".equals(e.getAction())).count())
        .isEqualTo(1);
  }

  @Test
  void expiredLinkCanStillBeRevokedByIdAndRemainsDenied() throws Exception {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    OrderJourney journey = newApprovedQuoteJourney(tenantId, "Q-REVEXP");
    String rawToken = "expired-revocable-token";
    OrderJourneyTrackingLink saved = trackingLinkRepository.save(new OrderJourneyTrackingLink(
        tenantId, journey.getId(), sha256(rawToken), NOW.minusSeconds(60), null, NOW.minusSeconds(3600)));

    // Expired → already denied publicly.
    TenantContext.clear();
    assertThatThrownBy(() -> trackingLinkService.resolvePublicTracking(rawToken)).isInstanceOf(NotFoundException.class);

    // Operator can still revoke it by id.
    TenantContext.setTenantId(tenantId);
    TrackingLinkRevokedDto revoked = trackingLinkService.revoke(journey.getId(), saved.getId(), null, UUID.randomUUID());
    assertThat(revoked.status()).isEqualTo("REVOKED");
    assertThat(trackingLinkRepository.findById(saved.getId()).orElseThrow().isRevoked()).isTrue();

    // Still denied after revocation either way.
    TenantContext.clear();
    assertThatThrownBy(() -> trackingLinkService.resolvePublicTracking(rawToken)).isInstanceOf(NotFoundException.class);
  }

  // ---- OP-CAP-46H — operator tracking link registry (list) ----------------------------------------

  @Test
  void listReturnsActiveExpiredAndRevokedStatusesNewestFirst() throws Exception {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    OrderJourney journey = newApprovedQuoteJourney(tenantId, "Q-LIST");

    // Oldest: an already-expired link saved directly (far-past expiry, oldest createdAt).
    OrderJourneyTrackingLink expired = trackingLinkRepository.save(new OrderJourneyTrackingLink(
        tenantId, journey.getId(), sha256("list-expired"), NOW.minusSeconds(60), null, NOW.minusSeconds(7200)));
    // Middle: a link we then revoke.
    OrderJourneyTrackingLink toRevoke = trackingLinkRepository.save(new OrderJourneyTrackingLink(
        tenantId, journey.getId(), sha256("list-revoked"), Instant.parse("2030-01-01T00:00:00Z"), null,
        NOW.minusSeconds(3600)));
    // Newest: an active link.
    OrderJourneyTrackingLink active = trackingLinkRepository.save(new OrderJourneyTrackingLink(
        tenantId, journey.getId(), sha256("list-active"), Instant.parse("2030-01-01T00:00:00Z"), null,
        NOW.minusSeconds(1)));
    trackingLinkService.revoke(journey.getId(), toRevoke.getId(), new RevokeTrackingLinkRequest("dup"), UUID.randomUUID());

    TrackingLinkListDto list = trackingLinkService.list(journey.getId());

    // Newest-first ordering: active, revoked, expired.
    assertThat(list.links()).extracting(TrackingLinkSummaryDto::linkId)
        .containsExactly(active.getId(), toRevoke.getId(), expired.getId());
    assertThat(list.links()).extracting(TrackingLinkSummaryDto::status)
        .containsExactly("ACTIVE", "REVOKED", "EXPIRED");
    // Revoked row carries revokedAt; active/expired do not.
    assertThat(list.links().get(0).revokedAt()).isNull();
    assertThat(list.links().get(1).revokedAt()).isNotNull();
    assertThat(list.links().get(2).revokedAt()).isNull();
    // Every row carries the safe lifecycle metadata.
    assertThat(list.links()).allSatisfy(row -> {
      assertThat(row.linkId()).isNotNull();
      assertThat(row.createdAt()).isNotNull();
      assertThat(row.expiresAt()).isNotNull();
    });
  }

  @Test
  void listIsTenantScoped() {
    UUID tenantA = UUID.randomUUID();
    TenantContext.setTenantId(tenantA);
    OrderJourney journeyA = newApprovedQuoteJourney(tenantA, "Q-LT-A");
    trackingLinkService.create(journeyA.getId(), new CreateTrackingLinkRequest(48), null);

    // Tenant B cannot list tenant A's journey at all — generic not-found, no enumeration oracle.
    UUID tenantB = UUID.randomUUID();
    TenantContext.setTenantId(tenantB);
    assertThatThrownBy(() -> trackingLinkService.list(journeyA.getId()))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void listIsJourneyScoped() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    OrderJourney journeyA = newApprovedQuoteJourney(tenantId, "Q-LJ-A");
    OrderJourney journeyB = newApprovedQuoteJourney(tenantId, "Q-LJ-B");
    trackingLinkService.create(journeyA.getId(), new CreateTrackingLinkRequest(48), null);

    // Journey B (same tenant) has its own — empty — list; it never sees journey A's link.
    TrackingLinkListDto listB = trackingLinkService.list(journeyB.getId());
    assertThat(listB.links()).isEmpty();

    TrackingLinkListDto listA = trackingLinkService.list(journeyA.getId());
    assertThat(listA.links()).singleElement()
        .satisfies(row -> assertThat(row.status()).isEqualTo("ACTIVE"));
  }

  @Test
  void listSummaryDoesNotExposeTokenHashTrackingPathOrTenantId() throws Exception {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    OrderJourney journey = newApprovedQuoteJourney(tenantId, "Q-LRED");
    trackingLinkService.create(journey.getId(), new CreateTrackingLinkRequest(48), null);
    String storedHash = trackingLinkRepository.findAll().get(0).getTokenHash();

    TrackingLinkListDto list = trackingLinkService.list(journey.getId());
    String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(list);

    assertThat(json)
        .doesNotContain(storedHash)
        .doesNotContain("tokenHash")
        .doesNotContain("token")
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

  @Test
  void revokeUsingListedLinkIdSucceedsAndIsReflectedOnRelist() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    OrderJourney journey = newApprovedQuoteJourney(tenantId, "Q-LREV");
    trackingLinkService.create(journey.getId(), new CreateTrackingLinkRequest(48), null);

    // The operator only ever holds the listed linkId — never the raw token.
    UUID listedLinkId = trackingLinkService.list(journey.getId()).links().get(0).linkId();
    assertThat(trackingLinkService.list(journey.getId()).links().get(0).status()).isEqualTo("ACTIVE");

    TrackingLinkRevokedDto revoked = trackingLinkService.revoke(journey.getId(), listedLinkId, null, UUID.randomUUID());
    assertThat(revoked.status()).isEqualTo("REVOKED");

    assertThat(trackingLinkService.list(journey.getId()).links().get(0).status()).isEqualTo("REVOKED");
  }

  @Test
  void listDoesNotMutateJourneyMilestoneOrSignal() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    OrderJourney journey = newApprovedQuoteJourney(tenantId, "Q-LMUT");
    journeyService.recordSignal(journey.getId(), new RecordFulfillmentSignalRequest(
        "INTERNAL", "PACKED", "OK", null, "wh-lmut-1", null, true), UUID.randomUUID());
    trackingLinkService.create(journey.getId(), new CreateTrackingLinkRequest(48), null);

    long journeysBefore = journeyRepository.count();
    long milestonesBefore = milestoneRepository.count();
    long signalsBefore = signalRepository.count();
    long linksBefore = trackingLinkRepository.count();
    Instant updatedAtBefore = journeyRepository.findByIdAndTenantId(journey.getId(), tenantId).orElseThrow().getUpdatedAt();

    trackingLinkService.list(journey.getId());
    trackingLinkService.list(journey.getId());

    assertThat(journeyRepository.count()).isEqualTo(journeysBefore);
    assertThat(milestoneRepository.count()).isEqualTo(milestonesBefore);
    assertThat(signalRepository.count()).isEqualTo(signalsBefore);
    assertThat(trackingLinkRepository.count()).isEqualTo(linksBefore);
    assertThat(journeyRepository.findByIdAndTenantId(journey.getId(), tenantId).orElseThrow().getUpdatedAt())
        .isEqualTo(updatedAtBefore);
  }
}
