package com.orderpilot.aibot.api;

import com.orderpilot.aibot.api.model.AiJobAcceptedResponse;
import com.orderpilot.aibot.api.model.AiJobStatusResponse;
import com.orderpilot.aibot.api.model.BotDefinitionVersionResponse;
import com.orderpilot.aibot.api.model.BotDraftResponse;
import com.orderpilot.aibot.api.model.CreateBotDraftRequest;
import com.orderpilot.aibot.api.model.GenerateBotDefinitionRequest;
import java.util.UUID;

public interface BotManagementApi {
  BotDraftResponse createDraft(UUID tenantId, UUID actorId, CreateBotDraftRequest request);

  AiJobAcceptedResponse generate(
      UUID tenantId, UUID actorId, String botPublicId, int version, GenerateBotDefinitionRequest request);

  BotDefinitionVersionResponse getVersion(UUID tenantId, String botPublicId, int version);

  AiJobStatusResponse getAiJob(UUID tenantId, String jobPublicId);
}
