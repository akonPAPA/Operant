package com.orderpilot.security.abuse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.orderpilot.application.services.IntakeValidationService;
import com.orderpilot.application.services.ObjectStorageService;
import com.orderpilot.application.services.bot.BotFlowPolicyDecision;
import com.orderpilot.application.services.bot.BotRuntimePolicyService;
import com.orderpilot.application.services.channel.ChannelType;
import com.orderpilot.application.services.channel.WebhookVerificationMode;
import com.orderpilot.application.services.channel.WhatsAppSignatureVerifier;
import com.orderpilot.application.services.extraction.ExtractionOutputSanitizer;
import com.orderpilot.application.services.extraction.PromptInjectionGuardService;
import com.orderpilot.application.services.extraction.SemanticExtractionProvider.FieldCandidate;
import com.orderpilot.application.services.extraction.SemanticExtractionProvider.SemanticExtractionOutput;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.bot.BotFlow;
import com.orderpilot.domain.bot.BotFlowMode;
import com.orderpilot.domain.bot.BotFlowPolicyReason;
import com.orderpilot.domain.bot.InventoryFreshnessPolicy;
import com.orderpilot.domain.bot.PriceVisibilityPolicy;
import com.orderpilot.domain.bot.UnknownCustomerMode;
import com.orderpilot.domain.channel.ChannelBotRuntimeConfiguration;
import com.orderpilot.domain.intake.ObjectStorageRecord;
import com.orderpilot.domain.intake.ObjectStorageRecordRepository;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * OP-CAP-42E — abuse corpus foundation proof.
 *
 * <p>Drives the {@link AbuseCorpus} benign hostile-input samples through the <b>real</b> production
 * guards/services for the four hostile surfaces and asserts the safe boundary:
 * <ul>
 *   <li>(A) AI: prompt injection is flagged as suspicious <i>content only</i>; unsafe output
 *       fields/intents are rejected by the schema sanitizer; script markup is neutralized.
 *   <li>(B) Bot: hostile customer commands are flagged content; the bot policy decision is
 *       structurally incapable of granting approve/execute/connector/write authority and
 *       default-denies / withholds price.
 *   <li>(C) File/intake: traversal/extension/content-type/size metadata is rejected and traversal
 *       filenames cannot influence the storage path.
 *   <li>(D) Webhook: the Stage-10E verifier never falsely claims it verified a hostile payload and
 *       fails closed when production verification is required.
 *   <li>(E) Every rejection message is asserted free of internal/implementation/secret tokens.
 * </ul>
 *
 * <p>This complements (does not duplicate) {@code AiWorkerHostileFixtureStage39CTest} (intake-layer
 * fail-closed), {@code ChannelWebhookSecurityTest} (normalization-layer signature/replay/no-draft),
 * and {@code ErrorResponseLeakTest} (malformed-JSON error-body redaction).
 */
class AbuseCorpusFoundationTest {
  private static final Instant NOW = Instant.parse("2026-06-22T00:00:00Z");

  // Internal/implementation/secret tokens that must never appear in a rejection/result message.
  private static final String[] SENSITIVE_LEAK_TOKENS = {
      "java.", "org.springframework", "com.fasterxml.jackson", "jakarta.",
      "Hibernate", "SQLException", "PSQLException", "DataAccessException",
      "stackTrace", "at com.orderpilot", ".java:", "Caused by",
      "password", "secret", "credential", "token"
  };

  private final PromptInjectionGuardService promptGuard = new PromptInjectionGuardService();
  private final ExtractionOutputSanitizer sanitizer = new ExtractionOutputSanitizer();
  private final IntakeValidationService intakeValidation = new IntakeValidationService();
  private final BotRuntimePolicyService botPolicy = new BotRuntimePolicyService(null);
  private final WhatsAppSignatureVerifier whatsAppVerifier = new WhatsAppSignatureVerifier();

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  private void assertNoSensitiveLeak(String message) {
    for (String token : SENSITIVE_LEAK_TOKENS) {
      assertThat(message)
          .as("message must not leak sensitive/implementation token '%s'", token)
          .doesNotContain(token);
    }
  }

  // ============================================================================================
  // (A) AI unsafe output / prompt injection remains advisory / rejected.
  // ============================================================================================

  @Test
  void aiPromptInjectionSamplesAreAllFlaggedAsSuspiciousContentOnly() {
    for (AbuseCorpus.AbuseSample sample : AbuseCorpus.aiPromptInjectionSamples()) {
      List<String> markers = promptGuard.detect(sample.content());
      assertThat(markers)
          .as("prompt-injection sample %s must be flagged as suspicious content", sample.name())
          .isNotEmpty();
    }
  }

  @Test
  void unsafeAiOutputForbiddenFieldsAreRejectedBySchemaSanitizer() {
    for (AbuseCorpus.AbuseSample sample : AbuseCorpus.unsafeAiOutputForbiddenFields()) {
      SemanticExtractionOutput output = new SemanticExtractionOutput(
          "RFQ", "message", 0.8, List.of(), "email",
          List.of(new FieldCandidate(sample.content(), "yes", "yes", "command", 0.9, 0, 3)),
          List.of(), List.of());

      assertThatThrownBy(() -> sanitizer.validateProviderOutput(output))
          .as("forbidden AI output field %s must be rejected", sample.name())
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("unsupported field")
          .satisfies(ex -> {
            assertNoSensitiveLeak(ex.getMessage());
            // the rejection must not echo the attacker-supplied field name back verbatim
            assertThat(ex.getMessage()).doesNotContain(sample.content());
          });
    }
  }

  @Test
  void unsafeAiOutputPrivilegedIntentsAreRejected() {
    for (AbuseCorpus.AbuseSample sample : AbuseCorpus.unsafeAiOutputUnsupportedIntents()) {
      SemanticExtractionOutput output = new SemanticExtractionOutput(
          sample.content(), "message", 0.8, List.of(), "email", List.of(), List.of(), List.of());

      assertThatThrownBy(() -> sanitizer.validateProviderOutput(output))
          .as("privileged AI intent %s must be rejected", sample.name())
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("unsupported document intent")
          .satisfies(ex -> assertNoSensitiveLeak(ex.getMessage()));
    }
  }

  @Test
  void malformedAiOutputFailsClosedAtSchemaValidation() {
    SemanticExtractionOutput malformed = new SemanticExtractionOutput(
        null, "message", 0.1, List.of(), "email", List.of(), List.of(), List.of());

    assertThatThrownBy(() -> sanitizer.validateProviderOutput(malformed))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("schema validation")
        .satisfies(ex -> assertNoSensitiveLeak(ex.getMessage()));
  }

  @Test
  void aiScriptAndMarkupTextIsNeutralized() {
    for (AbuseCorpus.AbuseSample sample : AbuseCorpus.scriptAndMarkupText()) {
      String sanitized = sanitizer.sanitizeText(sample.content());
      assertThat(sanitized)
          .as("script/markup sample %s must be neutralized", sample.name())
          .doesNotContain("<script")
          .doesNotContain("javascript:");
    }
  }

  // ============================================================================================
  // (B) Bot hostile business command cannot execute a trusted mutation.
  // ============================================================================================

  @Test
  void botDecisionTypeStructurallyCannotGrantApproveExecuteOrExternalWriteAuthority() {
    // Durable structural proof: the bot policy decision has NO field through which approve/execute/
    // connector/external-write authority could ever be expressed. The bot is advisory/handoff only.
    for (RecordComponent component : BotFlowPolicyDecision.class.getRecordComponents()) {
      String name = component.getName().toLowerCase();
      assertThat(name)
          .as("BotFlowPolicyDecision must not expose an execution/approval authority field (%s)", name)
          .doesNotContain("approve")
          .doesNotContain("execute")
          .doesNotContain("connector")
          .doesNotContain("erp")
          .doesNotContain("write")
          .doesNotContain("commit")
          .doesNotContain("mutate")
          .doesNotContain("send");
    }
  }

  @Test
  void botExternalWriteAttemptsAreFlaggedAsSuspiciousContentOnly() {
    for (AbuseCorpus.AbuseSample sample : AbuseCorpus.botExternalWriteAttempts()) {
      assertThat(promptGuard.detect(sample.content()))
          .as("bot external-write attempt %s must be flagged as suspicious content", sample.name())
          .isNotEmpty();
    }
  }

  @Test
  void disabledBotDeniesHostileFlowAndCreatesNoDraftOrExecution() {
    ChannelBotRuntimeConfiguration config = config(c -> c.apply(false, true, true,
        BotFlowMode.OPERATOR_REVIEW_ONLY, BotFlowMode.OPERATOR_REVIEW_ONLY, BotFlowMode.OPERATOR_REVIEW_ONLY, BotFlowMode.DISABLED,
        UnknownCustomerMode.HANDOFF, true, "BOT_REVIEW", 1440, InventoryFreshnessPolicy.WARN_AND_HANDOFF,
        PriceVisibilityPolicy.IDENTIFIED_CUSTOMER_ONLY, "g", "f", "h", NOW));

    BotFlowPolicyDecision decision = botPolicy.decide(config, BotFlow.RFQ, BotRuntimePolicyService.Context.unidentified());

    assertThat(decision.allowed()).isFalse();
    assertThat(decision.mayCreateDraft()).isFalse();
    assertThat(decision.reasonCode()).isEqualTo(BotFlowPolicyReason.BOT_DISABLED);
  }

  @Test
  void botNeverExposesInternalPriceOrCostToHostileUnidentifiedRequest() {
    // bot_internal_cost_request / price probes: PriceVisibilityPolicy.NEVER withholds price entirely.
    ChannelBotRuntimeConfiguration config = config(c -> c.apply(true, true, true,
        BotFlowMode.DISABLED, BotFlowMode.OPERATOR_REVIEW_ONLY, BotFlowMode.OPERATOR_REVIEW_ONLY, BotFlowMode.DISABLED,
        UnknownCustomerMode.HANDOFF, true, "BOT_REVIEW", 1440, InventoryFreshnessPolicy.WARN_AND_HANDOFF,
        PriceVisibilityPolicy.NEVER, "g", "f", "h", NOW));

    BotFlowPolicyDecision decision = botPolicy.decide(config, BotFlow.PRICE, BotRuntimePolicyService.Context.unidentified());

    assertThat(decision.allowed()).isFalse();
    assertThat(decision.mayExposePrice()).isFalse();
    assertThat(decision.reasonCode()).isEqualTo(BotFlowPolicyReason.PRICE_VISIBILITY_NEVER);
  }

  // ============================================================================================
  // (C) File / intake hostile content does not become a trusted command / path.
  // ============================================================================================

  @Test
  void fileUnsafeExtensionsAreRejected() {
    for (AbuseCorpus.AbuseSample sample : AbuseCorpus.fileUnsafeExtensions()) {
      assertThatThrownBy(() -> intakeValidation.validateFile(sample.content(), "application/pdf", 1024L))
          .as("unsafe extension %s must be rejected", sample.name())
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Unsupported file extension")
          .satisfies(ex -> assertNoSensitiveLeak(ex.getMessage()));
    }
  }

  @Test
  void fileUnsupportedContentTypesAreRejected() {
    for (String contentType : AbuseCorpus.fileUnsupportedContentTypes()) {
      assertThatThrownBy(() -> intakeValidation.validateFile("invoice.pdf", contentType, 1024L))
          .as("unsupported content type %s must be rejected", contentType)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Unsupported content type")
          .satisfies(ex -> assertNoSensitiveLeak(ex.getMessage()));
    }
  }

  @Test
  void fileOversizeBoundaryIsRejectedSafely() {
    long oversize = IntakeValidationService.DEFAULT_MAX_FILE_BYTES + 1;

    assertThatThrownBy(() -> intakeValidation.validateFile("invoice.pdf", "application/pdf", oversize))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exceeds max size")
        .satisfies(ex -> assertNoSensitiveLeak(ex.getMessage()));
  }

  @Test
  void fileEmbeddedPromptInjectionTextIsTreatedAsUntrustedContentOnly() {
    for (AbuseCorpus.AbuseSample sample : AbuseCorpus.fileEmbeddedPromptInjectionText()) {
      assertThat(promptGuard.detect(sample.content()))
          .as("embedded prompt injection in %s must be flagged as suspicious content", sample.name())
          .isNotEmpty();
    }
  }

  @Test
  void fileMacroAndScriptMarkersStayInertText() {
    for (AbuseCorpus.AbuseSample sample : AbuseCorpus.fileMacroAndScriptMarkers()) {
      String sanitized = sanitizer.sanitizeText(sample.content());
      // script markup neutralized; a macro marker remains plain inert text (no execution path).
      assertThat(sanitized).doesNotContain("<script");
      assertThat(sanitized).isInstanceOf(String.class);
    }
  }

  @Test
  void traversalFilenameMetadataCannotInfluenceStoragePath(@TempDir Path storageRoot) {
    ObjectStorageRecordRepository repository = mock(ObjectStorageRecordRepository.class);
    when(repository.save(any(ObjectStorageRecord.class))).thenAnswer(inv -> inv.getArgument(0));
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    ObjectStorageService storage =
        new ObjectStorageService(repository, intakeValidation, clock, storageRoot.toString());
    UUID tenantId = UUID.fromString("22222222-2222-2222-2222-222222222222");
    TenantContext.setTenantId(tenantId);
    Path root = storageRoot.toAbsolutePath().normalize();

    for (AbuseCorpus.AbuseSample sample : AbuseCorpus.fileTraversalFilenamesWithAllowedExtension()) {
      ObjectStorageRecord record =
          storage.store(sample.content(), "application/pdf", "safe bytes".getBytes(StandardCharsets.UTF_8));

      // The hostile filename is retained only as inert display metadata; the storage KEY is built
      // from tenant/sha/random-object-id and contains no traversal sequence.
      assertThat(record.getObjectKey())
          .as("traversal filename %s must not leak into the storage key", sample.name())
          .startsWith(tenantId + "/")
          .doesNotContain("..")
          .doesNotContain("etc")
          .doesNotContain("\\");
      Path resolved = root.resolve(record.getObjectKey()).normalize();
      assertThat(resolved.startsWith(root)).isTrue();
    }
  }

  // ============================================================================================
  // (D) Webhook replay/forged/malformed payload is denied or never falsely "verified".
  // ============================================================================================

  @Test
  void stage10eVerifierNeverFalselyClaimsItVerifiedAHostileWebhookPayload() {
    for (AbuseCorpus.WebhookAbuseSample sample : AbuseCorpus.webhookSamples()) {
      var result = whatsAppVerifier.verify(sample.headers(), sample.rawBody(), ChannelType.WHATSAPP, UUID.randomUUID());

      // Honest current boundary: Stage-10E production signature verification is NOT configured, so the
      // verifier must report NOT_CONFIGURED (never a CONFIGURED/verified mode) for a hostile payload.
      assertThat(result.mode())
          .as("hostile webhook %s must not be reported as production-verified", sample.name())
          .isEqualTo(WebhookVerificationMode.NOT_CONFIGURED_STAGE_10E);
      assertNoSensitiveLeak(result.status());
    }
  }

  @Test
  void webhookFailsClosedWhenProductionSignatureVerificationIsRequired() {
    AbuseCorpus.WebhookAbuseSample probe = AbuseCorpus.webhookRequireSignatureProbe();

    var result = whatsAppVerifier.verify(probe.headers(), probe.rawBody(), ChannelType.WHATSAPP, UUID.randomUUID());

    assertThat(result.accepted()).isFalse();
    assertThat(result.mode()).isEqualTo(WebhookVerificationMode.FAILED);
    assertNoSensitiveLeak(result.status());
  }

  private ChannelBotRuntimeConfiguration config(java.util.function.Consumer<ChannelBotRuntimeConfiguration> mutator) {
    ChannelBotRuntimeConfiguration config = new ChannelBotRuntimeConfiguration(UUID.randomUUID(), UUID.randomUUID(), NOW);
    mutator.accept(config);
    return config;
  }
}
