package com.orderpilot.aibot.infrastructure.web;

import com.orderpilot.aibot.api.BotManagementApi;
import com.orderpilot.aibot.api.model.AiJobAcceptedResponse;
import com.orderpilot.aibot.api.model.BotDefinitionVersionResponse;
import com.orderpilot.aibot.api.model.BotDraftResponse;
import com.orderpilot.aibot.api.model.CreateBotDraftRequest;
import com.orderpilot.aibot.api.model.GenerateBotDefinitionRequest;
import com.orderpilot.aibot.domain.exception.BotDefinitionNotFoundException;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.security.RequestActorResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/bots")
public class BotManagementController {
  private final BotManagementApi botManagementApi;
  private final RequestActorResolver actorResolver;

  public BotManagementController(BotManagementApi botManagementApi, RequestActorResolver actorResolver) {
    this.botManagementApi = botManagementApi;
    this.actorResolver = actorResolver;
  }

  @PostMapping
  public BotDraftResponse create(@RequestBody CreateBotDraftRequest request, HttpServletRequest http) {
    UUID tenantId = TenantContext.requireTenantId();
    return botManagementApi.createDraft(
        tenantId, actorResolver.resolveVerifiedActor(http, tenantId), request);
  }

  @PostMapping("/{botPublicId}/versions/{version}/generate")
  public ResponseEntity<AiJobAcceptedResponse> generate(
      @PathVariable String botPublicId,
      @PathVariable int version,
      @RequestBody GenerateBotDefinitionRequest request,
      HttpServletRequest http) {
    try {
      UUID tenantId = TenantContext.requireTenantId();
      AiJobAcceptedResponse body =
          botManagementApi.generate(
              tenantId,
              actorResolver.resolveVerifiedActor(http, tenantId),
              botPublicId,
              version,
              request);
      return ResponseEntity.status(HttpStatus.ACCEPTED).body(body);
    } catch (BotDefinitionNotFoundException ex) {
      throw new NotFoundException("bot_not_found");
    }
  }

  @GetMapping("/{botPublicId}/versions/{version}")
  public BotDefinitionVersionResponse getVersion(
      @PathVariable String botPublicId, @PathVariable int version) {
    try {
      return botManagementApi.getVersion(TenantContext.requireTenantId(), botPublicId, version);
    } catch (BotDefinitionNotFoundException ex) {
      throw new NotFoundException("bot_not_found");
    }
  }
}
