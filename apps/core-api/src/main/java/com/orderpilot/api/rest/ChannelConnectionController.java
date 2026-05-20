package com.orderpilot.api.rest;

import com.orderpilot.api.dto.Stage12Dtos.*;
import com.orderpilot.application.services.channel.*;
import com.orderpilot.domain.channel.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/channels")
public class ChannelConnectionController {
  private final ChannelConnectionService connectionService;
  private final ChannelEventNormalizationService eventService;

  public ChannelConnectionController(ChannelConnectionService connectionService, ChannelEventNormalizationService eventService) {
    this.connectionService = connectionService;
    this.eventService = eventService;
  }

  @GetMapping("/providers")
  public List<ProviderResponse> providers() {
    return Arrays.stream(ChannelProviderType.values()).map(p -> new ProviderResponse(p.name(), label(p.name()), "ADAPTER_READY_STUB", "READ_ONLY")).toList();
  }

  @GetMapping("/connections") public List<ChannelConnectionResponse> list() { return connectionService.list().stream().map(this::toResponse).toList(); }
  @PostMapping("/connections") public ChannelConnectionResponse create(@RequestBody ChannelConnectionRequest request) { return toResponse(connectionService.createDraft(ChannelProviderType.valueOf(request.providerType()), request.displayName(), request.externalAccountId(), request.webhookUrl(), request.secretRef())); }
  @GetMapping("/connections/{id}") public ChannelConnectionResponse get(@PathVariable UUID id) { return toResponse(connectionService.get(id)); }
  @PostMapping("/connections/{id}/activate") public ChannelConnectionResponse activate(@PathVariable UUID id) { return toResponse(connectionService.activate(id)); }
  @PostMapping("/connections/{id}/pause") public ChannelConnectionResponse pause(@PathVariable UUID id) { return toResponse(connectionService.pause(id)); }
  @PostMapping("/connections/{id}/disable") public ChannelConnectionResponse disable(@PathVariable UUID id) { return toResponse(connectionService.disable(id)); }
  @PostMapping("/connections/{id}/health-check") public ChannelHealthResponse health(@PathVariable UUID id) { var r = connectionService.recordHealthCheck(id); return new ChannelHealthResponse(r.providerType().name(), r.healthy(), r.statusCode(), r.message()); }
  @GetMapping("/events") public List<InboundChannelEventResponse> events() { return eventService.list().stream().map(this::toResponse).toList(); }

  private ChannelConnectionResponse toResponse(ChannelConnection c) {
    return new ChannelConnectionResponse(c.getId(), c.getProviderType().name(), c.getDisplayName(), c.getStatus(), c.getMode(), c.getExternalAccountId(), c.getWebhookUrl(), c.getSecretRef() != null && !c.getSecretRef().isBlank(), c.getLastHealthCheckAt(), c.getCreatedAt(), c.getUpdatedAt());
  }

  private InboundChannelEventResponse toResponse(InboundChannelEvent e) {
    return new InboundChannelEventResponse(e.getId(), e.getChannelConnectionId(), e.getProviderType().name(), e.getExternalEventId(), e.getSourceActorType(), e.getSourceActorExternalId(), e.getNormalizedText(), e.getPayloadHash(), e.getStatus(), e.getReceivedAt(), e.getProcessedAt(), e.getErrorCode(), e.getErrorMessage());
  }

  private static String label(String value) { return value.replace('_', ' '); }
}
