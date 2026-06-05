package com.orderpilot.application.services.aiwork;

/**
 * OP-CAP-07A provider abstraction for generating advisory AI work content.
 *
 * <p>The first implementation is deterministic and provider-agnostic (see
 * {@link DeterministicAiWorkProvider}) so the layer is testable and demo-safe without any external
 * LLM dependency or secret. A real LLM-backed provider can implement this interface later without
 * changing the service, persistence, permission, or audit contracts.
 *
 * <p>Implementations are advisory only: they must never write business state, call connectors/ERP,
 * or be treated as a final ERP payload. All output is validated/owned downstream by the operator.
 */
public interface AiWorkProvider {
  AiWorkGenerationResult generate(AiWorkGenerationRequest request);

  /** Stable identifier of the generation strategy/prompt version, recorded on each suggestion. */
  String strategyVersion();
}
