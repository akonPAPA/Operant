package com.orderpilot.application.services.workspace;

import com.orderpilot.api.dto.Stage11ADtos.RfqLineInput;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RfqTextLineExtractor {
  private static final Pattern QUANTITY_PATTERN =
      Pattern.compile(
          "(?<![\\p{L}\\p{N}])(\\d+(?:[.,]\\d+)?)\\s*"
              + "(pcs|pc|units|unit|ea|шт|штука|штук|ед)"
              + "(?![\\p{L}\\p{N}])",
          Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

  private static final Pattern SKU_TOKEN_PATTERN =
      Pattern.compile(
          "(?<![\\p{L}\\p{N}])"
              + "(?=[\\p{L}\\p{N}_/-]*\\p{N})"
              + "(?=[\\p{L}\\p{N}_/-]*\\p{L})"
              + "[\\p{L}\\p{N}]{2,}(?:[-_/][\\p{L}\\p{N}]{2,})+"
              + "(?![\\p{L}\\p{N}])",
          Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

  private static final List<String> KNOWN_LOCATION_HINTS =
      List.of("Almaty", "Astana", "Алматы", "Астана");

  private RfqTextLineExtractor() {}

  public static List<RfqLineInput> extractSingleLine(String rawMessageText) {
    if (rawMessageText == null || rawMessageText.isBlank()) {
      return List.of();
    }

    String collapsed = rawMessageText.strip().replaceAll("\\s+", " ");
    QuantityHint quantityHint = detectQuantity(collapsed);
    String rawSku = detectSku(collapsed).orElse(null);
    String requestedLocation = detectLocation(collapsed).orElse(null);

    return List.of(
        new RfqLineInput(
            collapsed, rawSku, quantityHint.quantity(), quantityHint.uom(), requestedLocation));
  }

  private static QuantityHint detectQuantity(String text) {
    Matcher matcher = QUANTITY_PATTERN.matcher(text);
    if (!matcher.find()) {
      return new QuantityHint(BigDecimal.ONE, "EA");
    }
    return new QuantityHint(
        new BigDecimal(matcher.group(1).replace(",", ".")), normalizeUomForInput(matcher.group(2)));
  }

  private static Optional<String> detectSku(String text) {
    Matcher matcher = SKU_TOKEN_PATTERN.matcher(text.toUpperCase(Locale.ROOT));
    while (matcher.find()) {
      String candidate = matcher.group().trim();
      if (!candidate.isBlank()) {
        return Optional.of(candidate);
      }
    }
    return Optional.empty();
  }

  private static Optional<String> detectLocation(String text) {
    String lower = text.toLowerCase(Locale.ROOT);
    return KNOWN_LOCATION_HINTS.stream()
        .filter(location -> lower.contains(location.toLowerCase(Locale.ROOT)))
        .findFirst();
  }

  private static String normalizeUomForInput(String uom) {
    if (uom == null || uom.isBlank()) {
      return "EA";
    }
    String normalized = uom.trim().toLowerCase(Locale.ROOT);
    if (normalized.equals("pcs")
        || normalized.equals("pc")
        || normalized.equals("units")
        || normalized.equals("unit")
        || normalized.equals("шт")
        || normalized.equals("штука")
        || normalized.equals("штук")
        || normalized.equals("ед")
        || normalized.equals("ea")) {
      return "EA";
    }
    return uom.trim().toUpperCase(Locale.ROOT);
  }

  private record QuantityHint(BigDecimal quantity, String uom) {}
}
