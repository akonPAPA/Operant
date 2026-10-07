package com.orderpilot.aibot.domain.preview;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;

/**
 * Backend-owned catalogue of synthetic preview messages.
 *
 * <p>The bot preview flow labels its input {@code SYNTHETIC} and sends it to the external AI provider
 * under the {@code SYNTHETIC_ONLY} data policy. That label is only truthful if the backend — not the
 * caller — decides what counts as synthetic. Arbitrary operator free-text could contain real customer
 * data (PII), so it must never be blessed as synthetic and shipped to the provider. Only messages in
 * this fixed, code-owned catalogue are recognised as synthetic; everything else is rejected before any
 * job is created or any provider call is made.
 *
 * <p>Comparison is NFKC-normalised, trimmed, and case-insensitive so the fixed scenarios match the
 * same normalisation the preview service applies to inbound messages.
 */
public final class SyntheticPreviewFixtures {

  private SyntheticPreviewFixtures() {}

  /** Canonical synthetic preview scenarios (already normalised + lower-cased for matching). */
  private static final Set<String> FIXTURES =
      Set.of(
          "order status?",
          "what is the status of my order?",
          "where is my delivery?",
          "do you have this part in stock?",
          "can you check availability for part number abc-123?",
          "i need a quote for 10 units.",
          "can i speak to a human?");

  /** True only when {@code message} is one of the backend-owned synthetic fixtures. */
  public static boolean isSynthetic(String message) {
    if (message == null || message.isBlank()) {
      return false;
    }
    String key = Normalizer.normalize(message, Normalizer.Form.NFKC).trim().toLowerCase(Locale.ROOT);
    return FIXTURES.contains(key);
  }
}
