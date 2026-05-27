package com.orderpilot.application.services;

import com.orderpilot.api.dto.Stage2Dtos.DemoTenantRequest;
import com.orderpilot.api.dto.Stage2Dtos.DemoTenantResponse;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.tenant.Tenant;
import com.orderpilot.domain.tenant.TenantRepository;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TenantDemoDataService {
  private final TenantRepository tenantRepository;
  private final AuditEventService auditEventService;
  private final Clock clock;

  public TenantDemoDataService(TenantRepository tenantRepository, AuditEventService auditEventService, Clock clock) {
    this.tenantRepository = tenantRepository;
    this.auditEventService = auditEventService;
    this.clock = clock;
  }

  @Transactional
  public DemoTenantResponse createOrReuse(DemoTenantRequest request) {
    String slug = request.slug() == null || request.slug().isBlank() ? "demo-auto-industrial" : request.slug().trim();
    String legalName = request.legalName() == null || request.legalName().isBlank() ? "Demo Auto Industrial Parts LLC" : request.legalName().trim();
    return tenantRepository.findBySlug(slug)
        .map(tenant -> new DemoTenantResponse(tenant.getId(), tenant.getSlug(), tenant.getLegalName(), tenant.getStatus(), false))
        .orElseGet(() -> {
          Tenant tenant = tenantRepository.save(new Tenant(slug, legalName, "ACTIVE", clock.instant()));
          TenantContext.setTenantId(tenant.getId());
          auditEventService.record("tenant.demo_created", "tenant", tenant.getId().toString(), null, "{\"source\":\"seed-core-v1\"}");
          return new DemoTenantResponse(tenant.getId(), tenant.getSlug(), tenant.getLegalName(), tenant.getStatus(), true);
        });
  }
}
