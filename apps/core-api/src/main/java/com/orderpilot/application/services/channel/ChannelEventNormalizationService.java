package com.orderpilot.application.services.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.channel.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChannelEventNormalizationService {
  private final ChannelConnectionRepository connectionRepository;
  private final InboundChannelEventRepository eventRepository;
  private final AuditEventService auditEventService;
  private final Map<ChannelProviderType, ChannelAdapter<?>> adapters;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  public ChannelEventNormalizationService(ChannelConnectionRepository connectionRepository, InboundChannelEventRepository eventRepository, AuditEventService auditEventService, List<ChannelAdapter<?>> adapters, ObjectMapper objectMapper, Clock clock) {
    this.connectionRepository = connectionRepository;
    this.eventRepository = eventRepository;
    this.auditEventService = auditEventService;
    this.adapters = adapters.stream().collect(Collectors.toMap(ChannelAdapter::providerType, Function.identity(), (a, b) -> a));
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  @Transactional
  public InboundChannelEvent normalize(UUID connectionId, ChannelProviderType providerType, Object payload) {
    UUID tenantId = TenantContext.requireTenantId();
    ChannelConnection connection = connectionRepository.findByIdAndTenantId(connectionId, tenantId).orElseThrow(() -> new IllegalArgumentException("Channel connection not found"));
    if (!connection.getProviderType().equals(providerType)) throw new IllegalArgumentException("Webhook provider does not match channel connection");
    if (!"ACTIVE".equals(connection.getStatus())) throw new IllegalArgumentException("Channel connection must be ACTIVE to receive webhooks");
    ChannelAdapter<?> adapter = adapters.get(providerType);
    if (adapter == null) throw new IllegalArgumentException("No channel adapter registered for " + providerType);
    NormalizedChannelEvent normalized = adapter.normalizeInbound(payload, connection);
    String rawJson = normalized.rawPayloadJson() == null ? toJson(payload) : normalized.rawPayloadJson();
    String hash = sha256(rawJson);
    if (normalized.externalEventId() != null && !normalized.externalEventId().isBlank()) {
      Optional<InboundChannelEvent> duplicate = eventRepository.findFirstByTenantIdAndProviderTypeAndExternalEventId(tenantId, providerType, normalized.externalEventId());
      if (duplicate.isPresent()) return duplicate.get();
    }
    Optional<InboundChannelEvent> duplicateByHash = eventRepository.findFirstByTenantIdAndChannelConnectionIdAndPayloadHash(tenantId, connectionId, hash);
    if (duplicateByHash.isPresent()) return duplicateByHash.get();
    InboundChannelEvent saved = eventRepository.save(new InboundChannelEvent(tenantId, connectionId, providerType, normalized.externalEventId(), normalized.sourceActorType(), normalized.sourceActorExternalId(), normalized.normalizedText(), hash, rawJson, clock.instant()));
    auditEventService.record("INBOUND_CHANNEL_EVENT_RECEIVED", "INBOUND_CHANNEL_EVENT", saved.getId().toString(), null, "{\"providerType\":\"" + providerType + "\",\"businessAction\":\"NONE\"}");
    return saved;
  }

  @Transactional(readOnly = true)
  public List<InboundChannelEvent> list() {
    return eventRepository.findByTenantIdOrderByReceivedAtDesc(TenantContext.requireTenantId());
  }

  private String toJson(Object payload) {
    try { return objectMapper.writeValueAsString(payload == null ? Map.of() : payload); } catch (Exception ex) { return "{}"; }
  }

  private static String sha256(String value) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
      StringBuilder builder = new StringBuilder();
      for (byte b : digest) builder.append(String.format("%02x", b));
      return builder.toString();
    } catch (Exception ex) {
      throw new IllegalStateException("Unable to hash inbound payload", ex);
    }
  }
}
