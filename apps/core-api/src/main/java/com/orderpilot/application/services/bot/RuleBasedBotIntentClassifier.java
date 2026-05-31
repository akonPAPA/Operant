package com.orderpilot.application.services.bot;

import com.orderpilot.domain.bot.BotIntent;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class RuleBasedBotIntentClassifier {
  public BotIntent detect(String rawText) {
    String text = rawText == null ? "" : rawText.toLowerCase(Locale.ROOT);
    if (containsAny(text, "hello", "hi", "salam", "good morning", "good afternoon")) return BotIntent.GREETING;
    if (containsAny(text, "human", "operator", "manager", "help", "agent", "person")) return BotIntent.HUMAN_HELP_REQUEST;
    if (containsAny(text, "status", "order status", "where is my order")) return BotIntent.ORDER_STATUS_QUESTION;
    if (containsAny(text, "price", "cost", "how much")) return BotIntent.PRICE_QUESTION;
    if (containsAny(text, "substitute", "replacement", "alternative", "analog")) return BotIntent.SUBSTITUTE_QUESTION;
    if (containsAny(text, "available", "availability", "stock", "in stock", "warehouse")) return BotIntent.PRODUCT_AVAILABILITY_QUESTION;
    if (containsAny(text, "need", "quote", "rfq", "request", "commercial offer")) return BotIntent.RFQ_REQUEST;
    return BotIntent.UNKNOWN;
  }

  private boolean containsAny(String text, String... needles) {
    for (String needle : needles) {
      if (needle.contains(" ")) {
        if (text.contains(needle)) return true;
      } else if (java.util.regex.Pattern.compile("(^|[^a-z0-9])" + java.util.regex.Pattern.quote(needle) + "([^a-z0-9]|$)").matcher(text).find()) {
        return true;
      }
    }
    return false;
  }
}
