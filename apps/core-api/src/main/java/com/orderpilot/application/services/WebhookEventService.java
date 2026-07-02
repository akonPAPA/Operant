package com.orderpilot.application.services;

import com.orderpilot.common.api.TenantScopedListLimits;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.intake.InboundEventLedger;
import com.orderpilot.domain.intake.InboundEventLedgerRepository;
import com.orderpilot.domain.intake.ObjectStorageRecord;
import com.orderpilot.domain.intake.WebhookEvent;
import com.orderpilot.domain.intake.WebhookEventRepository;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WebhookEventService {
  private final WebhookEventRepository repository; private final InboundEventLedgerRepository ledgerRepository; private final WebhookSecurityService securityService; private final ObjectStorageService storageService; private final Clock clock;
  public WebhookEventService(WebhookEventRepository repository, InboundEventLedgerRepository ledgerRepository, WebhookSecurityService securityService, ObjectStorageService storageService, Clock clock){this.repository=repository; this.ledgerRepository=ledgerRepository; this.securityService=securityService; this.storageService=storageService; this.clock=clock;}
  @Transactional public WebhookEvent record(String provider, String externalEventId, String payload, String headers, boolean signatureVerified) {
    UUID tenantId = TenantContext.requireTenantId(); ObjectStorageRecord stored = storageService.storeRawPayload(provider, externalEventId, payload); boolean replay = securityService.isReplay(provider, externalEventId); String status = replay ? "DUPLICATE" : "ACCEPTED"; WebhookEvent event = repository.save(new WebhookEvent(tenantId, provider, externalEventId, provider + "_WEBHOOK", stored.getSha256Fingerprint(), stored.getObjectKey(), signatureVerified, replay, payload, headers, status, clock.instant())); ledgerRepository.save(new InboundEventLedger(tenantId, provider, externalEventId, provider + "_WEBHOOK", stored.getSha256Fingerprint(), status, stored.getObjectKey(), clock.instant())); return event;
  }
  @Transactional(readOnly=true) public List<WebhookEvent> list(){ return list(TenantScopedListLimits.GENERAL_LIST_DEFAULT); }
  @Transactional(readOnly=true) public List<WebhookEvent> list(int limit){
    int clamped = TenantScopedListLimits.clamp(limit, TenantScopedListLimits.GENERAL_LIST_DEFAULT, TenantScopedListLimits.GENERAL_LIST_MAX);
    return repository.findByTenantIdOrderByReceivedAtDesc(TenantContext.requireTenantId(), PageRequest.of(0, clamped));
  }
  @Transactional(readOnly=true) public WebhookEvent get(UUID id){ return repository.findByIdAndTenantId(id, TenantContext.requireTenantId()).orElseThrow(() -> new IllegalArgumentException("Webhook event not found")); }
  @Transactional(readOnly=true) public List<InboundEventLedger> listLedger(){ return listLedger(TenantScopedListLimits.GENERAL_LIST_DEFAULT); }
  @Transactional(readOnly=true) public List<InboundEventLedger> listLedger(int limit){
    int clamped = TenantScopedListLimits.clamp(limit, TenantScopedListLimits.GENERAL_LIST_DEFAULT, TenantScopedListLimits.GENERAL_LIST_MAX);
    return ledgerRepository.findByTenantIdOrderByReceivedAtDesc(TenantContext.requireTenantId(), PageRequest.of(0, clamped));
  }
  @Transactional(readOnly=true) public InboundEventLedger getLedger(UUID id){ return ledgerRepository.findByIdAndTenantId(id, TenantContext.requireTenantId()).orElseThrow(() -> new IllegalArgumentException("Inbound event not found")); }
}
