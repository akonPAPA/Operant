package com.orderpilot.application.services.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.audit.AuditEventRepository;
import com.orderpilot.domain.channel.ChannelProviderType;
import com.orderpilot.domain.channel.ChannelRfqHandoffRepository;
import com.orderpilot.domain.channel.InboundChannelEvent;
import com.orderpilot.domain.channel.InboundChannelEventRepository;
import com.orderpilot.domain.integration.OutboxEventRepository;
import com.orderpilot.domain.tenant.Tenant;
import com.orderpilot.domain.tenant.TenantRepository;
import com.orderpilot.domain.workspace.DraftOrderRepository;
import com.orderpilot.domain.workspace.DraftQuoteRepository;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
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
class ChannelRfqHandoffTenantScopeTest {

  private static final Instant NOW = Instant.parse("2026-06-04T00:00:00Z");

  @Autowired private ChannelRfqHandoffService handoffService;
  @Autowired private ChannelRfqHandoffRepository handoffRepository;
  @Autowired private InboundChannelEventRepository eventRepository;
  @Autowired private AuditEventRepository auditEventRepository;
  @Autowired private DraftQuoteRepository draftQuoteRepository;
  @Autowired private DraftOrderRepository draftOrderRepository;
  @Autowired private OutboxEventRepository outboxEventRepository;
  @Autowired private TenantRepository tenantRepository;

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void validSameTenantRfqSourceLookupSucceeds() {
    UUID tenantId = seedTenant();
    InboundChannelEvent event = seedEvent(tenantId, "evt-same-tenant");
    TenantContext.setTenantId(tenantId);

    var response = handoffService.createFromChannelEvent(commandFor(event));

    assertThat(response.status()).isEqualTo("PENDING_REVIEW");
    assertThat(handoffRepository.findByTenantIdOrderByCreatedAtDesc(tenantId))
        .singleElement()
        .satisfies(handoff -> assertThat(handoff.getInboundChannelEventId()).isEqualTo(event.getId()));
    assertThat(auditEventRepository.findByTenantIdOrderByOccurredAtDesc(tenantId))
        .anyMatch(audit -> "CHANNEL_RFQ_HANDOFF_CREATED".equals(audit.getAction()));
  }

  @Test
  void wrongTenantSourceEventIsDeniedWithoutHandoffDraftAuditOrOutboxSideEffects() {
    UUID sourceTenantId = seedTenant();
    UUID requestingTenantId = seedTenant();
    InboundChannelEvent sourceEvent = seedEvent(sourceTenantId, "evt-wrong-tenant");
    long handoffsBefore = handoffRepository.count();
    long draftQuotesBefore = draftQuoteRepository.count();
    long draftOrdersBefore = draftOrderRepository.count();
    long outboxEventsBefore = outboxEventRepository.count();
    int requestingTenantAuditsBefore =
        auditEventRepository.findByTenantIdOrderByOccurredAtDesc(requestingTenantId).size();
    TenantContext.setTenantId(requestingTenantId);

    assertThatThrownBy(() -> handoffService.createFromChannelEvent(commandFor(sourceEvent)))
        .isInstanceOf(NotFoundException.class)
        .hasMessage("Source channel event not found for tenant");

    assertThat(handoffRepository.count()).isEqualTo(handoffsBefore);
    assertThat(draftQuoteRepository.count()).isEqualTo(draftQuotesBefore);
    assertThat(draftOrderRepository.count()).isEqualTo(draftOrdersBefore);
    assertThat(outboxEventRepository.count()).isEqualTo(outboxEventsBefore);
    assertThat(auditEventRepository.findByTenantIdOrderByOccurredAtDesc(requestingTenantId))
        .hasSize(requestingTenantAuditsBefore);
  }

  @Test
  void sourceLookupContractUsesTenantScopedRepositoryQuery() throws Exception {
    String source = Files.readString(Path.of(
        "src/main/java/com/orderpilot/application/services/channel/ChannelRfqHandoffService.java"));

    assertThat(source)
        .contains("eventRepository.findByIdAndTenantId(command.inboundChannelEventId(), tenantId)")
        .doesNotContain("eventRepository.findById(");
  }

  private UUID seedTenant() {
    return tenantRepository.saveAndFlush(
        new Tenant("rfq-scope-" + UUID.randomUUID(), "RFQ Scope Test", "ACTIVE", NOW)).getId();
  }

  private InboundChannelEvent seedEvent(UUID tenantId, String externalEventId) {
    return eventRepository.saveAndFlush(new InboundChannelEvent(
        tenantId,
        UUID.randomUUID(),
        ChannelProviderType.TELEGRAM,
        externalEventId,
        "CUSTOMER",
        "sender-" + externalEventId,
        "Please quote 10 of BRK-100",
        "hash-" + externalEventId,
        "{}",
        NOW));
  }

  private CreateChannelRfqHandoffCommand commandFor(InboundChannelEvent event) {
    return new CreateChannelRfqHandoffCommand(
        event.getId(),
        event.getChannelConnectionId(),
        "TELEGRAM",
        event.getExternalEventId(),
        event.getSourceActorExternalId(),
        null,
        null,
        event.getNormalizedText(),
        "RFQ_REQUEST");
  }
}
