package com.orderpilot.application.services;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.orderpilot.api.dto.Stage2Dtos.CustomerRequest;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.customer.CustomerAccountRepository;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class CustomerAccountServiceTest {
  private final CustomerAccountRepository repository = mock(CustomerAccountRepository.class);
  private final AuditEventService auditEventService = mock(AuditEventService.class);
  private final CustomerAccountService service = new CustomerAccountService(repository, auditEventService, Clock.systemUTC());

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void preventsDuplicateCustomerAccountCodeWithinTenant() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    when(repository.existsByTenantIdAndAccountCodeAndDeletedAtIsNull(tenantId, "C-1")).thenReturn(true);

    assertThatThrownBy(() -> service.create(new CustomerRequest(null, "C-1", "Customer LLC", "Customer", null, "ACTIVE", "USD", null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("account code already exists");
  }
}