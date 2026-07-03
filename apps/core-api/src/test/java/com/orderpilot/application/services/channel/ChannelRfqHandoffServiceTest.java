package com.orderpilot.application.services.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.api.dto.ChannelRfqHandoffDtos.ChannelRfqHandoffResponse;
import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.audit.AuditEvent;
import com.orderpilot.domain.audit.AuditEventRepository;
import com.orderpilot.domain.channel.ChannelProviderType;
import com.orderpilot.domain.channel.ChannelRfqHandoff;
import com.orderpilot.domain.channel.ChannelRfqHandoffRepository;
import com.orderpilot.domain.channel.ChannelRfqHandoffStatus;
import com.orderpilot.domain.channel.InboundChannelEvent;
import com.orderpilot.domain.channel.InboundChannelEventRepository;
import com.orderpilot.domain.tenant.Tenant;
import com.orderpilot.domain.tenant.TenantRepository;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
    ChannelRfqHandoffService.class,
    AuditEventService.class,
    ObjectMapper.class,
    CoreConfiguration.class
})
class ChannelRfqHandoffServiceTest {

  @Autowired private ChannelRfqHandoffService handoffService;
  @Autowired private ChannelRfqHandoffRepository handoffRepository;
  @Autowired private InboundChannelEventRepository eventRepository;
  @Autowired private AuditEventRepository auditEventRepository;
  @Autowired private TenantRepository tenantRepository;

  @AfterEach void clearTenant() { TenantContext.clear(); }

  @Test void createsReviewableRfqHandoffFromChannelEvent() {
    UUID tenantId = seedTenant();
    TenantContext.setTenantId(tenantId);
    InboundChannelEvent event = seedEvent(tenantId, "evt-1", "Please quote 10 of BRK-100");

    ChannelRfqHandoffResponse response = handoffService.createFromChannelEvent(commandFor(event));

    assertThat(response.id()).isNotNull();
    assertThat(response.status()).isEqualTo("PENDING_REVIEW");
    assertThat(response.sourceChannel()).isEqualTo("TELEGRAM");
    assertThat(response.requestText()).isEqualTo("Please quote 10 of BRK-100");
    assertThat(response.detectedIntent()).isEqualTo("RFQ_REQUEST");
    // Internal source/correlation linkage is persisted server-side but NOT exposed on the response.
    var saved = handoffRepository.findByTenantIdOrderByCreatedAtDesc(tenantId);
    assertThat(saved).hasSize(1);
    assertThat(saved.get(0).getInboundChannelEventId()).isEqualTo(event.getId());
    assertThat(saved.get(0).getSourceExternalEventId()).isEqualTo("evt-1");
  }

  @Test void duplicateSourceEventReturnsExistingHandoffWithoutInsertingDuplicate() {
    UUID tenantId = seedTenant();
    TenantContext.setTenantId(tenantId);
    InboundChannelEvent event = seedEvent(tenantId, "evt-dup", "quote 5 of FLT-22");

    ChannelRfqHandoffResponse first = handoffService.createFromChannelEvent(commandFor(event));
    ChannelRfqHandoffResponse second = handoffService.createFromChannelEvent(commandFor(event));

    assertThat(second.id()).isEqualTo(first.id());
    assertThat(handoffRepository.findByTenantIdOrderByCreatedAtDesc(tenantId)).hasSize(1);
  }

  @Test void uniqueConstraintBlocksDuplicateSourceEventAtPersistenceLayer() {
    UUID tenantId = seedTenant();
    TenantContext.setTenantId(tenantId);
    InboundChannelEvent event = seedEvent(tenantId, "evt-uniq", "quote please");
    Instant now = Instant.parse("2026-06-04T00:00:00Z");

    handoffRepository.saveAndFlush(new com.orderpilot.domain.channel.ChannelRfqHandoff(
        tenantId, event.getId(), UUID.randomUUID(), "TELEGRAM", "evt-uniq", null, null, null, "x", "RFQ_REQUEST", now));

    assertThatThrownBy(() -> handoffRepository.saveAndFlush(new com.orderpilot.domain.channel.ChannelRfqHandoff(
        tenantId, event.getId(), UUID.randomUUID(), "TELEGRAM", "evt-uniq", null, null, null, "y", "RFQ_REQUEST", now)))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test void tenantCannotReadAnotherTenantsHandoff() {
    UUID tenantA = seedTenant();
    TenantContext.setTenantId(tenantA);
    InboundChannelEvent eventA = seedEvent(tenantA, "evt-a", "quote for A");
    ChannelRfqHandoffResponse handoffA = handoffService.createFromChannelEvent(commandFor(eventA));

    UUID tenantB = seedTenant();
    TenantContext.setTenantId(tenantB);
    assertThatThrownBy(() -> handoffService.get(handoffA.id())).isInstanceOf(NotFoundException.class);
    assertThat(handoffService.list(null)).isEmpty();
  }

  @Test
  void listAppliesDefaultCustomPageAndMaximumBounds() {
    UUID tenantId = seedTenant();
    TenantContext.setTenantId(tenantId);
    seedDirectHandoffs(tenantId, 105, "bounded");

    List<ChannelRfqHandoffResponse> defaultPage = handoffService.list(null, null, null);
    List<ChannelRfqHandoffResponse> customFirstPage = handoffService.list(null, 0, 7);
    List<ChannelRfqHandoffResponse> customSecondPage = handoffService.list(null, 1, 7);
    List<ChannelRfqHandoffResponse> clampedPage = handoffService.list(null, 0, 1_000);

    assertThat(defaultPage).hasSize(ChannelRfqHandoffService.DEFAULT_PAGE_SIZE);
    assertThat(customFirstPage).hasSize(7);
    assertThat(customSecondPage).hasSize(7);
    assertThat(customFirstPage)
        .extracting(ChannelRfqHandoffResponse::id)
        .doesNotContainAnyElementsOf(
            customSecondPage.stream().map(ChannelRfqHandoffResponse::id).toList());
    assertThat(clampedPage).hasSize(ChannelRfqHandoffService.MAX_PAGE_SIZE);
  }

  @Test
  void listRejectsNegativePageAndNonPositiveSize() {
    UUID tenantId = seedTenant();
    TenantContext.setTenantId(tenantId);

    assertThatThrownBy(() -> handoffService.list(null, -1, 10))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("page");
    assertThatThrownBy(() -> handoffService.list(null, 0, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("size");
  }

  @Test
  void listPaginationIsTieStableWhenCreatedAtCollides() {
    UUID tenantId = seedTenant();
    TenantContext.setTenantId(tenantId);
    // All rows share the same createdAt so ordering must fall back to the id desc tiebreaker.
    seedHandoffsWithSharedCreatedAt(tenantId, 20, "tie", Instant.parse("2026-06-10T00:00:00Z"));

    List<ChannelRfqHandoffResponse> fullOrder = handoffService.list(null, 0, 20);
    List<ChannelRfqHandoffResponse> firstPage = handoffService.list(null, 0, 7);
    List<ChannelRfqHandoffResponse> secondPage = handoffService.list(null, 1, 7);

    assertThat(fullOrder).hasSize(20);
    // Pages are stable, non-overlapping slices of a single deterministic total order.
    assertThat(idsOf(firstPage)).isEqualTo(idsOf(fullOrder.subList(0, 7)));
    assertThat(idsOf(secondPage)).isEqualTo(idsOf(fullOrder.subList(7, 14)));
    assertThat(idsOf(firstPage)).doesNotContainAnyElementsOf(idsOf(secondPage));
    // Re-fetching the same page yields the identical order (no reordering across equal createdAt).
    assertThat(idsOf(handoffService.list(null, 0, 7))).isEqualTo(idsOf(firstPage));
    // createdAt remains the non-increasing primary key across the full ordered page.
    assertThat(fullOrder).extracting(ChannelRfqHandoffResponse::createdAt)
        .isSortedAccordingTo(java.util.Comparator.reverseOrder());
  }

  private static List<UUID> idsOf(List<ChannelRfqHandoffResponse> responses) {
    return responses.stream().map(ChannelRfqHandoffResponse::id).toList();
  }

  @Test void auditEventIsEmittedWhenHandoffIsCreated() {
    UUID tenantId = seedTenant();
    TenantContext.setTenantId(tenantId);
    InboundChannelEvent event = seedEvent(tenantId, "evt-audit", "rfq");

    handoffService.createFromChannelEvent(commandFor(event));

    List<AuditEvent> audits = auditEventRepository.findByTenantIdOrderByOccurredAtDesc(tenantId);
    assertThat(audits).anyMatch(a -> "CHANNEL_RFQ_HANDOFF_CREATED".equals(a.getAction()));
    String metadata = audits.stream()
        .filter(a -> "CHANNEL_RFQ_HANDOFF_CREATED".equals(a.getAction()))
        .map(AuditEvent::getMetadata).findFirst().orElse("");
    assertThat(metadata).contains("\"externalExecution\":\"DISABLED\"");
    assertThat(metadata).contains(event.getId().toString());
  }

  @Test void missingSourceEventReferenceIsRejected() {
    UUID tenantId = seedTenant();
    TenantContext.setTenantId(tenantId);

    assertThatThrownBy(() -> handoffService.createFromChannelEvent(new CreateChannelRfqHandoffCommand(
        null, UUID.randomUUID(), "TELEGRAM", "x", null, null, null, "rfq", "RFQ_REQUEST")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test void crossTenantSourceEventIsRejected() {
    UUID tenantA = seedTenant();
    TenantContext.setTenantId(tenantA);
    InboundChannelEvent eventA = seedEvent(tenantA, "evt-x", "rfq");

    UUID tenantB = seedTenant();
    TenantContext.setTenantId(tenantB);
    assertThatThrownBy(() -> handoffService.createFromChannelEvent(commandFor(eventA)))
        .isInstanceOf(NotFoundException.class);
    assertThat(handoffRepository.findByTenantIdOrderByCreatedAtDesc(tenantB)).isEmpty();
  }

  @Test void listFiltersByTenantAndStatus() {
    UUID tenantId = seedTenant();
    TenantContext.setTenantId(tenantId);
    InboundChannelEvent event = seedEvent(tenantId, "evt-list", "rfq");
    handoffService.createFromChannelEvent(commandFor(event));

    assertThat(handoffService.list(ChannelRfqHandoffStatus.PENDING_REVIEW, 0, 10)).hasSize(1);
    assertThat(handoffService.list(ChannelRfqHandoffStatus.CONVERTED, 0, 10)).isEmpty();
    assertThat(handoffRepository.findByTenantIdAndStatusOrderByCreatedAtDesc(tenantId, ChannelRfqHandoffStatus.PENDING_REVIEW)).hasSize(1);
  }

  // --- OP-CAP-06C operator workflow ---

  @Test void startReviewFromPendingSucceeds() {
    UUID tenantId = seedTenant();
    TenantContext.setTenantId(tenantId);
    UUID id = createHandoff(tenantId, "evt-sr", "rfq");
    UUID reviewer = UUID.randomUUID();

    ChannelRfqHandoffResponse response = handoffService.startReview(id, reviewer);

    assertThat(response.status()).isEqualTo("IN_REVIEW");
    assertThat(response.reviewStartedAt()).isNotNull();
    // Reviewer is recorded server-side for audit/attribution but is NOT exposed on the operator response.
    var saved = handoffRepository.findByIdAndTenantId(id, tenantId).orElseThrow();
    assertThat(saved.getReviewerUserId()).isEqualTo(reviewer);
  }

  @Test void startReviewFromDismissedFails() {
    UUID tenantId = seedTenant();
    TenantContext.setTenantId(tenantId);
    UUID id = createHandoff(tenantId, "evt-srd", "rfq");
    handoffService.dismiss(id, "not a real request", null);

    assertThatThrownBy(() -> handoffService.startReview(id, null)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test void startReviewFromConvertedFails() {
    UUID tenantId = seedTenant();
    TenantContext.setTenantId(tenantId);
    UUID id = createHandoff(tenantId, "evt-src", "rfq");
    handoffService.markConverted(id, "handled outside Operant", null);

    assertThatThrownBy(() -> handoffService.startReview(id, null)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test void dismissFromPendingSucceedsWithReason() {
    UUID tenantId = seedTenant();
    TenantContext.setTenantId(tenantId);
    UUID id = createHandoff(tenantId, "evt-dp", "rfq");

    ChannelRfqHandoffResponse response = handoffService.dismiss(id, "  spam  ", null);

    assertThat(response.status()).isEqualTo("DISMISSED");
    assertThat(response.dismissReason()).isEqualTo("spam");
    assertThat(response.dismissedAt()).isNotNull();
  }

  @Test void dismissFromInReviewSucceedsWithReason() {
    UUID tenantId = seedTenant();
    TenantContext.setTenantId(tenantId);
    UUID id = createHandoff(tenantId, "evt-dir", "rfq");
    handoffService.startReview(id, UUID.randomUUID());

    ChannelRfqHandoffResponse response = handoffService.dismiss(id, "duplicate of another request", null);

    assertThat(response.status()).isEqualTo("DISMISSED");
    assertThat(response.dismissReason()).isEqualTo("duplicate of another request");
  }

  @Test void dismissWithBlankReasonFails() {
    UUID tenantId = seedTenant();
    TenantContext.setTenantId(tenantId);
    UUID id = createHandoff(tenantId, "evt-db", "rfq");

    assertThatThrownBy(() -> handoffService.dismiss(id, "   ", null)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> handoffService.dismiss(id, null, null)).isInstanceOf(IllegalArgumentException.class);
    // Status is unchanged after a rejected dismiss.
    assertThat(handoffService.get(id).status()).isEqualTo("PENDING_REVIEW");
  }

  @Test void markConvertedFromPendingSucceedsWithoutCreatingQuoteOrOrder() {
    UUID tenantId = seedTenant();
    TenantContext.setTenantId(tenantId);
    UUID id = createHandoff(tenantId, "evt-mcp", "rfq");

    ChannelRfqHandoffResponse response = handoffService.markConverted(id, "ready for manual quote", null);

    assertThat(response.status()).isEqualTo("CONVERTED");
    assertThat(response.convertedAt()).isNotNull();
    assertThat(response.conversionNote()).isEqualTo("ready for manual quote");
    // Placeholder only: no business identifiers are mutated, no quote/order field is populated.
    assertThat(response.customerAccountId()).isNull();
    assertThat(response.customerContactId()).isNull();
  }

  @Test void markConvertedWithBlankNoteFailsWithoutChangingState() {
    UUID tenantId = seedTenant();
    TenantContext.setTenantId(tenantId);
    UUID id = createHandoff(tenantId, "evt-mcb", "rfq");

    assertThatThrownBy(() -> handoffService.markConverted(id, "   ", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("non-blank conversion note");
    assertThatThrownBy(() -> handoffService.markConverted(id, null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(handoffService.get(id).status()).isEqualTo("PENDING_REVIEW");
  }

  @Test void markConvertedFromInReviewSucceeds() {
    UUID tenantId = seedTenant();
    TenantContext.setTenantId(tenantId);
    UUID id = createHandoff(tenantId, "evt-mcir", "rfq");
    handoffService.startReview(id, UUID.randomUUID());

    ChannelRfqHandoffResponse response =
        handoffService.markConverted(id, "  handled outside Operant  ", null);

    assertThat(response.status()).isEqualTo("CONVERTED");
    assertThat(response.conversionNote()).isEqualTo("handled outside Operant");
  }

  @Test void markConvertedFromDismissedFails() {
    UUID tenantId = seedTenant();
    TenantContext.setTenantId(tenantId);
    UUID id = createHandoff(tenantId, "evt-mcd", "rfq");
    handoffService.dismiss(id, "invalid", null);

    assertThatThrownBy(() -> handoffService.markConverted(id, "already handled", null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test void crossTenantTransitionIsRejected() {
    UUID tenantA = seedTenant();
    TenantContext.setTenantId(tenantA);
    UUID idA = createHandoff(tenantA, "evt-ct", "rfq");

    UUID tenantB = seedTenant();
    TenantContext.setTenantId(tenantB);
    assertThatThrownBy(() -> handoffService.startReview(idA, null)).isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> handoffService.dismiss(idA, "x", null)).isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> handoffService.markConverted(idA, "handled elsewhere", null))
        .isInstanceOf(NotFoundException.class);

    // Tenant A's handoff is untouched.
    TenantContext.setTenantId(tenantA);
    assertThat(handoffService.get(idA).status()).isEqualTo("PENDING_REVIEW");
  }

  @Test void auditEmittedForEachTransition() {
    UUID tenantId = seedTenant();
    TenantContext.setTenantId(tenantId);
    UUID reviewId = createHandoff(tenantId, "evt-a1", "rfq");
    handoffService.startReview(reviewId, UUID.randomUUID());
    UUID convertId = createHandoff(tenantId, "evt-a2", "rfq");
    handoffService.markConverted(convertId, "note", null);
    UUID dismissId = createHandoff(tenantId, "evt-a3", "rfq");
    handoffService.dismiss(dismissId, "invalid", null);

    List<String> actions = auditEventRepository.findByTenantIdOrderByOccurredAtDesc(tenantId).stream()
        .map(AuditEvent::getAction).toList();
    assertThat(actions)
        .contains("CHANNEL_RFQ_HANDOFF_REVIEW_STARTED", "CHANNEL_RFQ_HANDOFF_CONVERTED", "CHANNEL_RFQ_HANDOFF_DISMISSED");
    String dismissMeta = auditEventRepository.findByTenantIdOrderByOccurredAtDesc(tenantId).stream()
        .filter(a -> "CHANNEL_RFQ_HANDOFF_DISMISSED".equals(a.getAction()))
        .map(AuditEvent::getMetadata).findFirst().orElse("");
    assertThat(dismissMeta).contains("\"previousStatus\":\"PENDING_REVIEW\"");
    assertThat(dismissMeta).contains("\"newStatus\":\"DISMISSED\"");
    assertThat(dismissMeta).contains("\"externalExecution\":\"DISABLED\"");
  }

  @Test void listReflectsStatusAfterTransition() {
    UUID tenantId = seedTenant();
    TenantContext.setTenantId(tenantId);
    UUID id = createHandoff(tenantId, "evt-lt", "rfq");
    handoffService.startReview(id, null);

    assertThat(handoffService.list(ChannelRfqHandoffStatus.PENDING_REVIEW)).isEmpty();
    assertThat(handoffService.list(ChannelRfqHandoffStatus.IN_REVIEW)).hasSize(1);
  }

  // --- helpers ---

  private UUID createHandoff(UUID tenantId, String externalEventId, String text) {
    InboundChannelEvent event = seedEvent(tenantId, externalEventId, text);
    return handoffService.createFromChannelEvent(commandFor(event)).id();
  }

  private void seedDirectHandoffs(UUID tenantId, int count, String prefix) {
    List<ChannelRfqHandoff> handoffs = new ArrayList<>();
    Instant base = Instant.parse("2026-06-04T00:00:00Z");
    for (int index = 0; index < count; index++) {
      InboundChannelEvent event =
          seedEvent(tenantId, prefix + "-" + index, "rfq " + index);
      handoffs.add(
          new ChannelRfqHandoff(
              tenantId,
              event.getId(),
              event.getChannelConnectionId(),
              "TELEGRAM",
              event.getExternalEventId(),
              event.getSourceActorExternalId(),
              null,
              null,
              event.getNormalizedText(),
              "RFQ_REQUEST",
              base.plusSeconds(index)));
    }
    handoffRepository.saveAllAndFlush(handoffs);
  }

  private void seedHandoffsWithSharedCreatedAt(
      UUID tenantId, int count, String prefix, Instant sharedCreatedAt) {
    List<ChannelRfqHandoff> handoffs = new ArrayList<>();
    for (int index = 0; index < count; index++) {
      InboundChannelEvent event = seedEvent(tenantId, prefix + "-" + index, "rfq " + index);
      handoffs.add(
          new ChannelRfqHandoff(
              tenantId,
              event.getId(),
              event.getChannelConnectionId(),
              "TELEGRAM",
              event.getExternalEventId(),
              event.getSourceActorExternalId(),
              null,
              null,
              event.getNormalizedText(),
              "RFQ_REQUEST",
              sharedCreatedAt));
    }
    handoffRepository.saveAllAndFlush(handoffs);
  }

  private UUID seedTenant() {
    return tenantRepository.save(new Tenant("rfq-" + UUID.randomUUID(), "RFQ Test", "ACTIVE", Instant.parse("2026-06-04T00:00:00Z"))).getId();
  }

  private InboundChannelEvent seedEvent(UUID tenantId, String externalEventId, String text) {
    InboundChannelEvent event = new InboundChannelEvent(
        tenantId, UUID.randomUUID(), ChannelProviderType.TELEGRAM, externalEventId,
        "CUSTOMER", "sender-" + externalEventId, text, "hash-" + externalEventId, "{}",
        Instant.parse("2026-06-04T00:00:00Z"));
    return eventRepository.save(event);
  }

  private CreateChannelRfqHandoffCommand commandFor(InboundChannelEvent event) {
    return new CreateChannelRfqHandoffCommand(
        event.getId(), UUID.randomUUID(), "TELEGRAM", event.getExternalEventId(),
        event.getSourceActorExternalId(), null, null, event.getNormalizedText(), "RFQ_REQUEST");
  }
}
