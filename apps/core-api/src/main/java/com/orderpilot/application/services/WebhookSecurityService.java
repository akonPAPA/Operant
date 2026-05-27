package com.orderpilot.application.services;

import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.intake.WebhookEventRepository;
import org.springframework.stereotype.Service;

@Service
public class WebhookSecurityService {
  private final WebhookEventRepository repository;
  public WebhookSecurityService(WebhookEventRepository repository){this.repository=repository;}
  public boolean verifySignature(String provider, String payload, String signature){ return signature != null && !signature.isBlank(); }
  public boolean isReplay(String provider, String externalEventId){ return externalEventId != null && !externalEventId.isBlank() && repository.existsByTenantIdAndProviderAndExternalEventId(TenantContext.requireTenantId(), provider, externalEventId); }
}
