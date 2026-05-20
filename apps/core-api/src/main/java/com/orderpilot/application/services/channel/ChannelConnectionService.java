package com.orderpilot.application.services.channel;

import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.channel.*;
import java.time.Clock;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChannelConnectionService {
  private final ChannelConnectionRepository repository;
  private final AuditEventService auditEventService;
  private final Map<ChannelProviderType, ChannelAdapter<?>> adapters;
  private final Clock clock;

  public ChannelConnectionService(ChannelConnectionRepository repository, AuditEventService auditEventService, List<ChannelAdapter<?>> adapters, Clock clock) {
    this.repository = repository;
    this.auditEventService = auditEventService;
    this.adapters = adapters.stream().collect(Collectors.toMap(ChannelAdapter::providerType, Function.identity(), (a, b) -> a));
    this.clock = clock;
  }

  @Transactional
  public ChannelConnection createDraft(ChannelProviderType providerType, String displayName, String externalAccountId, String webhookUrl, String secretRef) {
    if (providerType == null) throw new IllegalArgumentException("providerType is required");
    if (displayName == null || displayName.isBlank()) throw new IllegalArgumentException("displayName is required");
    ChannelConnection saved = repository.save(new ChannelConnection(TenantContext.requireTenantId(), providerType, displayName.trim(), externalAccountId, webhookUrl, secretRef, clock.instant()));
    auditEventService.record("CHANNEL_CONNECTION_CREATED", "CHANNEL_CONNECTION", saved.getId().toString(), null, "{\"providerType\":\"" + providerType + "\",\"mode\":\"READ_ONLY\"}");
    return saved;
  }

  @Transactional public ChannelConnection activate(UUID id) { ChannelConnection c = get(id); requireValidConfig(c); c.activate(clock.instant()); auditEventService.record("CHANNEL_CONNECTION_ACTIVATED", "CHANNEL_CONNECTION", c.getId().toString(), null, "{}"); return c; }
  @Transactional public ChannelConnection pause(UUID id) { ChannelConnection c = get(id); c.pause(clock.instant()); auditEventService.record("CHANNEL_CONNECTION_PAUSED", "CHANNEL_CONNECTION", c.getId().toString(), null, "{}"); return c; }
  @Transactional public ChannelConnection disable(UUID id) { ChannelConnection c = get(id); c.disable(clock.instant()); auditEventService.record("CHANNEL_CONNECTION_DISABLED", "CHANNEL_CONNECTION", c.getId().toString(), null, "{}"); return c; }
  @Transactional(readOnly = true) public List<ChannelConnection> list() { return repository.findByTenantIdOrderByCreatedAtDesc(TenantContext.requireTenantId()); }
  @Transactional(readOnly = true) public ChannelConnection get(UUID id) { return repository.findByIdAndTenantId(id, TenantContext.requireTenantId()).orElseThrow(() -> new IllegalArgumentException("Channel connection not found")); }

  @Transactional
  public ChannelHealthCheckResult recordHealthCheck(UUID id) {
    ChannelConnection connection = get(id);
    ChannelAdapter<?> adapter = adapters.get(connection.getProviderType());
    ChannelHealthCheckResult result = adapter == null ? new ChannelHealthCheckResult(connection.getProviderType(), true, "NO_ADAPTER_STUB", "No adapter registered; treated as adapter-ready stub") : adapter.healthCheck(connection);
    connection.recordHealthCheck(result.healthy(), clock.instant());
    return result;
  }

  private static void requireValidConfig(ChannelConnection connection) {
    if (connection.getDisplayName() == null || connection.getDisplayName().isBlank()) throw new IllegalArgumentException("Channel connection displayName is required before activation");
  }
}
