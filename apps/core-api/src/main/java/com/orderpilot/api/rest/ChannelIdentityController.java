package com.orderpilot.api.rest;

import com.orderpilot.api.dto.Stage10DOmnichannelDtos.*;
import com.orderpilot.application.services.channel.ChannelIdentityService;
import com.orderpilot.domain.channel.ChannelIdentity;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/channel-identities")
public class ChannelIdentityController {
  private final ChannelIdentityService service;

  public ChannelIdentityController(ChannelIdentityService service) {
    this.service = service;
  }

  @GetMapping
  public List<ChannelIdentityResponse> list() {
    return service.listIdentities().stream().map(this::toResponse).toList();
  }

  @GetMapping("/{id}")
  public ChannelIdentityResponse get(@PathVariable UUID id) {
    return toResponse(service.getIdentity(id));
  }

  @PostMapping("/{id}/link")
  public ChannelIdentityResponse link(@PathVariable UUID id, @RequestBody ChannelIdentityLinkRequest request) {
    return toResponse(service.linkIdentity(id, request.customerAccountId(), request.customerContactId(), request.linkedByUserId(), request.notes()));
  }

  @PostMapping("/{id}/unlink")
  public ChannelIdentityResponse unlink(@PathVariable UUID id, @RequestBody(required = false) ChannelIdentityActionRequest request) {
    return toResponse(service.unlinkIdentity(id, request == null ? null : request.notes()));
  }

  @PostMapping("/{id}/block")
  public ChannelIdentityResponse block(@PathVariable UUID id, @RequestBody(required = false) ChannelIdentityActionRequest request) {
    return toResponse(service.blockIdentity(id, request == null ? null : request.notes()));
  }

  private ChannelIdentityResponse toResponse(ChannelIdentity identity) {
    return new ChannelIdentityResponse(identity.getId(), identity.getChannelType(), identity.getExternalSenderId(), identity.getExternalConversationId(), identity.getSenderPhone(), identity.getSenderDisplayName(), identity.getCustomerAccountId(), identity.getCustomerContactId(), identity.getIdentityStatus(), identity.getMatchConfidence(), identity.getCreatedAt(), identity.getUpdatedAt(), identity.getLinkedAt(), identity.getLinkedByUserId(), identity.getNotes());
  }
}
