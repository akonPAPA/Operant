package com.orderpilot.api.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.orderpilot.api.dto.ChannelBotBridgeDtos.ChannelBotBridgeEventResponse;
import com.orderpilot.api.dto.ChannelBotBridgeDtos.ChannelBotBridgeResultResponse;
import com.orderpilot.api.dto.ChannelBotBridgeDtos.ChannelBotBridgeStatusResponse;
import com.orderpilot.application.services.channel.ChannelBotRuntimeBridgeService;
import com.orderpilot.domain.channel.ChannelProviderType;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * OP-CAP-06A Messenger Chatbot Integration Layer.
 *
 * <p>The webhook entrypoint lives under {@code /api/v1/webhooks/channels/**} so it inherits the
 * existing provider-verified (un-permissioned) webhook treatment; trust is enforced by the managed
 * connection + verifier inside the service. The operator read endpoint lives under
 * {@code /api/v1/channels/**} so it inherits the existing {@code ADMIN_SETTINGS_READ} permission.
 */
@RestController
public class ChannelBotBridgeController {
  private final ChannelBotRuntimeBridgeService bridgeService;

  public ChannelBotBridgeController(ChannelBotRuntimeBridgeService bridgeService) {
    this.bridgeService = bridgeService;
  }

  /** Secure per-connection Telegram webhook that drives the controlled bot runtime. */
  @PostMapping("/api/v1/webhooks/channels/bot/telegram/{connectionId}")
  public ChannelBotBridgeResultResponse telegram(
      @PathVariable UUID connectionId,
      @RequestHeader Map<String, String> headers,
      @RequestBody JsonNode payload) {
    return bridgeService.handleInbound(connectionId, ChannelProviderType.TELEGRAM, payload, headers);
  }

  /**
   * Operator-facing list linking recent normalized channel events to their bot conversations.
   * Bounded: {@code limit} is advisory and clamped server-side, defaulting to a safe recent window.
   */
  @GetMapping("/api/v1/channels/bot-events")
  public List<ChannelBotBridgeEventResponse> bridgedEvents(
      @RequestParam(name = "limit", required = false) Integer limit) {
    return bridgeService.listRecentBridgedEvents(limit);
  }

  /** Operator-facing bridge status: controlled safety contract plus a bounded recent-window summary. */
  @GetMapping("/api/v1/channels/bot-bridge/status")
  public ChannelBotBridgeStatusResponse bridgeStatus(
      @RequestParam(name = "limit", required = false) Integer limit) {
    return bridgeService.getBridgeStatus(limit);
  }
}
