package com.orderpilot.api.rest;

import com.orderpilot.api.dto.Stage10DOmnichannelDtos.ChannelIdentityActionRequest;
import com.orderpilot.api.dto.Stage10DOmnichannelDtos.ChannelIdentityLinkRequest;
import com.orderpilot.api.dto.Stage10DOmnichannelDtos.ChannelIdentityResponse;
import com.orderpilot.application.services.channel.ChannelIdentityResolutionMapper;
import com.orderpilot.application.services.channel.ChannelIdentityService;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.channel.ChannelIdentity;
import com.orderpilot.security.RequestActorResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * OP-CAP-06D Channel Identity operator control and read contract.
 *
 * <p>All mutations are tenant-scoped (tenant resolved server-side from {@code TenantContext}).
 * Permission guard: GET requires {@code ADMIN_SETTINGS_READ}; mutations require
 * {@code CHANNEL_IDENTITY_ACTION} (enforced by {@code ApiPermissionInterceptor}). {@code BOT_ACTION}
 * alone never authorizes a channel-identity mutation (OP-CAP-06D.1 hardening).
 */
@RestController
@RequestMapping("/api/v1/channel-identities")
public class ChannelIdentityController {
  private final ChannelIdentityService service;
  private final RequestActorResolver actorResolver;

  public ChannelIdentityController(
      ChannelIdentityService service, RequestActorResolver actorResolver) {
    this.service = service;
    this.actorResolver = actorResolver;
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
  public ChannelIdentityResponse link(
      @PathVariable UUID id,
      @RequestBody ChannelIdentityLinkRequest request,
      HttpServletRequest http) {
    UUID linkedByUserId =
        actorResolver.resolveVerifiedActor(http, TenantContext.requireTenantId());
    return toResponse(service.linkIdentity(
        id, request.customerAccountId(), request.customerContactId(),
        linkedByUserId, request.notes()));
  }

  @PostMapping("/{id}/unlink")
  public ChannelIdentityResponse unlink(
      @PathVariable UUID id,
      @RequestBody(required = false) ChannelIdentityActionRequest request) {
    return toResponse(service.unlinkIdentity(id, request == null ? null : request.notes()));
  }

  @PostMapping("/{id}/block")
  public ChannelIdentityResponse block(
      @PathVariable UUID id,
      @RequestBody(required = false) ChannelIdentityActionRequest request) {
    return toResponse(service.blockIdentity(id, request == null ? null : request.notes()));
  }

  @PostMapping("/{id}/needs-review")
  public ChannelIdentityResponse needsReview(
      @PathVariable UUID id,
      @RequestBody(required = false) ChannelIdentityActionRequest request) {
    return toResponse(service.markNeedsReview(id, request == null ? null : request.notes()));
  }

  private ChannelIdentityResponse toResponse(ChannelIdentity identity) {
    return new ChannelIdentityResponse(
        identity.getId(),
        identity.getChannelType(),
        identity.getExternalSenderId(),
        identity.getExternalConversationId(),
        identity.getSenderPhone(),
        identity.getSenderDisplayName(),
        identity.getCustomerAccountId(),
        identity.getCustomerContactId(),
        identity.getIdentityStatus(),
        identity.getMatchConfidence(),
        identity.getCreatedAt(),
        identity.getUpdatedAt(),
        identity.getLinkedAt(),
        identity.getLinkedByUserId(),
        identity.getNotes(),
        ChannelIdentityResolutionMapper.toResolutionView(identity));
  }
}
