package com.orderpilot.application.services.modelruntime;

/**
 * Provider shape for a model runtime policy.
 *
 * <p>{@code REMOTE_PLACEHOLDER} is deliberately non-runnable in this foundation. It records future
 * intent without adding secrets, outbound provider calls, or production remote integration.
 */
public enum AiModelProviderType {
  DISABLED,
  OLLAMA_LOCAL,
  REMOTE_PLACEHOLDER
}
