package com.orderpilot.aibot.infrastructure.web;

import com.orderpilot.aibot.api.BotManagementApi;
import com.orderpilot.aibot.api.model.AiJobResultResponse;
import com.orderpilot.aibot.api.model.AiJobStatusResponse;
import com.orderpilot.aibot.domain.exception.BotDefinitionNotFoundException;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.common.tenant.TenantContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ai-jobs")
public class AiJobController {
  private final BotManagementApi botManagementApi;

  public AiJobController(BotManagementApi botManagementApi) {
    this.botManagementApi = botManagementApi;
  }

  @GetMapping("/{jobPublicId}")
  public AiJobStatusResponse get(@PathVariable String jobPublicId) {
    try {
      return botManagementApi.getAiJob(TenantContext.requireTenantId(), jobPublicId);
    } catch (BotDefinitionNotFoundException ex) {
      throw new NotFoundException("ai_job_not_found");
    }
  }

  /** Advisory result retrieval for preview/generation polling. Returns a safe, leak-free view. */
  @GetMapping("/{jobPublicId}/result")
  public AiJobResultResponse getResult(@PathVariable String jobPublicId) {
    try {
      return botManagementApi.getAiJobResult(TenantContext.requireTenantId(), jobPublicId);
    } catch (BotDefinitionNotFoundException ex) {
      throw new NotFoundException("ai_job_not_found");
    }
  }
}
