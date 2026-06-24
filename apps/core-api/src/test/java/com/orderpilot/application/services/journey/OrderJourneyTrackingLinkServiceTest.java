package com.orderpilot.application.services.journey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.api.dto.OrderJourneyDtos.CreateTrackingLinkRequest;
import com.orderpilot.api.dto.OrderJourneyDtos.PublicOrderTrackingView;
import com.orderpilot.api.dto.OrderJourneyDtos.RecordFulfillmentSignalRequest;
import com.orderpilot.api.dto.OrderJourneyDtos.TrackingLinkCreatedDto;
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
}
