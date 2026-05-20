package com.orderpilot.application.services;

import java.util.Locale;

public final class ProductCodeNormalizer {
  private ProductCodeNormalizer() {}

  public static String normalize(String value) {
    if (value == null) {
      return "";
    }
    return value.trim()
        .toUpperCase(Locale.ROOT)
        .replaceAll("\\s+", " ")
        .replaceAll("[-_ /]+", "");
  }
}
