package com.orderpilot.common.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdempotencyService {
  private static final Duration DEFAULT_TTL = Duration.ofHours(24);

  private final IdempotencyRecordRepository records;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  public IdempotencyService(IdempotencyRecordRepository records, ObjectMapper objectMapper, Clock clock) {
    this.records = records;
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  @Transactional
  public <T> T execute(
      UUID tenantId,
      UUID actorId,
      String idempotencyKey,
      String commandType,
      String targetResourceType,
      String targetResourceId,
      Object businessPayload,
      Class<T> responseType,
      Supplier<T> action) {
    String normalizedKey = requireKey(idempotencyKey);
    String keyHash = sha256(normalizedKey);
    String requestHash = requestHash(tenantId, actorId, commandType, targetResourceType, targetResourceId, businessPayload);
    IdempotencyRecord record;
    try {
      record = reserveOrReplay(tenantId, actorId, keyHash, requestHash, commandType, targetResourceType, targetResourceId, responseType);
    } catch (IdempotencyReplayException replay) {
      return responseType.cast(replay.response());
    }
    T response = action.get();
    record.markSucceeded(200, write(response), clock.instant());
    return response;
  }

  public String requestHash(UUID tenantId, UUID actorId, String commandType, String targetResourceType, String targetResourceId, Object businessPayload) {
    Map<String, Object> canonical = new LinkedHashMap<>();
    canonical.put("tenantId", tenantId == null ? "" : tenantId.toString());
    canonical.put("actorId", actorId == null ? "" : actorId.toString());
    canonical.put("commandType", text(commandType));
    canonical.put("targetResourceType", text(targetResourceType));
    canonical.put("targetResourceId", text(targetResourceId));
    canonical.put("payload", scrubMetadata(objectMapper.valueToTree(businessPayload == null ? Map.of() : businessPayload)));
    return sha256(write(canonical));
  }

  private <T> IdempotencyRecord reserveOrReplay(
      UUID tenantId,
      UUID actorId,
      String keyHash,
      String requestHash,
      String commandType,
      String targetResourceType,
      String targetResourceId,
      Class<T> responseType) {
    return records.findByTenantIdAndKeyHash(tenantId, keyHash)
        .map(existing -> handleExisting(existing, actorId, requestHash, responseType))
        .orElseGet(() -> reserve(tenantId, actorId, keyHash, requestHash, commandType, targetResourceType, targetResourceId, responseType));
  }

  private <T> IdempotencyRecord reserve(
      UUID tenantId,
      UUID actorId,
      String keyHash,
      String requestHash,
      String commandType,
      String targetResourceType,
      String targetResourceId,
      Class<T> responseType) {
    Instant now = clock.instant();
    try {
      return records.saveAndFlush(new IdempotencyRecord(
          tenantId,
          actorId,
          keyHash,
          requestHash,
          commandType,
          targetResourceType,
          targetResourceId,
          now,
          now.plus(DEFAULT_TTL)));
    } catch (DataIntegrityViolationException ex) {
      IdempotencyRecord existing = records.findByTenantIdAndKeyHash(tenantId, keyHash)
          .orElseThrow(() -> ex);
      return handleExisting(existing, actorId, requestHash, responseType);
    }
  }

  private <T> IdempotencyRecord handleExisting(IdempotencyRecord existing, UUID actorId, String requestHash, Class<T> responseType) {
    if (!Objects.equals(existing.getActorId(), actorId)) {
      throw new IdempotencyConflictException("Idempotency key is already bound to a different actor context");
    }
    if (!existing.getRequestHash().equals(requestHash)) {
      throw new IdempotencyConflictException("Idempotency key was reused with a different request");
    }
    if ("SUCCEEDED".equals(existing.getStatus())) {
      throw new IdempotencyReplayException(read(existing.getResponseBody(), responseType));
    }
    throw new IdempotencyInProgressException("Request with this idempotency key is already in progress");
  }

  private static String requireKey(String idempotencyKey) {
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      throw new IllegalArgumentException("Idempotency-Key header is required for this mutation");
    }
    String normalized = idempotencyKey.trim();
    if (normalized.length() > 180) {
      throw new IllegalArgumentException("Idempotency-Key must not exceed 180 characters");
    }
    return normalized;
  }

  private JsonNode scrubMetadata(JsonNode node) {
    if (node == null || node.isNull() || node.isValueNode()) {
      return node;
    }
    if (node.isArray()) {
      var array = objectMapper.createArrayNode();
      node.forEach(child -> array.add(scrubMetadata(child)));
      return array;
    }
    var object = objectMapper.createObjectNode();
    Map<String, JsonNode> fields = new TreeMap<>();
    node.fields().forEachRemaining(entry -> {
      String key = entry.getKey();
      if (!isMetadataKey(key)) {
        fields.put(key, entry.getValue());
      }
    });
    fields.forEach((key, value) -> object.set(key, scrubMetadata(value)));
    return object;
  }

  private static boolean isMetadataKey(String key) {
    return "idempotencyKey".equals(key)
        || "tenantId".equals(key)
        || "actorId".equals(key)
        || "actorRole".equals(key)
        || "csrfToken".equals(key)
        || "correlationId".equals(key)
        || "requestId".equals(key)
        || "timestamp".equals(key);
  }

  private <T> T read(String json, Class<T> type) {
    try {
      return objectMapper.readValue(json, type);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("Stored idempotency response is not readable", ex);
    }
  }

  private String write(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException ex) {
      throw new IllegalArgumentException("Unable to serialize idempotency payload", ex);
    }
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is required for idempotency hashing", ex);
    }
  }

  private static String text(String value) {
    return value == null ? "" : value;
  }

  private static final class IdempotencyReplayException extends RuntimeException {
    private final Object response;

    private IdempotencyReplayException(Object response) {
      this.response = response;
    }

    private Object response() {
      return response;
    }
  }
}
