package com.orderpilot.application.services.aiwork;

import com.orderpilot.domain.aiwork.AiWorkSourceType;
import com.orderpilot.domain.aiwork.AiWorkType;
import java.util.UUID;

/**
 * OP-CAP-07A input to an {@link AiWorkProvider}. The context text is operator/system-assembled
 * context about the source object; the provider must treat it as untrusted input and must never be
 * given secrets, tokens, or raw credentials (see docs/legal/AI_CODE_USAGE_POLICY.md).
 */
public record AiWorkGenerationRequest(
    UUID tenantId,
    AiWorkType workType,
    AiWorkSourceType sourceType,
    UUID sourceId,
    String contextText) {}
