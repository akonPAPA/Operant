package com.orderpilot.application.services;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.intake.ChannelMessageRepository;
import com.orderpilot.domain.intake.InboundEventLedgerRepository;
import com.orderpilot.domain.intake.WebhookEventRepository;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

class IntakeListBoundsServiceTest {
  private final ChannelMessageRepository messageRepository = mock(ChannelMessageRepository.class);
  private final InboundEventLedgerRepository ledgerRepository = mock(InboundEventLedgerRepository.class);
  private final ChannelMessageService messageService = new ChannelMessageService(
      messageRepository,
      ledgerRepository,
      mock(IntakeValidationService.class),
      mock(ProcessingJobService.class),
      mock(AuditEventService.class),
      mock(ObjectStorageService.class),
      Clock.systemUTC());
  private final WebhookEventService webhookEventService = new WebhookEventService(
      mock(WebhookEventRepository.class),
      ledgerRepository,
      mock(WebhookSecurityService.class),
      mock(ObjectStorageService.class),
      Clock.systemUTC());

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void messageListDefaultsCapsAndNormalizesInvalidLimits() {
    UUID tenantId = setTenant();

    messageService.list();
    messageService.list(500);
    messageService.list(0);
    messageService.list(-10);

    verify(messageRepository, times(3))
        .findByTenantIdOrderByReceivedAtDesc(tenantId, PageRequest.of(0, 50));
    verify(messageRepository)
        .findByTenantIdOrderByReceivedAtDesc(tenantId, PageRequest.of(0, 100));
    verify(messageRepository, never()).findByTenantIdOrderByReceivedAtDesc(tenantId);
  }

  @Test
  void conversationListDefaultsCapsAndPreservesAscendingRepositoryOrder() {
    UUID tenantId = setTenant();
    String conversationId = "conversation-1";

    messageService.conversation(conversationId);
    messageService.conversation(conversationId, 500);
    messageService.conversation(conversationId, 0);
    messageService.conversation(conversationId, -10);

    verify(messageRepository, times(3))
        .findByTenantIdAndConversationIdOrderByReceivedAt(
            tenantId, conversationId, PageRequest.of(0, 50));
    verify(messageRepository)
        .findByTenantIdAndConversationIdOrderByReceivedAt(
            tenantId, conversationId, PageRequest.of(0, 100));
    verify(messageRepository, never())
        .findByTenantIdAndConversationIdOrderByReceivedAt(tenantId, conversationId);
  }

  @Test
  void ledgerListDefaultsCapsAndNormalizesInvalidLimits() {
    UUID tenantId = setTenant();

    webhookEventService.listLedger();
    webhookEventService.listLedger(500);
    webhookEventService.listLedger(0);
    webhookEventService.listLedger(-10);

    verify(ledgerRepository, times(3))
        .findByTenantIdOrderByReceivedAtDesc(tenantId, PageRequest.of(0, 50));
    verify(ledgerRepository)
        .findByTenantIdOrderByReceivedAtDesc(tenantId, PageRequest.of(0, 100));
    verify(ledgerRepository, never()).findByTenantIdOrderByReceivedAtDesc(tenantId);
  }

  private static UUID setTenant() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    return tenantId;
  }
}
