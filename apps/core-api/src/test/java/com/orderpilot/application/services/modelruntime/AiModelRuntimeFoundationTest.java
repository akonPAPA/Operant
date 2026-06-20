package com.orderpilot.application.services.modelruntime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class AiModelRuntimeFoundationTest {

  @Test
  void safeDefaultProviderIsDisabledAndSequential() {
    AiModelRuntimePolicy policy = AiModelRuntimeDefaults.disabled(AiModelRole.CODE_REVIEW);

    assertThat(policy.providerType()).isEqualTo(AiModelProviderType.DISABLED);
    assertThat(policy.enabled()).isFalse();
    assertThat(policy.sequentialExecution()).isTrue();
    assertThat(policy.heavyModel()).isFalse();
    AiModelRuntimeGuard.validate(policy);
  }

  @Test
  void defaultLocalReviewRotaExcludesHeavyModelAndRunsSequentially() {
    List<AiModelRuntimePolicy> rota = AiModelRuntimeDefaults.defaultLocalReviewRota();

    assertThat(rota).extracting(AiModelRuntimePolicy::modelId)
        .containsExactly("qwen3-coder:30b", "qwen3:30b")
        .doesNotContain("deepseek-r1:32b");
    assertThat(rota).allSatisfy(policy -> {
      assertThat(policy.sequentialExecution()).isTrue();
      assertThat(policy.heavyModel()).isFalse();
      assertThat(policy.providerType()).isEqualTo(AiModelProviderType.OLLAMA_LOCAL);
      AiModelRuntimeGuard.validate(policy);
    });
  }

  @Test
  void heavyModelIsOptionalDisabledAndCappedLowerThanDefault() {
    AiModelRuntimePolicy heavy = AiModelRuntimeDefaults.optionalHeavyLocalReviewer();

    assertThat(heavy.modelId()).isEqualTo("deepseek-r1:32b");
    assertThat(heavy.heavyModel()).isTrue();
    assertThat(heavy.enabled()).isFalse();
    assertThat(heavy.sequentialExecution()).isTrue();
    assertThat(heavy.maxContextTokens()).isLessThan(AiModelRuntimeDefaults.DEFAULT_MAX_CONTEXT_TOKENS);
    assertThat(heavy.maxOutputTokens()).isLessThan(AiModelRuntimeDefaults.DEFAULT_MAX_OUTPUT_TOKENS);
  }

  @Test
  void guardRejectsUnsafeModelActions() {
    assertThat(AiModelRuntimeGuard.isAdvisoryOnly(AiModelAdvisoryAction.FINDINGS)).isTrue();
    assertThat(AiModelRuntimeGuard.isAdvisoryOnly(AiModelAdvisoryAction.SUGGESTED_TESTS)).isTrue();

    assertThatThrownBy(() -> AiModelRuntimeGuard.rejectUnsafeAction(
        AiModelAdvisoryAction.APPROVE_CHANGE_REQUEST))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("no business write authority");
    assertThatThrownBy(() -> AiModelRuntimeGuard.rejectUnsafeAction(
        AiModelAdvisoryAction.EXECUTE_CONNECTOR))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> AiModelRuntimeGuard.rejectUnsafeAction(
        AiModelAdvisoryAction.OVERRIDE_TENANT_ACTOR_STATUS_APPROVAL_EXECUTION))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void advisoryOutputHasNoWriteAuthority() {
    AiModelAdvisoryOutput output = new AiModelAdvisoryOutput(
        List.of("check response DTO"),
        "LOW",
        List.of("add leak test"),
        "MEDIUM",
        List.of("verify with targeted tests"),
        "run code review");

    assertThat(output.advisoryOnly()).isTrue();
    assertThat(output.hasBusinessWriteAuthority()).isFalse();
    assertThat(output.canExecuteConnector()).isFalse();
  }

  @Test
  void remotePlaceholderCannotBeEnabledWithoutRealSafetyWork() {
    assertThatThrownBy(() -> new AiModelRuntimePolicy(
        AiModelRole.CODE_REVIEW,
        AiModelProviderType.REMOTE_PLACEHOLDER,
        "remote-model",
        4096,
        1000,
        java.time.Duration.ofSeconds(30),
        true,
        false,
        true))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("remote placeholder");
  }

  @Test
  void runMetadataCapturesFailureWithoutRawOutput() {
    Instant startedAt = Instant.parse("2026-06-20T00:00:00Z");
    Instant finishedAt = startedAt.plusSeconds(4);
    AiModelRunMetadata metadata = AiModelRunMetadata
        .planned(AiModelRuntimeDefaults.defaultLocalReviewRota().get(0), startedAt)
        .failed(finishedAt, "underlying connection closed after local Ollama crash with extra details");

    assertThat(metadata.status()).isEqualTo(AiModelRunStatus.FAILED);
    assertThat(metadata.duration()).isEqualTo(Duration.ofSeconds(4));
    assertThat(metadata.failureReason()).hasSizeLessThanOrEqualTo(80);
    assertThat(metadata.failureReason()).doesNotContain("prompt", "response", "secret");
  }
}
