package com.operant.ctl;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.Iterator;
import java.util.Set;

final class ControlResponseValidator {
  private static final JsonFactory JSON_FACTORY = JsonFactory.builder()
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
      .streamReadConstraints(StreamReadConstraints.builder()
          .maxNestingDepth(32)
          .maxNumberLength(32)
          .maxStringLength(1024)
          .build())
      .build();
  private static final ObjectMapper JSON = JsonMapper.builder(JSON_FACTORY)
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
      .build();
  private static final int MAX_STRING_LENGTH = 128;
  private static final int MAX_DEPENDENCIES = 16;
  private static final int MAX_PROFILES = 16;
  private static final Set<String> DEPENDENCY_NAMES = Set.of("database", "redis");
  private static final Set<String> DEPENDENCY_STATES = Set.of("UP", "DOWN", "NOT_CONFIGURED");
  private static final Set<String> FORBIDDEN_FIELD_MARKERS = Set.of(
      "secret", "token", "credential", "authorization", "password", "tenant", "actor", "permission",
      "sourceid", "audit", "payload", "stack", "trace", "path", "url", "host", "port");

  private ControlResponseValidator() {}

  static ValidatedResponse validate(String command, String rawBody) {
    JsonNode root = parse(rawBody);
    if (!root.isObject()) {
      throw invalid();
    }
    rejectForbiddenFields(root);
    return switch (command) {
      case "status" -> validateStatus(root);
      case "health" -> validateHealth(root);
      case "readiness" -> validateReadiness(root);
      case "diagnose" -> validateDiagnostics(root);
      default -> throw invalid();
    };
  }

  private static ValidatedResponse validateStatus(JsonNode root) {
    requireFields(root, Set.of("version", "uptimeSeconds", "dependencies"));
    requireSafeString(root.get("version"));
    requireNonNegativeLong(root.get("uptimeSeconds"));
    validateDependencies(root.get("dependencies"));
    return normalized(root, true);
  }

  private static ValidatedResponse validateHealth(JsonNode root) {
    requireFields(root, Set.of("status"));
    if (!root.get("status").isTextual() || !"UP".equals(root.get("status").asText())) {
      throw invalid();
    }
    return normalized(root, true);
  }

  private static ValidatedResponse validateReadiness(JsonNode root) {
    requireFields(root, Set.of("ready", "dependencies"));
    if (!root.get("ready").isBoolean()) {
      throw invalid();
    }
    validateDependencies(root.get("dependencies"));
    return normalized(root, root.get("ready").asBoolean());
  }

  private static ValidatedResponse validateDiagnostics(JsonNode root) {
    requireFields(root, Set.of("version", "activeProfiles", "database", "redis", "jvm"));
    requireSafeString(root.get("version"));
    validateProfiles(root.get("activeProfiles"));
    JsonNode database = requireObject(root.get("database"));
    requireFields(database, Set.of("state", "migrationVersion"));
    requireDependencyState(database.get("state"));
    requireSafeString(database.get("migrationVersion"));
    JsonNode redis = requireObject(root.get("redis"));
    requireFields(redis, Set.of("configured", "state"));
    if (!redis.get("configured").isBoolean()) {
      throw invalid();
    }
    requireDependencyState(redis.get("state"));
    JsonNode jvm = requireObject(root.get("jvm"));
    requireFields(jvm, Set.of("heapUsedMb", "heapMaxMb"));
    requireNonNegativeLong(jvm.get("heapUsedMb"));
    requireNonNegativeLong(jvm.get("heapMaxMb"));
    return normalized(root, true);
  }

  private static JsonNode parse(String rawBody) {
    try {
      return JSON.readValue(rawBody, JsonNode.class);
    } catch (JsonProcessingException malformed) {
      throw invalid();
    }
  }

  private static void validateDependencies(JsonNode dependencies) {
    if (!dependencies.isArray() || dependencies.size() > MAX_DEPENDENCIES) {
      throw invalid();
    }
    java.util.HashSet<String> names = new java.util.HashSet<>();
    for (JsonNode dependency : dependencies) {
      JsonNode object = requireObject(dependency);
      requireFields(object, Set.of("name", "state"));
      String name = requireSafeString(object.get("name"));
      if (!DEPENDENCY_NAMES.contains(name) || !names.add(name)) {
        throw invalid();
      }
      requireDependencyState(object.get("state"));
    }
  }

  private static void validateProfiles(JsonNode profiles) {
    if (!profiles.isArray() || profiles.size() > MAX_PROFILES) {
      throw invalid();
    }
    for (JsonNode profile : profiles) {
      String value = requireSafeString(profile);
      if (!value.matches("[A-Za-z0-9_.-]{1,64}")) {
        throw invalid();
      }
    }
  }

  private static JsonNode requireObject(JsonNode node) {
    if (node == null || !node.isObject()) {
      throw invalid();
    }
    rejectForbiddenFields(node);
    return node;
  }

  private static void requireFields(JsonNode object, Set<String> expected) {
    java.util.HashSet<String> actual = new java.util.HashSet<>();
    Iterator<String> names = object.fieldNames();
    while (names.hasNext()) {
      actual.add(names.next());
    }
    if (!actual.equals(expected)) {
      throw invalid();
    }
  }

  private static String requireSafeString(JsonNode node) {
    if (node == null || !node.isTextual()) {
      throw invalid();
    }
    String value = node.asText();
    if (value.isBlank() || value.length() > MAX_STRING_LENGTH) {
      throw invalid();
    }
    for (int i = 0; i < value.length(); i++) {
      if (Character.isISOControl(value.charAt(i))) {
        throw invalid();
      }
    }
    return value;
  }

  private static void requireDependencyState(JsonNode node) {
    if (node == null || !node.isTextual() || !DEPENDENCY_STATES.contains(node.asText())) {
      throw invalid();
    }
  }

  private static void requireNonNegativeLong(JsonNode node) {
    if (node == null || !node.isIntegralNumber() || !node.canConvertToLong() || node.asLong() < 0) {
      throw invalid();
    }
  }

  private static void rejectForbiddenFields(JsonNode object) {
    Iterator<String> names = object.fieldNames();
    while (names.hasNext()) {
      String normalized = names.next().replaceAll("[^A-Za-z0-9]", "").toLowerCase(java.util.Locale.ROOT);
      for (String marker : FORBIDDEN_FIELD_MARKERS) {
        if (normalized.contains(marker)) {
          throw invalid();
        }
      }
    }
  }

  private static ValidatedResponse normalized(JsonNode root, boolean ready) {
    try {
      return new ValidatedResponse(JSON.writeValueAsString(root), ready);
    } catch (JsonProcessingException impossible) {
      throw invalid();
    }
  }

  private static InvalidControlResponseException invalid() {
    return new InvalidControlResponseException();
  }

  record ValidatedResponse(String normalizedJson, boolean ready) {}

  static final class InvalidControlResponseException extends RuntimeException {}
}