package com.orderpilot.api.rest;

import com.orderpilot.api.dto.ChannelRfqHandoffDtos.ChannelRfqHandoffResponse;
import com.orderpilot.api.dto.ChannelRfqHandoffDtos.DismissRfqHandoffRequest;
import com.orderpilot.api.dto.ChannelRfqHandoffDtos.MarkConvertedRfqHandoffRequest;
import com.orderpilot.application.services.channel.ChannelRfqHandoffService;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.channel.ChannelRfqHandoffStatus;
import com.orderpilot.security.RequestActorResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * OP-CAP-06B controlled Bot Runtime RFQ Handoff read API.
 *
 * <p>Operator-facing, read-only. Mounted under {@code /api/v1/channels/**} so it inherits the
 * existing {@code ADMIN_SETTINGS_READ} permission. There is no create endpoint here on purpose:
 * handoffs are created only from the verified channel/bot bridge flow via
 * {@link ChannelRfqHandoffService}, so the bot/channel path can never directly POST arbitrary
 * business records. Business logic stays in the service; this controller only adapts request/response.
 *
 * <p>OP-CAP-06C adds operator-review workflow actions (start-review / dismiss / mark-converted).
 * These are tenant-scoped, strictly state-checked, and audited in the service layer. They never
 * create a quote/order, approve anything, mutate inventory/price/customer/product data, or trigger
 * an external/ERP write. All transition logic lives in the service, never in this controller.
 */
@RestController
public class ChannelRfqHandoffController {
  private final ChannelRfqHandoffService handoffService;
  private final RequestActorResolver actorResolver;

  public ChannelRfqHandoffController(
      ChannelRfqHandoffService handoffService, RequestActorResolver actorResolver) {
    this.handoffService = handoffService;
    this.actorResolver = actorResolver;
  }

  /** List tenant-scoped RFQ handoffs, optionally filtered by status. */
  @GetMapping("/api/v1/channels/rfq-handoffs")
  public List<ChannelRfqHandoffResponse> list(
      @RequestParam(name = "status", required = false) String status) {
    return handoffService.list(parseStatus(status));
  }

  /** Get one tenant-scoped RFQ handoff by id. */
  @GetMapping("/api/v1/channels/rfq-handoffs/{id}")
  public ChannelRfqHandoffResponse get(@PathVariable UUID id) {
    return handoffService.get(id);
  }

  /**
   * Take a handoff into review (PENDING_REVIEW -&gt; IN_REVIEW). Carries no client payload: the
   * acting reviewer is resolved from the trusted request context, never from the request body.
   */
  @PostMapping("/api/v1/channels/rfq-handoffs/{id}/start-review")
  public ChannelRfqHandoffResponse startReview(@PathVariable UUID id, HttpServletRequest http) {
    return handoffService.startReview(id, trustedActor(http));
  }

  /** Dismiss a handoff as invalid/irrelevant. Requires a non-blank reason. */
  @PostMapping("/api/v1/channels/rfq-handoffs/{id}/dismiss")
  public ChannelRfqHandoffResponse dismiss(
      @PathVariable UUID id,
      @RequestBody(required = false) DismissRfqHandoffRequest request,
      HttpServletRequest http) {
    return handoffService.dismiss(
        id,
        request == null ? null : request.reason(),
        trustedActor(http));
  }

  /**
   * Close a handoff without creating a draft. Requires a non-blank operator note and does not create
   * any quote/order.
   */
  @PostMapping("/api/v1/channels/rfq-handoffs/{id}/mark-converted")
  public ChannelRfqHandoffResponse markConverted(
      @PathVariable UUID id,
      @RequestBody(required = false) MarkConvertedRfqHandoffRequest request,
      HttpServletRequest http) {
    return handoffService.markConverted(
        id,
        request == null ? null : request.conversionNote(),
        trustedActor(http));
  }

  private UUID trustedActor(HttpServletRequest http) {
    UUID actorId = actorResolver.resolveVerifiedActor(http, TenantContext.requireTenantId());
    return RequestActorResolver.SYSTEM_ACTOR.equals(actorId) ? null : actorId;
  }

  private ChannelRfqHandoffStatus parseStatus(String status) {
    if (status == null || status.isBlank()) {
      return null;
    }
    try {
      return ChannelRfqHandoffStatus.valueOf(status.trim().toUpperCase());
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("Unknown RFQ handoff status: " + status);
    }
  }
}
