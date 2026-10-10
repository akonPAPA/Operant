package com.orderpilot.aibot.api;

import com.orderpilot.aibot.api.model.AiJobAcceptedResponse;
import com.orderpilot.aibot.api.model.PreviewBotMessageRequest;
import java.util.UUID;

public interface BotPreviewApi {
  AiJobAcceptedResponse preview(
      UUID tenantId, UUID actorId, String botPublicId, int version, PreviewBotMessageRequest request);
}
