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
import java.util.regex.Pattern;

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
  private static final Pattern SAFE_VERSION_TOKEN = Pattern.compile("^(unknown|[A-Za-z0-9][A-Za-z0-9_.+-]{0,63})$");
  private static final int MAX_DEPENDENCIES = 2;
  private static final int MAX_PROFILES = 16;
  private static final Set<String> DEPENDENCY_NAMES = Set.of("database", "redis");
  private static final Set<String> DEPENDENCY_STATES = Set.of("UP", "DOWN", "NOT_CONFIGURED");
  private static final Set<String> EVENT_SEVERITIES = Set.of("ERROR", "WARN", "INFO");
  private static final Set<String> EVENT_CODES = Set.of("DEPENDENCY_STATE_CHANGED", "READINESS_STATE_CHANGED");
  private static final Set<String> EVENT_COMPONENTS = Set.of("DATABASE", "REDIS", "PLATFORM");
  private static final int MAX_EVENT_ENTRIES = 100;
  private static final int MAX_EVENT_SUMMARY_LENGTH = 200;
  private static final Pattern ISO_INSTANT =
      Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?Z$");
  private static final Pattern CURSOR_TOKEN = Pattern.compile("^\\d{1,19}$");
  private static final Pattern CORRELATION_TOKEN = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
  private static final Pattern INSTANCE_ID_TOKEN =
      Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
  private static final String EVENT_SCOPE = "LOCAL_PROCESS_RECENT_OPERATIONAL_EVENTS";
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
      case "operational-events" -> validateOperationalEvents(root);
      default -> throw invalid();
    };
  }

  private static ValidatedResponse validateOperationalEvents(JsonNode root) {
    requireFields(root, Set.of("events", "nextCursor", "hasMore", "returned", "maxLimit", "scope", "instanceId"));
    // Honest runtime scope: this surface is process-local and non-durable, marked by a fixed token.
    if (!EVENT_SCOPE.equals(requireSafeString(root.get("scope")))) {
      throw invalid();
    }
    if (!INSTANCE_ID_TOKEN.matcher(requireSafeString(root.get("instanceId"))).matches()) {
      throw invalid();
    }
    long maxLimit = requireNonNegativeLong(root.get("maxLimit"));
    if (maxLimit < 1 || maxLimit > MAX_EVENT_ENTRIES) {
      throw invalid();
    }
    if (!root.get("hasMore").isBoolean()) {
      throw invalid();
    }
    boolean hasMore = root.get("hasMore").asBoolean();
    JsonNode events = root.get("events");
    if (!events.isArray() || events.size() > maxLimit) {
      throw invalid();
    }
    long returned = requireNonNegativeLong(root.get("returned"));
    if (returned != events.size()) {
      throw invalid();
    }
    // nextCursor is present iff there are more (older) events - a symmetric server contract.
    JsonNode nextCursor = root.get("nextCursor");
    boolean cursorPresent;
    if (nextCursor.isNull()) {
      cursorPresent = false;
    } else {
      if (!CURSOR_TOKEN.matcher(requireSafeString(nextCursor)).matches()) {
        throw invalid();
      }
      cursorPresent = true;
    }
    if (hasMore != cursorPresent) {
      throw invalid();
    }
    for (JsonNode eventNode : events) {
      JsonNode event = requireObject(eventNode);
      requireFields(event, Set.of("occurredAt", "eventCode", "component", "severity", "summary", "correlationId"));
      requireIsoInstant(event.get("occurredAt"));
      requireAllowlisted(event.get("eventCode"), EVENT_CODES);
      requireAllowlisted(event.get("component"), EVENT_COMPONENTS);
      requireAllowlisted(event.get("severity"), EVENT_SEVERITIES);
      requireEventSummary(event.get("summary"));
      requireOptionalCorrelation(event.get("correlationId"));
    }
    return normalized(root, true);
  }

  private static void requireIsoInstant(JsonNode node) {
    if (!ISO_INSTANT.matcher(requireSafeString(node)).matches()) {
      throw invalid();
    }
  }

  private static void requireAllowlisted(JsonNode node, Set<String> allowed) {
    if (node == null || !node.isTextual() || !allowed.contains(node.asText())) {
      throw invalid();
    }
  }

  private static void requireEventSummary(JsonNode node) {
    if (node == null || !node.isTextual()) {
      throw invalid();
    }
    String value = node.asText();
    if (value.length() > MAX_EVENT_SUMMARY_LENGTH) {
      throw invalid();
    }
    for (int i = 0; i < value.length(); i++) {
      if (Character.isISOControl(value.charAt(i))) {
        throw invalid();
      }
    }
  }

  private static void requireOptionalCorrelation(JsonNode node) {
    if (node == null) {
      throw invalid();
    }
    if (node.isNull()) {
      return;
    }
    if (!node.isTextual() || !CORRELATION_TOKEN.matcher(node.asText()).matches()) {
      throw invalid();
    }
  }

  private static ValidatedResponse validateStatus(JsonNode root) {
    requireFields(root, Set.of("version", "uptimeSeconds", "dependencies"));
    requireSafeVersionToken(root.get("version"));
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
    boolean derivedReady = validateDependencies(root.get("dependencies"));
    if (root.get("ready").asBoolean() != derivedReady) {
      throw invalid();
    }
    return normalized(root, derivedReady);
  }

  private static ValidatedResponse validateDiagnostics(JsonNode root) {
    requireFields(root, Set.of("version", "activeProfiles", "database", "redis", "jvm"));
    requireSafeVersionToken(root.get("version"));
    validateProfiles(root.get("activeProfiles"));
    JsonNode database = requireObject(root.get("database"));
    requireFields(database, Set.of("state", "migrationVersion"));
    requireDependencyState(database.get("state"));
    requireSafeVersionToken(database.get("migrationVersion"));
    JsonNode redis = requireObject(root.get("redis"));
    requireFields(redis, Set.of("configured", "state"));
    if (!redis.get("configured").isBoolean()) {
      throw invalid();
    }
    String redisState = requireDependencyState(redis.get("state"));
    boolean redisConfigured = redis.get("configured").asBoolean();
    if ((!redisConfigured && !"NOT_CONFIGURED".equals(redisState))
        || (redisConfigured && "NOT_CONFIGURED".equals(redisState))) {
      throw invalid();
    }
    JsonNode jvm = requireObject(root.get("jvm"));
    requireFields(jvm, Set.of("heapUsedMb", "heapMaxMb"));
    long heapUsedMb = requireNonNegativeLong(jvm.get("heapUsedMb"));
    long heapMaxMb = requireNonNegativeLong(jvm.get("heapMaxMb"));
    if (heapMaxMb <= 0 || heapUsedMb > heapMaxMb) {
      throw invalid();
    }
    return normalized(root, true);
  }

  private static JsonNode parse(String rawBody) {
    try {
      return JSON.readValue(rawBody, JsonNode.class);
    } catch (JsonProcessingException malformed) {
      throw invalid();
    }
  }

  private static boolean validateDependencies(JsonNode dependencies) {
    if (!dependencies.isArray() || dependencies.size() != DEPENDENCY_NAMES.size()) {
      throw invalid();
    }
    java.util.HashSet<String> names = new java.util.HashSet<>();
    boolean ready = true;
    for (JsonNode dependency : dependencies) {
      JsonNode object = requireObject(dependency);
      requireFields(object, Set.of("name", "state"));
      String name = requireSafeString(object.get("name"));
      if (!DEPENDENCY_NAMES.contains(name) || !names.add(name)) {
        throw invalid();
      }
      String state = requireDependencyState(object.get("state"));
      if ("DOWN".equals(state)) {
        ready = false;
      }
    }
    if (!names.equals(DEPENDENCY_NAMES)) {
      throw invalid();
    }
    return ready;
  }

  private static void validateProfiles(JsonNode profiles) {
    if (!profiles.isArray() || profiles.size() > MAX_PROFILES) {
      throw invalid();
    }
    java.util.HashSet<String> values = new java.util.HashSet<>();
    for (JsonNode profile : profiles) {
      String value = requireSafeString(profile);
      if (!value.matches("[A-Za-z0-9_.-]{1,64}") || !values.add(value)) {
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

  private static String requireDependencyState(JsonNode node) {
    if (node == null || !node.isTextual() || !DEPENDENCY_STATES.contains(node.asText())) {
      throw invalid();
    }
    return node.asText();
  }

  private static long requireNonNegativeLong(JsonNode node) {
    if (node == null || !node.isIntegralNumber() || !node.canConvertToLong() || node.asLong() < 0) {
      throw invalid();
    }
    return node.asLong();
  }


  private static String requireSafeVersionToken(JsonNode node) {
    String value = requireSafeString(node);
    String lower = value.toLowerCase(java.util.Locale.ROOT);
    if (!SAFE_VERSION_TOKEN.matcher(value).matches()
        || value.contains("/")
        || value.contains("\\")
        || lower.contains("://")
        || lower.contains("jdbc")
        || lower.contains("redis")
        || lower.contains("postgres")
        || lower.contains("password")
        || lower.contains("secret")
        || lower.contains("token")) {
      throw invalid();
    }
    return value;
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
