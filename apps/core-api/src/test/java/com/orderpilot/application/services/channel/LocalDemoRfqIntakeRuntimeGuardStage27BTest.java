package com.orderpilot.application.services.channel;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.application.services.runtime.RuntimeGuardDecision;
import com.orderpilot.application.services.runtime.RuntimeGuardReasonCodes;
import com.orderpilot.application.services.runtime.RuntimeGuardRequest;
import com.orderpilot.application.services.runtime.RuntimeGuardService;
import com.orderpilot.application.services.runtime.RuntimeOperationType;
import com.orderpilot.application.services.runtime.RuntimeRateLimitedException;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.channel.ChannelConnectionRepository;
import com.orderpilot.domain.channel.ChannelRfqHandoffRepository;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * OP-CAP-27B — the runtime guard on the deterministic demo RFQ handoff creation boundary fails closed:
 * a denial short-circuits before the channel bridge, the handoff repository, and any audit side effect,
 * so no inbound event, no handoff, and no audit record is created and no external write is attempted.
 */
class LocalDemoRfqIntakeRuntimeGuardStage27BTest {
  private final ChannelConnectionRepository connectionRepository =
      mock(ChannelConnectionRepository.class);
  private final ChannelRfqHandoffRepository handoffRepository =
      mock(ChannelRfqHandoffRepository.class);
  private final ChannelBotRuntimeBridgeService bridgeService =
      mock(ChannelBotRuntimeBridgeService.class);
  private final AuditEventService auditEventService = mock(AuditEventService.class);
  private final RuntimeGuardService runtimeGuardService = mock(RuntimeGuardService.class);

  private final LocalDemoRfqIntakeService service =
      new LocalDemoRfqIntakeService(
          connectionRepository,
          handoffRepository,
          bridgeService,
          auditEventService,
          runtimeGuardService,
          new ObjectMapper());

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void runtimeDenialBlocksDemoRfqCreationBeforeAnySideEffect() {
    TenantContext.setTenantId(UUID.randomUUID());
    when(runtimeGuardService.enforce(any(RuntimeGuardRequest.class)))
        .thenThrow(new RuntimeRateLimitedException(deniedDecision()));

    assertThatThrownBy(() -> service.createOrGet(UUID.randomUUID()))
        .isInstanceOf(RuntimeRateLimitedException.class);

    verifyNoInteractions(connectionRepository, handoffRepository, bridgeService, auditEventService);
  }

  private static RuntimeGuardDecision deniedDecision() {
    return new RuntimeGuardDecision(
        false,
        429,
        RuntimeGuardReasonCodes.RATE_LIMIT_EXCEEDED,
        RuntimeOperationType.DEMO_RFQ_HANDOFF_CREATE,
        null,
        1L,
        null,
        0L,
        null,
        30L,
        "bucket");
  }
}
