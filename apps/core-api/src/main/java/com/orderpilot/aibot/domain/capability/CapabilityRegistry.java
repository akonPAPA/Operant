package com.orderpilot.aibot.domain.capability;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** Server-owned allowlist of bot capabilities. AI cannot invent keys. */
public final class CapabilityRegistry {

  private static final Set<BotCapabilityKey> ALL = EnumSet.allOf(BotCapabilityKey.class);

  private CapabilityRegistry() {}

  public static Set<BotCapabilityKey> all() {
    return EnumSet.copyOf(ALL);
  }

  public static Optional<BotCapabilityKey> resolve(String key) {
    if (key == null || key.isBlank()) {
      return Optional.empty();
    }
    String normalized = key.trim().toUpperCase(Locale.ROOT);
    if (normalized.contains("*") || normalized.contains("..")) {
      return Optional.empty();
    }
    try {
      return Optional.of(BotCapabilityKey.valueOf(normalized));
    } catch (IllegalArgumentException ex) {
      return Optional.empty();
    }
  }

  public static boolean isAllowed(String key) {
    return resolve(key).isPresent();
  }
}
