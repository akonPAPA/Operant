package com.orderpilot.aibot.infrastructure.web;

import com.orderpilot.aibot.api.BotPreviewApi;
import com.orderpilot.aibot.api.model.AiJobAcceptedResponse;
import com.orderpilot.aibot.api.model.PreviewBotMessageRequest;
import com.orderpilot.aibot.domain.exception.BotDefinitionNotFoundException;
import com.orderpilot.aibot.domain.exception.BotDefinitionNotPreviewableException;
import com.orderpilot.aibot.domain.exception.BotPreviewInputRejectedException;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.security.RequestActorResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/bots")
public class BotPreviewController {
  private final BotPreviewApi botPreviewApi;
  private final RequestActorResolver actorResolver;

  public BotPreviewController(BotPreviewApi botPreviewApi, RequestActorResolver actorResolver) {
    this.botPreviewApi = botPreviewApi;
    this.actorResolver = actorResolver;
  }

  @PostMapping("/{botPublicId}/versions/{version}/preview")
  public ResponseEntity<AiJobAcceptedResponse> preview(
      @PathVariable String botPublicId,
      @PathVariable int version,
      @RequestBody PreviewBotMessageRequest request,
      HttpServletRequest http) {
    try {
      UUID tenantId = TenantContext.requireTenantId();
      AiJobAcceptedResponse accepted =
          botPreviewApi.preview(
              tenantId,
              actorResolver.resolveVerifiedActor(http, tenantId),
              botPublicId,
              version,
              request);
      return ResponseEntity.status(HttpStatus.ACCEPTED).body(accepted);
    } catch (BotDefinitionNotFoundException ex) {
      throw new NotFoundException("bot_not_found");
    } catch (BotDefinitionNotPreviewableException ex) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "bot_not_previewable");
    } catch (BotPreviewInputRejectedException ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "preview_input_rejected");
    }
  }
}
