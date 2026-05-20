package com.orderpilot.application.services.channel;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class WhatsAppInboundAdapter implements ChannelAdapter<JsonNode> {
  private final Clock clock;

  public WhatsAppInboundAdapter(Clock clock) {
    this.clock = clock;
  }

  @Override
  public ChannelType channelType() {
    return ChannelType.WHATSAPP;
  }

  @Override
  public List<NormalizedInboundMessage> normalize(JsonNode payload) {
    List<NormalizedInboundMessage> messages = new ArrayList<>();
    if (payload == null || payload.isMissingNode()) {
      throw new IllegalArgumentException("Malformed WhatsApp webhook payload");
    }
    JsonNode entries = payload.path("entry");
    if (!entries.isArray()) {
      throw new IllegalArgumentException("Malformed WhatsApp webhook payload");
    }
    for (JsonNode entry : entries) {
      JsonNode changes = entry.path("changes");
      if (!changes.isArray()) {
        continue;
      }
      for (JsonNode change : changes) {
        JsonNode value = change.path("value");
        String displayName = firstContactName(value.path("contacts"));
        JsonNode inboundMessages = value.path("messages");
        if (!inboundMessages.isArray()) {
          continue;
        }
        for (JsonNode message : inboundMessages) {
          if (!"text".equals(message.path("type").asText())) {
            continue;
          }
          String text = message.path("text").path("body").asText(null);
          String messageId = message.path("id").asText(null);
          String from = message.path("from").asText(null);
          if (isBlank(text) || isBlank(messageId) || isBlank(from)) {
            continue;
          }
          messages.add(new NormalizedInboundMessage(
              null,
              ChannelType.WHATSAPP,
              messageId,
              from,
              from,
              displayName,
              from,
              text.trim(),
              List.of(),
              receivedAt(message.path("timestamp").asText(null)),
              value.toString(),
              "WHATSAPP:" + messageId));
        }
      }
    }
    return messages;
  }

  private Instant receivedAt(String epochSeconds) {
    if (isBlank(epochSeconds)) {
      return clock.instant();
    }
    try {
      return Instant.ofEpochSecond(Long.parseLong(epochSeconds));
    } catch (NumberFormatException ex) {
      return clock.instant();
    }
  }

  private static String firstContactName(JsonNode contacts) {
    if (contacts != null && contacts.isArray() && contacts.size() > 0) {
      return contacts.get(0).path("profile").path("name").asText(null);
    }
    return null;
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}