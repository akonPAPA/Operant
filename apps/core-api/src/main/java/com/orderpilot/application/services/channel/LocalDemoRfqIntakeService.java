package com.orderpilot.application.services.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.orderpilot.api.dto.DemoRfqHandoffDtos.DemoRfqHandoffResponse;
import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.application.services.runtime.RuntimeGuardRequest;
import com.orderpilot.application.services.runtime.RuntimeGuardService;
import com.orderpilot.application.services.runtime.RuntimeOperationType;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.channel.ChannelConnection;
import com.orderpilot.domain.channel.ChannelConnectionRepository;
import com.orderpilot.domain.channel.ChannelProviderType;
import com.orderpilot.domain.channel.ChannelRfqHandoff;
import com.orderpilot.domain.channel.ChannelRfqHandoffRepository;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Backend-owned local/demo Telegram RFQ intake.
 *
 * <p>The caller supplies no provider payload or authority fields. Tenant comes from the trusted
 * request context; the source connection, provider event identity, sender, text, deduplication, and
 * initial handoff state are fixed or resolved here. The managed channel bridge remains the only
 * writer of the inbound event, legacy bot RFQ, and reviewable channel handoff.
 */
@Service
public class LocalDemoRfqIntakeService {
  static final String DEMO_EXTERNAL_ACCOUNT_ID = "operant-local-demo";
  static final String DEMO_EXTERNAL_EVENT_ID = "operant-local-demo-rfq-1";
  static final String DEMO_SENDER_ID = "450001";
  static final String DEMO_RFQ_TEXT =
      "Need 2 EA PAD-OE-04465 brake pads for Toyota Camry 2018, wholesale, Almaty.";

  private final ChannelConnectionRepository connectionRepository;
  private final ChannelRfqHandoffRepository handoffRepository;
  private final ChannelBotRuntimeBridgeService bridgeService;
  private final AuditEventService auditEventService;
  private final RuntimeGuardService runtimeGuardService;
  private final ObjectMapper objectMapper;

  public LocalDemoRfqIntakeService(
      ChannelConnectionRepository connectionRepository,
      ChannelRfqHandoffRepository handoffRepository,
      ChannelBotRuntimeBridgeService bridgeService,
      AuditEventService auditEventService,
      RuntimeGuardService runtimeGuardService,
      ObjectMapper objectMapper) {
    this.connectionRepository = connectionRepository;
    this.handoffRepository = handoffRepository;
    this.bridgeService = bridgeService;
    this.auditEventService = auditEventService;
    this.runtimeGuardService = runtimeGuardService;
    this.objectMapper = objectMapper;
  }

  @Transactional
  public DemoRfqHandoffResponse createOrGet(UUID actorId) {
    UUID tenantId = TenantContext.requireTenantId();

    // OP-CAP-27B runtime-control checkpoint: rate/backpressure guard BEFORE any inbound event,
    // channel handoff, bot conversation, or audit side effect is created. A denial throws a stable
    // mapped exception (429 rate/backpressure; 403 quota) and fails closed — no state is mutated and
    // no external write is attempted. Deterministic operator-initiated demo op → 1 unit; cost is
    // governed by the fixed operation weight, not payload size (there is no quota metric here).
    runtimeGuardService.enforce(
        RuntimeGuardRequest.of(tenantId, RuntimeOperationType.DEMO_RFQ_HANDOFF_CREATE, 1));

    ChannelConnection connection =
        connectionRepository
            .findFirstByTenantIdAndProviderTypeAndExternalAccountIdOrderByCreatedAtDesc(
                tenantId, ChannelProviderType.TELEGRAM, DEMO_EXTERNAL_ACCOUNT_ID)
            .orElseThrow(
                () ->
                    new NotFoundException(
                        "Local demo Telegram source is not configured for this tenant"));
    requireSafeLocalDemoConnection(connection);

    var bridgeResult =
        bridgeService.handleInbound(
            connection.getId(),
            ChannelProviderType.TELEGRAM,
            demoTelegramPayload(),
            Map.of());

    ChannelRfqHandoff handoff =
        handoffRepository
            .findFirstByTenantIdAndInboundChannelEventId(tenantId, bridgeResult.eventId())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Demo RFQ could not be routed to operator review"));

    auditEventService.record(
        "LOCAL_DEMO_RFQ_INTAKE_READY",
        "CHANNEL_RFQ_HANDOFF",
        handoff.getId().toString(),
        actorId,
        "{\"status\":\""
            + handoff.getStatus().name()
            + "\",\"externalExecution\":\"DISABLED\"}");

    return new DemoRfqHandoffResponse(
        handoff.getId(),
        handoff.getStatus().name(),
        "Demo RFQ is ready in the RFQ handoff workspace.");
  }

  private static void requireSafeLocalDemoConnection(ChannelConnection connection) {
    if (!"ACTIVE".equals(connection.getStatus())
        || !"READ_ONLY".equals(connection.getMode())
        || !"DISABLED_FOR_LOCAL_DEV".equals(connection.getWebhookVerificationMode())) {
      throw new IllegalArgumentException(
          "Local demo Telegram source must be ACTIVE, READ_ONLY, and local-verification only");
    }
  }

  private ObjectNode demoTelegramPayload() {
    ObjectNode root = objectMapper.createObjectNode();
    root.put("update_id", DEMO_EXTERNAL_EVENT_ID);
    ObjectNode message = root.putObject("message");
    message.put("message_id", DEMO_EXTERNAL_EVENT_ID);
    message.putObject("chat").put("id", DEMO_SENDER_ID);
    message.put("text", DEMO_RFQ_TEXT);
    return root;
  }
}
