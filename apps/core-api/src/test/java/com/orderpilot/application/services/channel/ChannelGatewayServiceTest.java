package com.orderpilot.application.services.channel;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.application.services.IntakeValidationService;
import com.orderpilot.application.services.ProcessingJobService;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.audit.AuditEventRepository;
import com.orderpilot.domain.integration.ChangeRequestRepository;
import com.orderpilot.domain.channel.ChannelIdentityRepository;
import com.orderpilot.domain.intake.ChannelMessage;
import com.orderpilot.domain.intake.ChannelMessageRepository;
import com.orderpilot.domain.intake.ProcessingJobRepository;
import com.orderpilot.domain.workspace.DraftOrderRepository;
import com.orderpilot.domain.workspace.DraftQuoteRepository;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import java.util.List;
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
@Import({ChannelGatewayService.class, ChannelIdentityService.class, IntakeValidationService.class, ProcessingJobService.class, AuditEventService.class, CoreConfiguration.class})
class ChannelGatewayServiceTest {
  @Autowired private ChannelGatewayService service;
  @Autowired private ChannelMessageRepository channelMessageRepository;
  @Autowired private ProcessingJobRepository processingJobRepository;
  @Autowired private AuditEventRepository auditEventRepository;
  @Autowired private DraftQuoteRepository draftQuoteRepository;
  @Autowired private DraftOrderRepository draftOrderRepository;
  @Autowired private ChangeRequestRepository changeRequestRepository;
  @Autowired private ChannelIdentityRepository channelIdentityRepository;
  @Autowired private ChannelIdentityService channelIdentityService;

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void whatsappStyleMessageCreatesNormalizedChannelMessage() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);

    ChannelMessage message = service.accept(new NormalizedInboundMessage(tenantId, ChannelType.WHATSAPP, "wamid.1", "77001112233", "77001112233", "Buyer One", "77001112233", "Need brake pads", List.of(), null, "{\"object\":\"whatsapp_business_account\"}", "wa-1"));

    assertThat(message.getTenantId()).isEqualTo(tenantId);
    assertThat(message.getChannel()).isEqualTo("WHATSAPP");
    assertThat(message.getTextContent()).isEqualTo("Need brake pads");
    assertThat(message.getSenderHandle()).isEqualTo("77001112233");
    assertThat(message.getStatus()).isEqualTo("PENDING_REVIEW_UNLINKED_IDENTITY");
    assertThat(message.getChannelIdentityId()).isNotNull();
    assertThat(channelIdentityRepository.count()).isEqualTo(1);
    assertThat(processingJobRepository.findAll()).hasSize(1);
    assertThat(auditEventRepository.findAll()).extracting("action").contains("CHANNEL_GATEWAY_MESSAGE_RECEIVED");
  }

  @Test
  void duplicateExternalMessageIdReturnsExistingMessageWithoutCreatingDuplicate() {
    TenantContext.setTenantId(UUID.randomUUID());
    NormalizedInboundMessage inbound = new NormalizedInboundMessage(null, ChannelType.API, "api-msg-1", "api-conv-1", "api-sender", null, null, "API text", List.of(), null, "{}", "api-key-1");

    ChannelMessage first = service.accept(inbound);
    ChannelMessage second = service.accept(inbound);

    assertThat(second.getId()).isEqualTo(first.getId());
    assertThat(channelMessageRepository.findAll()).hasSize(1);
    assertThat(channelIdentityRepository.findAll()).hasSize(1);
    assertThat(processingJobRepository.findAll()).hasSize(1);
    assertThat(auditEventRepository.findAll()).extracting("action").contains("CHANNEL_GATEWAY_DUPLICATE_IGNORED");
  }

  @Test
  void idempotencyKeyCanDeduplicateWhenExternalMessageIdIsAbsent() {
    TenantContext.setTenantId(UUID.randomUUID());
    NormalizedInboundMessage inbound = new NormalizedInboundMessage(null, ChannelType.EMAIL, null, "email-thread-1", "buyer@example.test", "Buyer", null, "Email body", List.of(), null, "{}", "email-idempotency-1");

    ChannelMessage first = service.accept(inbound);
    ChannelMessage second = service.accept(inbound);

    assertThat(second.getId()).isEqualTo(first.getId());
    assertThat(first.getExternalMessageId()).isEqualTo("email-idempotency-1");
    assertThat(channelMessageRepository.findAll()).hasSize(1);
  }

  @Test
  void channelGatewayDoesNotCreateBusinessRecordsOrChangeRequests() {
    TenantContext.setTenantId(UUID.randomUUID());

    service.accept(new NormalizedInboundMessage(null, ChannelType.API, "api-msg-2", "api-conv-2", "api-sender", null, null, "Need quote for filters", List.of(), null, "{}", "api-key-2"));

    assertThat(draftQuoteRepository.count()).isZero();
    assertThat(draftOrderRepository.count()).isZero();
    assertThat(changeRequestRepository.count()).isZero();
  }

  @Test
  void linkedIdentityAttachesCustomerContextToFutureInboundMessages() {
    TenantContext.setTenantId(UUID.randomUUID());
    UUID customerAccountId = UUID.randomUUID();
    UUID customerContactId = UUID.randomUUID();
    var identity = channelIdentityService.findOrCreateUnlinkedIdentity(ChannelType.WHATSAPP, "77001112233", "77001112233", "77001112233", "Buyer One");
    channelIdentityService.linkIdentity(identity.getId(), customerAccountId, customerContactId, UUID.randomUUID(), "confirmed by operator");

    ChannelMessage message = service.accept(new NormalizedInboundMessage(null, ChannelType.WHATSAPP, "wamid-linked", "77001112233", "77001112233", "Buyer One", "77001112233", "Need oil", List.of(), null, "{}", "wa-linked"));

    assertThat(message.getChannelIdentityId()).isEqualTo(identity.getId());
    assertThat(message.getCustomerAccountId()).isEqualTo(customerAccountId);
    assertThat(message.getCustomerContactId()).isEqualTo(customerContactId);
    assertThat(message.getStatus()).isEqualTo("PENDING_PROCESSING_LINKED_IDENTITY");
    assertThat(changeRequestRepository.count()).isZero();
  }

  @Test
  void blockedIdentityFlagsFutureInboundMessagesWithoutQueueingProcessing() {
    TenantContext.setTenantId(UUID.randomUUID());
    var identity = channelIdentityService.findOrCreateUnlinkedIdentity(ChannelType.WHATSAPP, "blocked-sender", "blocked-sender", "blocked-sender", "Blocked");
    channelIdentityService.blockIdentity(identity.getId(), "spam");

    ChannelMessage message = service.accept(new NormalizedInboundMessage(null, ChannelType.WHATSAPP, "wamid-blocked", "blocked-sender", "blocked-sender", "Blocked", "blocked-sender", "spam", List.of(), null, "{}", "wa-blocked"));

    assertThat(message.getStatus()).isEqualTo("BLOCKED_IDENTITY_NEEDS_REVIEW");
    assertThat(processingJobRepository.findAll()).isEmpty();
  }

  @Test
  void tenantIsolationUsesCurrentTenantContext() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    TenantContext.setTenantId(tenantA);
    ChannelMessage a = service.accept(new NormalizedInboundMessage(null, ChannelType.API, "shared-msg", "conv-a", "sender-a", null, null, "A text", List.of(), null, "{}", "a"));
    TenantContext.setTenantId(tenantB);
    ChannelMessage b = service.accept(new NormalizedInboundMessage(null, ChannelType.API, "shared-msg", "conv-b", "sender-b", null, null, "B text", List.of(), null, "{}", "b"));

    assertThat(a.getTenantId()).isEqualTo(tenantA);
    assertThat(b.getTenantId()).isEqualTo(tenantB);
    assertThat(channelMessageRepository.findFirstByTenantIdAndChannelAndExternalMessageId(tenantA, "API", "shared-msg")).contains(a);
    assertThat(channelMessageRepository.findFirstByTenantIdAndChannelAndExternalMessageId(tenantB, "API", "shared-msg")).contains(b);
  }
}
