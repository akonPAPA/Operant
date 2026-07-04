package com.orderpilot.application.services.channel;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.application.services.runtime.RuntimeGuardDecision;
import com.orderpilot.application.services.runtime.RuntimeGuardReasonCodes;
import com.orderpilot.application.services.runtime.RuntimeGuardRequest;
import com.orderpilot.application.services.runtime.RuntimeGuardService;
import com.orderpilot.application.services.runtime.RuntimeOperationType;
import com.orderpilot.application.services.runtime.RuntimeRateLimitedException;
import com.orderpilot.application.services.workspace.RfqToDraftQuoteService;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.channel.ChannelRfqHandoff;
import com.orderpilot.domain.channel.ChannelRfqHandoffRepository;
import com.orderpilot.domain.channel.ChannelRfqHandoffStatus;
import com.orderpilot.domain.workspace.DraftQuote;
import com.orderpilot.domain.workspace.DraftQuoteRepository;
import com.orderpilot.security.policy.ActorRole;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * OP-CAP-27B — the runtime guard on the RFQ handoff draft-quote creation and safe-terminal decision
 * boundaries fails closed. On a denial no draft quote is created, no handoff transition occurs, no
 * quote status is mutated, and no decision audit is recorded — the guard short-circuits before any
 * business write.
 */
class RfqHandoffDraftQuoteRuntimeGuardStage27BTest {
  private final ChannelRfqHandoffRepository handoffRepository =
      mock(ChannelRfqHandoffRepository.class);
  private final ChannelRfqHandoffService handoffService = mock(ChannelRfqHandoffService.class);
  private final RfqToDraftQuoteService draftQuoteService = mock(RfqToDraftQuoteService.class);
  private final DraftQuoteRepository draftQuoteRepository = mock(DraftQuoteRepository.class);
  private final AuditEventService auditEventService = mock(AuditEventService.class);
  private final RuntimeGuardService runtimeGuardService = mock(RuntimeGuardService.class);

  private final RfqHandoffDraftQuoteService service =
      new RfqHandoffDraftQuoteService(
          handoffRepository,
          handoffService,
          draftQuoteService,
          draftQuoteRepository,
          auditEventService,
          runtimeGuardService,
          Clock.systemUTC());

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void runtimeDenialBlocksDraftQuoteCreationBeforePersistenceAndConversion() {
    TenantContext.setTenantId(UUID.randomUUID());
    UUID handoffId = UUID.randomUUID();
    ChannelRfqHandoff handoff = mock(ChannelRfqHandoff.class);
    when(handoff.getStatus()).thenReturn(ChannelRfqHandoffStatus.IN_REVIEW);
    when(handoffRepository.findWithLockByIdAndTenantId(any(), any()))
        .thenReturn(Optional.of(handoff));
    when(draftQuoteService.findByIdempotencyKey(any())).thenReturn(Optional.empty());
    when(runtimeGuardService.enforce(any(RuntimeGuardRequest.class)))
        .thenThrow(new RuntimeRateLimitedException(deniedDecision(
            RuntimeOperationType.RFQ_HANDOFF_DRAFT_QUOTE_CREATE)));

    assertThatThrownBy(
            () -> service.createDraftQuote(handoffId, UUID.randomUUID(), ActorRole.OPERATOR))
        .isInstanceOf(RuntimeRateLimitedException.class);

    verify(draftQuoteService, never()).createFromRfq(any(), any());
    verify(handoffService, never()).markConverted(any(), any(), any());
    verifyNoInteractions(auditEventService);
  }

  @Test
  void runtimeDenialBlocksSafeTerminalDecisionBeforeQuoteMutationAndAudit() {
    TenantContext.setTenantId(UUID.randomUUID());
    UUID handoffId = UUID.randomUUID();
    ChannelRfqHandoff handoff = mock(ChannelRfqHandoff.class);
    when(handoff.getStatus()).thenReturn(ChannelRfqHandoffStatus.CONVERTED);
    when(handoffRepository.findWithLockByIdAndTenantId(any(), any()))
        .thenReturn(Optional.of(handoff));
    DraftQuote quote = mock(DraftQuote.class);
    when(quote.getSourceType()).thenReturn("RFQ_HANDOFF");
    when(quote.getStatus()).thenReturn("NEEDS_REVIEW");
    when(draftQuoteRepository.findWithLockByTenantIdAndIdempotencyKey(any(), any()))
        .thenReturn(Optional.of(quote));
    when(runtimeGuardService.enforce(any(RuntimeGuardRequest.class)))
        .thenThrow(new RuntimeRateLimitedException(deniedDecision(
            RuntimeOperationType.RFQ_HANDOFF_DEMO_DECISION)));

    assertThatThrownBy(
            () ->
                service.decide(
                    handoffId,
                    UUID.randomUUID(),
                    ActorRole.SALES_QUOTE_MANAGER,
                    "COMPLETE_DEMO",
                    "Operator completed the safe local demo review."))
        .isInstanceOf(RuntimeRateLimitedException.class);

    verify(quote, never()).transition(any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any(), any());
    verify(draftQuoteRepository, never()).save(any());
    verifyNoInteractions(auditEventService);
  }

  private static RuntimeGuardDecision deniedDecision(RuntimeOperationType operationType) {
    return new RuntimeGuardDecision(
        false,
        429,
        RuntimeGuardReasonCodes.RATE_LIMIT_EXCEEDED,
        operationType,
        null,
        1L,
        null,
        0L,
        null,
        30L,
        "bucket");
  }
}
