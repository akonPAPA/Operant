package com.orderpilot.common.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

/**
 * F01 machine-enforced parity: Core's canonical Idempotency-Key grammar must equal the single-source
 * contract that the BFF also embeds
 * (apps/web-dashboard/lib/bff/idempotency-key-contract.json). If either side edits the grammar without
 * the other, this test fails — preventing the historical BFF/Core divergence (the BFF-only {@code ~}
 * character) from reappearing.
 */
class ClientIdempotencyKeyContractParityTest {

  private static final String CONTRACT_RELATIVE_PATH =
      "apps/web-dashboard/lib/bff/idempotency-key-contract.json";

  private static JsonNode loadContract() throws IOException {
    Path dir = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
    for (int i = 0; i < 8 && dir != null; i++) {
      Path candidate = dir.resolve(CONTRACT_RELATIVE_PATH);
      if (Files.isRegularFile(candidate)) {
        return new ObjectMapper().readTree(Files.readString(candidate));
      }
      dir = dir.getParent();
    }
    throw new IllegalStateException(
        "Could not locate single-source contract " + CONTRACT_RELATIVE_PATH + " from user.dir");
  }

  @Test
  void coreGrammarEqualsTheSingleSourceContract() throws IOException {
    JsonNode contract = loadContract();
    assertThat(ClientIdempotencyKey.CANONICAL_PATTERN).isEqualTo(contract.get("pattern").asText());
    assertThat(ClientIdempotencyKey.MAX_LENGTH).isEqualTo(contract.get("maxLength").asInt());
    assertThat(ClientIdempotencyKey.MIN_LENGTH).isEqualTo(contract.get("minLength").asInt());
  }

  @Test
  void normalizeAcceptsCanonicalKeysAndRejectsTheHistoricalTilde() {
    // Accepted canonical shapes (identical to what the BFF forwards).
    assertThat(ClientIdempotencyKey.normalize("op-key-123")).isEqualTo("op-key-123");
    assertThat(ClientIdempotencyKey.normalize("rfq-handoff-decision-abc.def:1"))
        .isEqualTo("rfq-handoff-decision-abc.def:1");
    assertThat(ClientIdempotencyKey.normalize("SENTINEL_IDEMPOTENCY_KEY"))
        .isEqualTo("SENTINEL_IDEMPOTENCY_KEY");

    // The BFF-only tilde must be rejected here (this was the divergence).
    assertThatThrownBy(() -> ClientIdempotencyKey.normalize("op~key"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void normalizeRejectsOversizedAndDisallowedCharactersAndTreatsBlankAsAbsent() {
    assertThatThrownBy(
            () -> ClientIdempotencyKey.normalize("x".repeat(ClientIdempotencyKey.MAX_LENGTH + 1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ClientIdempotencyKey.normalize("bad key with spaces"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ClientIdempotencyKey.normalize("op/key/slash"))
        .isInstanceOf(IllegalArgumentException.class);
    assertNull(ClientIdempotencyKey.normalize(null));
    assertNull(ClientIdempotencyKey.normalize("   "));
  }
}
