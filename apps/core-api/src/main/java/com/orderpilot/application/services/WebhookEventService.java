package com.orderpilot.application.services;

import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.intake.WebhookEvent;
import com.orderpilot.domain.intake.WebhookEventRepository;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WebhookEventService {
  private final WebhookEventRepository repository; private final WebhookSecurityService securityService; private final Clock clock;
  public WebhookEventService(WebhookEventRepository repository, WebhookSecurityService securityService, Clock clock){this.repository=repository; this.securityService=securityService; this.clock=clock;}
  @Transactional public WebhookEvent record(String provider, String externalEventId, String payload, String headers, boolean signatureVerified) {
    UUID tenantId = TenantContext.getTenantId().orElse(null); boolean replay = securityService.isReplay(provider, externalEventId); String status = replay ? "DUPLICATE" : "ACCEPTED"; return repository.save(new WebhookEvent(tenantId, provider, externalEventId, signatureVerified, replay, payload, headers, status, clock.instant()));
  }
  @Transactional(readOnly=true) public List<WebhookEvent> list(){ return repository.findByTenantIdOrderByReceivedAtDesc(TenantContext.requireTenantId()); }
  @Transactional(readOnly=true) public WebhookEvent get(UUID id){ return repository.findByIdAndTenantId(id, TenantContext.requireTenantId()).orElseThrow(() -> new IllegalArgumentException("Webhook event not found")); }
}