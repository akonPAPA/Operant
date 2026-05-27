package com.orderpilot.application.services.bot;

import com.orderpilot.domain.bot.BotIntent;
import com.orderpilot.domain.bot.BotPolicyDecision;
import org.springframework.stereotype.Service;

@Service
public class BotPolicyService {
  public PolicyResult decide(BotIntent intent, boolean knownCustomerIdentity) {
    if ((intent == BotIntent.PRICE_QUESTION || intent == BotIntent.ORDER_STATUS_QUESTION) && !knownCustomerIdentity) {
      return new PolicyResult(BotPolicyDecision.REQUIRE_CUSTOMER_IDENTIFICATION, "UNKNOWN_CUSTOMER_IDENTITY", true, "verify customer identity before disclosure");
    }
    return switch (intent) {
      case RFQ_REQUEST -> new PolicyResult(BotPolicyDecision.REQUIRE_OPERATOR_REVIEW, "RFQ_REQUIRES_OPERATOR_REVIEW", true, "create RFQ request draft and route to operator review");
      case PRICE_QUESTION -> new PolicyResult(BotPolicyDecision.REQUIRE_OPERATOR_REVIEW, "PRICE_DISCLOSURE_REQUIRES_OPERATOR_REVIEW", true, "route to operator before price disclosure");
      case ORDER_STATUS_QUESTION -> new PolicyResult(BotPolicyDecision.REQUIRE_OPERATOR_REVIEW, "ORDER_STATUS_REQUIRES_OPERATOR_REVIEW", true, "route to operator before order-status disclosure");
      case PRODUCT_AVAILABILITY_QUESTION -> new PolicyResult(BotPolicyDecision.REQUIRE_OPERATOR_REVIEW, "AVAILABILITY_REQUIRES_VALIDATION", true, "route to validation/review before availability response");
      case SUBSTITUTE_QUESTION -> new PolicyResult(BotPolicyDecision.REQUIRE_OPERATOR_REVIEW, "SUBSTITUTE_REQUIRES_VALIDATION", true, "route to validation/review before substitute response");
      case HUMAN_HELP_REQUEST -> new PolicyResult(BotPolicyDecision.REQUIRE_HUMAN_HANDOFF, "HUMAN_REVIEW_REQUESTED", true, "create handoff");
      case UNKNOWN -> new PolicyResult(BotPolicyDecision.BLOCK_UNSUPPORTED, "UNKNOWN_OR_UNSUPPORTED_INTENT", true, "create handoff");
    };
  }

  public PolicyResult decideResponseDraft(BotIntent intent, boolean knownCustomerIdentity) {
    if ((intent == BotIntent.PRICE_QUESTION || intent == BotIntent.ORDER_STATUS_QUESTION) && !knownCustomerIdentity) {
      return new PolicyResult(BotPolicyDecision.REQUIRE_CUSTOMER_IDENTIFICATION, "UNKNOWN_CUSTOMER_IDENTITY", true, "verify customer identity before drafting a response");
    }
    return switch (intent) {
      case RFQ_REQUEST -> new PolicyResult(BotPolicyDecision.REQUIRE_OPERATOR_REVIEW, "RFQ_RESPONSE_REQUIRES_OPERATOR_REVIEW", true, "draft RFQ acknowledgement for operator review");
      case PRODUCT_AVAILABILITY_QUESTION -> new PolicyResult(BotPolicyDecision.REQUIRE_OPERATOR_REVIEW, "AVAILABILITY_RESPONSE_REQUIRES_OPERATOR_REVIEW", true, "operator must confirm product before availability response");
      case PRICE_QUESTION -> new PolicyResult(BotPolicyDecision.REQUIRE_OPERATOR_REVIEW, "PRICE_RESPONSE_REQUIRES_OPERATOR_REVIEW", true, "operator must review before price response");
      case SUBSTITUTE_QUESTION -> new PolicyResult(BotPolicyDecision.REQUIRE_OPERATOR_REVIEW, "SUBSTITUTE_RESPONSE_REQUIRES_OPERATOR_REVIEW", true, "operator must validate substitute before response");
      case ORDER_STATUS_QUESTION -> new PolicyResult(BotPolicyDecision.REQUIRE_CUSTOMER_IDENTIFICATION, "ORDER_STATUS_REQUIRES_CUSTOMER_IDENTIFICATION", true, "verify customer identity before order-status response");
      case HUMAN_HELP_REQUEST -> new PolicyResult(BotPolicyDecision.REQUIRE_HUMAN_HANDOFF, "HUMAN_REVIEW_REQUESTED", true, "create handoff");
      case UNKNOWN -> new PolicyResult(BotPolicyDecision.BLOCK_UNSUPPORTED, "UNKNOWN_OR_UNSUPPORTED_INTENT", true, "block unsupported response and create handoff");
    };
  }

  public record PolicyResult(BotPolicyDecision decision, String reasonCode, boolean requiresHumanReview, String suggestedNextAction) {}
}
