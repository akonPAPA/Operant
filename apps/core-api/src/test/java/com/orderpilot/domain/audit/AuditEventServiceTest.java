package com.orderpilot.domain.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@ActiveProfiles("test")
@Import({AuditEventService.class, CoreConfiguration.class})
class AuditEventServiceTest {
  @Autowired
  private AuditEventService auditEventService;

  @Autowired
  private AuditEventRepository auditEventRepository;

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void recordsAppendOnlyAuditEventWithTenant() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);

    AuditEvent event = auditEventService.record("tenant.created", "tenant", tenantId.toString(), null, "{\"source\":\"test\"}");

    assertThat(event.getId()).isNotNull();
    assertThat(event.getTenantId()).isEqualTo(tenantId);
    assertThat(event.getAction()).isEqualTo("tenant.created");
    assertThat(auditEventRepository.findAll()).hasSize(1);
  }
}