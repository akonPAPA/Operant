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
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChannelEventNormalizationService {
  private final ChannelConnectionRepository connectionRepository;
  private final InboundChannelEventRepository eventRepository;
  private final AuditEventService auditEventService;
  private final Map<ChannelProviderType, ChannelAdapter<?>> adapters;
  private final Map<ChannelProviderType, ChannelWebhookVerifier> verifiers;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  public ChannelEventNormalizationService(ChannelConnectionRepository connectionRepository, InboundChannelEventRepository eventRepository, AuditEventService auditEventService, List<ChannelAdapter<?>> adapters, List<ChannelWebhookVerifier> verifiers, ObjectMapper objectMapper, Clock clock) {
    this.connectionRepository = connectionRepository;
    this.eventRepository = eventRepository;
    this.auditEventService = auditEventService;
    this.adapters = adapters.stream().collect(Collectors.toMap(ChannelAdapter::providerType, Function.identity(), (a, b) -> a));
    this.verifiers = verifiers.stream().collect(Collectors.toMap(ChannelWebhookVerifier::providerType, Function.identity(), (a, b) -> a));
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  @Transactional
  public InboundChannelEvent normalize(UUID connectionId, ChannelProviderType providerType, Object payload) {
    return normalize(connectionId, providerType, payload, Map.of());
  }

  @Transactional
  public InboundChannelEvent normalize(UUID connectionId, ChannelProviderType providerType, Object payload, Map<String, String> headers) {
    UUID tenantId = TenantContext.requireTenantId();
    ChannelConnection connection = connectionRepository.findByIdAndTenantId(connectionId, tenantId).orElseThrow(() -> new IllegalArgumentException("Channel connection not found"));
    if (!connection.getProviderType().equals(providerType)) throw new IllegalArgumentException("Webhook provider does not match channel connection");
    if (!"ACTIVE".equals(connection.getStatus())) throw new IllegalArgumentException("Channel connection must be ACTIVE to receive webhooks");
    String rawJson = toJson(payload);
    ChannelWebhookVerifier verifier = verifiers.get(providerType);
    VerificationResult verification = verifier == null ? VerificationResult.skippedLocalDev("No provider verifier registered; local adapter-ready mode only") : verifier.verify(connection, headers, rawJson);
    if (!verification.accepted()) {
      auditEventService.record("CHANNEL_WEBHOOK_VERIFICATION_FAILED", "CHANNEL_CONNECTION", connection.getId().toString(), null, "{\"providerType\":\"" + providerType + "\",\"reason\":\"" + sanitize(verification.reason()) + "\"}");
      throw new IllegalArgumentException("Webhook verification failed");
    }
    ChannelAdapter<?> adapter = adapters.get(providerType);
    if (adapter == null) throw new IllegalArgumentException("No channel adapter registered for " + providerType);
    NormalizedChannelEvent normalized = adapter.normalizeInbound(payload, connection);
    rawJson = normalized.rawPayloadJson() == null ? rawJson : normalized.rawPayloadJson();
    String hash = sha256(rawJson);
    if (normalized.externalEventId() != null && !normalized.externalEventId().isBlank()) {
      Optional<InboundChannelEvent> duplicate = eventRepository.findFirstByTenantIdAndProviderTypeAndExternalEventId(tenantId, providerType, normalized.externalEventId());
      if (duplicate.isPresent()) return duplicate.get();
    }
    Optional<InboundChannelEvent> duplicateByHash = eventRepository.findFirstByTenantIdAndChannelConnectionIdAndPayloadHash(tenantId, connectionId, hash);
    if (duplicateByHash.isPresent()) return duplicateByHash.get();
    InboundChannelEvent saved = eventRepository.save(new InboundChannelEvent(tenantId, connectionId, providerType, normalized.externalEventId(), normalized.sourceActorType(), normalized.sourceActorExternalId(), normalized.normalizedText(), hash, rawJson, verification.status(), verification.reason(), clock.instant()));
    auditEventService.record("CHANNEL_WEBHOOK_ACCEPTED", "INBOUND_CHANNEL_EVENT", saved.getId().toString(), null, "{\"providerType\":\"" + providerType + "\",\"businessAction\":\"NONE\"}");
    return saved;
  }

  @Transactional(readOnly = true)
  public List<InboundChannelEvent> list() {
    return eventRepository.findByTenantIdOrderByReceivedAtDesc(TenantContext.requireTenantId());
  }

  /**
   * Bounded, tenant-scoped recent inbound events for operator read surfaces. The caller supplies an
   * already-clamped {@link Limit} so this read path can never load a tenant's entire event history.
   */
  @Transactional(readOnly = true)
  public List<InboundChannelEvent> listRecent(Limit limit) {
    return eventRepository.findByTenantIdOrderByReceivedAtDesc(TenantContext.requireTenantId(), limit);
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

  private static String sanitize(String value) {
    if (value == null) return "";
    return value.replace("\"", "'").replace("\\", "");
  }
}
