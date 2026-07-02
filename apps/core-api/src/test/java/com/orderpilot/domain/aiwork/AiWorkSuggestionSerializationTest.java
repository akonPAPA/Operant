package com.orderpilot.domain.aiwork;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AiWorkSuggestionSerializationTest {
  private final ObjectMapper mapper = new ObjectMapper()
      .registerModule(new JavaTimeModule())
      .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  @Test
  void accidentalEntitySerializationOmitsRawPayloadAndAuthorityFields() throws Exception {
    AiWorkSuggestion suggestion = new AiWorkSuggestion(
        UUID.randomUUID(),
        AiWorkType.REQUEST_SUMMARY,
        AiWorkSourceType.CHANNEL_MESSAGE,
        UUID.randomUUID(),
        "deterministic-v1",
        "LOW",
        new BigDecimal("0.5"),
        "summary",
        "{\"secret\":\"x\"}",
        "[{\"note\":\"y\"}]",
        "idem-key",
        UUID.randomUUID(),
        Instant.parse("2026-01-01T00:00:00Z"));

    String json = mapper.writeValueAsString(suggestion);
    assertThat(json).doesNotContain("structuredPayloadJson");
    assertThat(json).doesNotContain("evidenceRefsJson");
    assertThat(json).doesNotContain("idempotencyKey");
    assertThat(json).doesNotContain("createdByUserId");
    assertThat(json).doesNotContain("decidedByUserId");
    assertThat(json).doesNotContain("idem-key");
    assertThat(json).doesNotContain("secret");
  }
}
