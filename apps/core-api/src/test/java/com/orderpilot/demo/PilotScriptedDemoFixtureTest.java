package com.orderpilot.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * OP-CAP-11I Pilot Seed Dataset &amp; Scripted Demo Data Pack.
 *
 * <p>Validates the scripted demo scenario fixtures and the unsafe-input/bad-AI-output fixture as
 * pure data: structure, stable scenario codes mapped to the OP-CAP-11H demo scenario codes, no
 * secrets/tokens, and prompt-injection content that stays data only (never code/SQL/template/instruction).
 * No Spring context, no DB, no network, no AI provider.
 */
class PilotScriptedDemoFixtureTest {
  private static final String BASE = "demo/core-v1-demo/";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** Stable contract: must match the OP-CAP-11H {@code PilotDemoScenarioService} scenario codes. */
  private static final Set<String> EXPECTED_SCENARIO_CODES = Set.of(
      "TELEGRAM_RFQ_SUBSTITUTION",
      "PDF_PO_EXCEPTION",
      "DISCOUNT_MARGIN_GUARDRAIL",
      "INVENTORY_MISMATCH",
      "BAD_AI_OUTPUT_REJECTED");

  private static final List<String> REQUIRED_SCENARIO_FIELDS = List.of(
      "code", "title", "actor", "channel", "inputSample", "expectedInterpretation",
      "expectedValidationResult", "expectedExceptionOrApproval", "safetyBoundary",
      "demoRoute", "evidenceImpact", "knownLimitation");

  private static final List<String> NEW_FIXTURES = List.of(
      "scripted-scenarios-demo.json", "pdf-po-exception-demo.json", "bad-ai-output-demo.json");

  private static final List<String> FORBIDDEN_SECRET_MARKERS = List.of(
      "apikey", "api_key", "api-key", "secret", "token", "password", "passwd",
      "private key", "begin rsa", "bearer ", "client_secret", "webhook secret");

  @Test
  void scriptedScenarioFixtureParsesWithRequiredFieldsAndStableUniqueCodes() throws IOException {
    JsonNode root = read("scripted-scenarios-demo.json");
    assertThat(root.path("providerMode").asText()).isEqualTo("MOCK_ONLY");

    JsonNode scenarios = root.path("scenarios");
    assertThat(scenarios.isArray()).isTrue();
    assertThat(scenarios).hasSize(EXPECTED_SCENARIO_CODES.size());

    Set<String> seenCodes = new HashSet<>();
    for (JsonNode scenario : scenarios) {
      for (String field : REQUIRED_SCENARIO_FIELDS) {
        assertThat(scenario.hasNonNull(field))
            .as("scenario %s must have non-null field %s", scenario.path("code").asText(), field)
            .isTrue();
        assertThat(scenario.path(field).asText().isBlank())
            .as("scenario %s field %s must not be blank", scenario.path("code").asText(), field)
            .isFalse();
      }
      String code = scenario.path("code").asText();
      assertThat(seenCodes.add(code)).as("scenario code %s must be unique", code).isTrue();
    }
    assertThat(seenCodes).isEqualTo(EXPECTED_SCENARIO_CODES);
  }

  @Test
  void scriptedScenarioDemoRoutesMatchTheElevenHScenarioRoutes() throws IOException {
    JsonNode scenarios = read("scripted-scenarios-demo.json").path("scenarios");
    for (JsonNode scenario : scenarios) {
      String route = scenario.path("demoRoute").asText();
      assertThat(route).startsWith("/");
      // Routes are the same ones the 11H PilotDemoScenarioService suggests.
      switch (scenario.path("code").asText()) {
        case "TELEGRAM_RFQ_SUBSTITUTION" -> assertThat(route).isEqualTo("/bot-conversations");
        case "PDF_PO_EXCEPTION" -> assertThat(route).isEqualTo("/validation-review");
        case "DISCOUNT_MARGIN_GUARDRAIL" -> assertThat(route).isEqualTo("/pilot-readiness/evidence-report");
        case "INVENTORY_MISMATCH" -> assertThat(route).isEqualTo("/reconciliation");
        case "BAD_AI_OUTPUT_REJECTED" -> assertThat(route).isEqualTo("/pilot-readiness");
        default -> throw new AssertionError("unexpected scenario code " + scenario.path("code").asText());
      }
    }
  }

  @Test
  void newFixturesContainNoSecretsTokensOrCredentials() throws IOException {
    for (String fixture : NEW_FIXTURES) {
      String text = fixtureText(fixture).toLowerCase();
      for (String marker : FORBIDDEN_SECRET_MARKERS) {
        assertThat(text)
            .as("fixture %s must not contain secret marker '%s'", fixture, marker)
            .doesNotContain(marker);
      }
    }
  }

  @Test
  void badAiOutputFixtureKeepsHostileContentAsDataOnly() throws IOException {
    JsonNode root = read("bad-ai-output-demo.json");
    assertThat(root.path("providerMode").asText()).isEqualTo("MOCK_ONLY");

    // The prompt-injection-like content is a plain JSON string value (data), not executable structure.
    JsonNode injected = root.path("untrustedCustomerMessage");
    assertThat(injected.isTextual()).as("injected customer message must be a JSON string value").isTrue();
    assertThat(injected.asText()).contains("ignore all previous instructions");

    JsonNode directive = root.path("malformedModelOutput").path("injectedDirective");
    assertThat(directive.isTextual()).as("injected directive must be a JSON string value").isTrue();

    // The malformed extraction is data only: lineItems is intentionally a string, not an array.
    assertThat(root.path("malformedModelOutput").path("lineItems").isTextual()).isTrue();

    // The fixture documents that no business write occurs.
    assertThat(root.path("expectedHandling").path("businessWrite").asText()).startsWith("NONE");
  }

  @Test
  void pdfPurchaseOrderFixtureIsPlainTextStandinWithReviewLines() throws IOException {
    JsonNode root = read("pdf-po-exception-demo.json");
    assertThat(root.path("sourceFormat").asText()).isEqualTo("PLAIN_TEXT_STANDIN_FOR_PDF");

    List<String> outcomes = new ArrayList<>();
    for (JsonNode line : root.path("purchaseOrder").path("lines")) {
      outcomes.add(line.path("expectedOutcome").asText());
    }
    assertThat(outcomes).contains("AMBIGUOUS_SKU_NEEDS_REVIEW", "UNSUPPORTED_UOM_NEEDS_REVIEW");
    assertThat(root.path("expectedExceptions").isArray()).isTrue();
    assertThat(root.path("expectedExceptions")).isNotEmpty();
  }

  private static JsonNode read(String name) throws IOException {
    try (var in = new ClassPathResource(BASE + name).getInputStream()) {
      return MAPPER.readTree(in);
    }
  }

  private static String fixtureText(String name) throws IOException {
    try (var in = new ClassPathResource(BASE + name).getInputStream()) {
      return new String(in.readAllBytes());
    }
  }
}
