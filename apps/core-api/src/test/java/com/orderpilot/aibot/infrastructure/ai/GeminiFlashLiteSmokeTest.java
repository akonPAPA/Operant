package com.orderpilot.aibot.infrastructure.ai;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Manually enabled smoke test. Disabled by default. Requires GEMINI_API_KEY and must use synthetic
 * text only.
 */
@Disabled("manual synthetic-only smoke; enable locally with GEMINI_API_KEY")
class GeminiFlashLiteSmokeTest {
  @Test
  void placeholder() {
    assumeTrue(System.getenv("GEMINI_API_KEY") != null && !System.getenv("GEMINI_API_KEY").isBlank());
  }
}
