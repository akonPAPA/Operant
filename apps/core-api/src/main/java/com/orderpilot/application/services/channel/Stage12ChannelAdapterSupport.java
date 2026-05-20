package com.orderpilot.application.services.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.domain.channel.ChannelConnection;
import com.orderpilot.domain.channel.ChannelProviderType;
import java.util.List;

abstract class Stage12ChannelAdapterSupport implements ChannelAdapter<Object> {
  private final ChannelProviderType providerType;
  private final ChannelType channelType;
  private final ObjectMapper objectMapper;

  Stage12ChannelAdapterSupport(ChannelProviderType providerType, ChannelType channelType, ObjectMapper objectMapper) {
    this.providerType = providerType;
    this.channelType = channelType;
    this.objectMapper = objectMapper;
  }

  @Override public ChannelType channelType() { return channelType; }
  @Override public ChannelProviderType providerType() { return providerType; }
  @Override public List<NormalizedInboundMessage> normalize(Object payload) { return List.of(); }

  @Override
  public NormalizedChannelEvent normalizeInbound(Object providerPayload, ChannelConnection connection) {
    JsonNode root = objectMapper.valueToTree(providerPayload == null ? "{}" : providerPayload);
    String externalId = firstText(root, "message_id", "id", "event_id", "externalEventId");
    if (externalId == null && root.has("message")) {
      externalId = firstText(root.path("message"), "message_id", "id");
    }
    if (externalId == null && root.has("entry")) {
      externalId = "event-" + Math.abs(root.toString().hashCode());
    }
    String sender = firstText(root, "from", "sender", "sender_id", "externalSenderId", "phone");
    if (sender == null && root.has("message")) {
      sender = firstText(root.path("message").path("chat"), "id", "username");
    }
    String text = firstText(root, "text", "body", "message", "caption", "rawText");
    if (text == null && root.has("message")) {
      text = firstText(root.path("message"), "text", "caption");
    }
    if (text == null && root.has("entry")) {
      text = root.findPath("body").asText(null);
    }
    return new NormalizedChannelEvent(externalId, "CUSTOMER", sender, text == null ? "" : text.trim(), root.toString());
  }

  @Override
  public ChannelHealthCheckResult healthCheck(ChannelConnection connection) {
    return new ChannelHealthCheckResult(providerType, true, "ADAPTER_READY_STUB", "Stage 12 stub only; no provider network call performed");
  }

  private static String firstText(JsonNode node, String... names) {
    if (node == null || node.isMissingNode()) return null;
    for (String name : names) {
      JsonNode value = node.path(name);
      if (!value.isMissingNode() && !value.isNull() && !value.asText("").isBlank()) return value.asText();
    }
    return null;
  }
}
