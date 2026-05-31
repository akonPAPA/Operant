package com.orderpilot.api.rest;

import com.orderpilot.api.dto.Stage12BDtos.ChannelToQuoteRequest;
import com.orderpilot.api.dto.Stage12BDtos.ChannelToQuoteResponse;
import com.orderpilot.application.services.workspace.ChannelToQuoteWiringService;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/quote-transactions")
public class QuoteTransactionConversionController {
  private final ChannelToQuoteWiringService wiringService;

  public QuoteTransactionConversionController(ChannelToQuoteWiringService wiringService) {
    this.wiringService = wiringService;
  }

  @PostMapping("/from-channel-message/{messageId}")
  public ChannelToQuoteResponse fromChannelMessage(@PathVariable UUID messageId, @RequestBody(required = false) ChannelToQuoteRequest request) {
    return wiringService.createFromChannelMessage(messageId, request);
  }

  @PostMapping("/from-inbound-document/{documentId}")
  public ChannelToQuoteResponse fromInboundDocument(@PathVariable UUID documentId, @RequestBody(required = false) ChannelToQuoteRequest request) {
    return wiringService.createFromInboundDocument(documentId, request);
  }

  @PostMapping("/from-extraction/{extractionId}")
  public ChannelToQuoteResponse fromExtraction(@PathVariable UUID extractionId, @RequestBody(required = false) ChannelToQuoteRequest request) {
    return wiringService.createFromExtraction(extractionId, request);
  }

  @GetMapping("/conversion-attempts/{attemptId}")
  public ChannelToQuoteResponse conversionAttempt(@PathVariable UUID attemptId) {
    return wiringService.getAttempt(attemptId);
  }
}
