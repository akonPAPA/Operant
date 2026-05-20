package com.orderpilot.application.services.bot;

import com.orderpilot.domain.bot.BotIntent;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class RuleBasedBotIntentClassifier {
  public BotIntent detect(String rawText) {
    String text = rawText == null ? "" : rawText.toLowerCase(Locale.ROOT);
    if (containsAny(text, "need", "quote", "rfq", "request", "нужно", "заявка", "коммерческое")) return BotIntent.RFQ_REQUEST;
    if (containsAny(text, "available", "stock", "in stock", "наличие", "есть в наличии", "склад")) return BotIntent.AVAILABILITY_CHECK;
    if (containsAny(text, "price", "cost", "цена", "сколько стоит")) return BotIntent.PRICE_CHECK;
    if (containsAny(text, "status", "order status", "статус", "где заказ")) return BotIntent.ORDER_STATUS;
    return BotIntent.UNKNOWN;
  }

  private boolean containsAny(String text, String... needles) {
    for (String needle : needles) {
      if (text.contains(needle)) return true;
    }
    return false;
  }
}
