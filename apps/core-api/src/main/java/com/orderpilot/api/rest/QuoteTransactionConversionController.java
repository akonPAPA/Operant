package com.orderpilot.api.rest;

import com.orderpilot.api.dto.Stage12BDtos.ChannelToQuoteRequest;
import com.orderpilot.api.dto.Stage12BDtos.ChannelToQuoteResponse;
import com.orderpilot.application.services.workspace.ChannelToQuoteWiringService;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.security.RequestActorResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/quote-transactions")
public class QuoteTransactionConversionController {
  private final ChannelToQuoteWiringService wiringService;
  private final RequestActorResolver actorResolver;

  public QuoteTransactionConversionController(ChannelToQuoteWiringService wiringService, RequestActorResolver actorResolver) {
    this.wiringService = wiringService;
    this.actorResolver = actorResolver;
  }

  @PostMapping("/from-channel-message/{messageId}")
  public ChannelToQuoteResponse fromChannelMessage(@PathVariable UUID messageId, @RequestBody(required = false) ChannelToQuoteRequest request, HttpServletRequest http) {
    return wiringService.createFromChannelMessage(messageId, request, operatorActor(http), "USER");
  }

  @PostMapping("/from-inbound-document/{documentId}")
  public ChannelToQuoteResponse fromInboundDocument(@PathVariable UUID documentId, @RequestBody(required = false) ChannelToQuoteRequest request, HttpServletRequest http) {
    return wiringService.createFromInboundDocument(documentId, request, operatorActor(http), "USER");
  }

  @PostMapping("/from-extraction/{extractionId}")
  public ChannelToQuoteResponse fromExtraction(@PathVariable UUID extractionId, @RequestBody(required = false) ChannelToQuoteRequest request, HttpServletRequest http) {
    return wiringService.createFromExtraction(extractionId, request, operatorActor(http), "USER");
  }

  @GetMapping("/conversion-attempts/{attemptId}")
  public ChannelToQuoteResponse conversionAttempt(@PathVariable UUID attemptId) {
    return wiringService.getAttempt(attemptId);
  }

  // OP-CAP-31: actor is resolved from the trusted (optionally signed) actor context, never from the
  // request body. The stable SYSTEM_ACTOR sentinel maps to a null operator id for audit attribution.
  private UUID operatorActor(HttpServletRequest http) {
    UUID actorId = actorResolver.resolveVerifiedActor(http, TenantContext.getTenantId().orElse(null));
    return RequestActorResolver.SYSTEM_ACTOR.equals(actorId) ? null : actorId;
  }
}
