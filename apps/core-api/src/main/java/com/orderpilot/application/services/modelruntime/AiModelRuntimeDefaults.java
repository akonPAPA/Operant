package com.orderpilot.application.services.modelruntime;

import java.time.Duration;
import java.util.List;

/** Safe built-in model-runtime policies for OP-CAP-39A. */
public final class AiModelRuntimeDefaults {
  public static final int DEFAULT_MAX_CONTEXT_TOKENS = 8192;
  public static final int DEFAULT_MAX_OUTPUT_TOKENS = 3000;
  public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(30);

  public static final int HEAVY_MAX_CONTEXT_TOKENS = 6144;
  public static final int HEAVY_MAX_OUTPUT_TOKENS = 1500;
  public static final Duration HEAVY_TIMEOUT = Duration.ofMinutes(15);

  private AiModelRuntimeDefaults() {}

  /** Production-safe default: no runnable provider. */
  public static AiModelRuntimePolicy disabled(AiModelRole role) {
    return new AiModelRuntimePolicy(
        role,
        AiModelProviderType.DISABLED,
        "disabled",
        DEFAULT_MAX_CONTEXT_TOKENS,
        DEFAULT_MAX_OUTPUT_TOKENS,
        DEFAULT_TIMEOUT,
        true,
        false,
        false);
  }

  /** Default local review rota from OP-CAP-38/COORD: two reliable 30B reviewers, sequential only. */
  public static List<AiModelRuntimePolicy> defaultLocalReviewRota() {
    return List.of(
        new AiModelRuntimePolicy(
            AiModelRole.CODE_REVIEW,
            AiModelProviderType.OLLAMA_LOCAL,
            "qwen3-coder:30b",
            DEFAULT_MAX_CONTEXT_TOKENS,
            DEFAULT_MAX_OUTPUT_TOKENS,
            DEFAULT_TIMEOUT,
            true,
            false,
            true),
        new AiModelRuntimePolicy(
            AiModelRole.PRODUCT_SECURITY_GATE,
            AiModelProviderType.OLLAMA_LOCAL,
            "qwen3:30b",
            DEFAULT_MAX_CONTEXT_TOKENS,
            DEFAULT_MAX_OUTPUT_TOKENS,
            DEFAULT_TIMEOUT,
            true,
            false,
            true));
  }

  /** Optional heavy reviewer. It is never part of the default rota. */
  public static AiModelRuntimePolicy optionalHeavyLocalReviewer() {
    return new AiModelRuntimePolicy(
        AiModelRole.BUSINESS_LOGIC_REVIEW,
        AiModelProviderType.OLLAMA_LOCAL,
        "deepseek-r1:32b",
        HEAVY_MAX_CONTEXT_TOKENS,
        HEAVY_MAX_OUTPUT_TOKENS,
        HEAVY_TIMEOUT,
        true,
        true,
        false);
  }
}
