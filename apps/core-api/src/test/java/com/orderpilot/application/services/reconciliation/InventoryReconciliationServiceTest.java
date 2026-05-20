package com.orderpilot.application.services.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderpilot.api.dto.Stage8Dtos.ReconciliationRunResponse;
import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.audit.AuditEventRepository;
import com.orderpilot.domain.customer.CustomerAccountRepository;
import com.orderpilot.domain.inventory.InventorySnapshotRepository;
import com.orderpilot.domain.pricing.PriceRuleRepository;
import com.orderpilot.domain.reconciliation.*;
import com.orderpilot.domain.workspace.DraftOrderRepository;
import com.orderpilot.domain.workspace.DraftQuoteRepository;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({InventoryReconciliationService.class, AuditEventService.class, CoreConfiguration.class})
class InventoryReconciliationServiceTest {
  @Autowired private InventoryReconciliationService service;
  @Autowired private InventoryMovementRepository movementRepository;
  @Autowired private ReconciliationCaseRepository caseRepository;
  @Autowired private AuditEventRepository auditEventRepository;
  @Autowired private DraftQuoteRepository draftQuoteRepository;
  @Autowired private DraftOrderRepository draftOrderRepository;
  @Autowired private InventorySnapshotRepository inventorySnapshotRepository;
  @Autowired private PriceRuleRepository priceRuleRepository;
  @Autowired private CustomerAccountRepository customerAccountRepository;

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void formulaHappyPathCreatesNoMismatchCase() {
    UUID tenantId = UUID.randomUUID();
    UUID productId = UUID.randomUUID();
    UUID locationId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    add(tenantId, productId, locationId, InventoryMovementType.OPENING_STOCK, "150", "2026-05-01T00:00:00Z");
    add(tenantId, productId, locationId, InventoryMovementType.SALE, "34", "2026-05-02T00:00:00Z");
    add(tenantId, productId, locationId, InventoryMovementType.ACTUAL_STOCK_COUNT, "116", "2026-05-03T00:00:00Z");

    ReconciliationRunResponse response = service.runInventoryReconciliation(productId, locationId);

    assertThat(response.expectedStock()).isEqualByComparingTo("116");
    assertThat(response.actualStock()).isEqualByComparingTo("116");
    assertThat(response.mismatchQuantity()).isEqualByComparingTo("0");
    assertThat(response.reconciliationCaseId()).isNull();
    assertThat(caseRepository.count()).isZero();
  }

  @Test
  void mismatchPathCreatesHighSeverityCaseAndAuditEvent() {
    UUID tenantId = UUID.randomUUID();
    UUID productId = UUID.randomUUID();
    UUID locationId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    add(tenantId, productId, locationId, InventoryMovementType.OPENING_STOCK, "150", "2026-05-01T00:00:00Z");
    add(tenantId, productId, locationId, InventoryMovementType.SALE, "34", "2026-05-02T00:00:00Z");
    add(tenantId, productId, locationId, InventoryMovementType.ACTUAL_STOCK_COUNT, "100", "2026-05-03T00:00:00Z");

    ReconciliationRunResponse response = service.runInventoryReconciliation(productId, locationId);

    assertThat(response.expectedStock()).isEqualByComparingTo("116");
    assertThat(response.actualStock()).isEqualByComparingTo("100");
    assertThat(response.mismatchQuantity()).isEqualByComparingTo("-16");
    assertThat(response.severity()).isEqualTo("HIGH");
    assertThat(response.reconciliationCaseId()).isNotNull();
    assertThat(caseRepository.count()).isEqualTo(1);
    assertThat(auditEventRepository.findAll()).extracting("action").contains("RECONCILIATION_CASE_CREATED");
  }

  @Test
  void movementTypesAdjustExpectedStockDeterministically() {
    UUID tenantId = UUID.randomUUID();
    UUID productId = UUID.randomUUID();
    UUID locationId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    add(tenantId, productId, locationId, InventoryMovementType.OPENING_STOCK, "100", "2026-05-01T00:00:00Z");
    add(tenantId, productId, locationId, InventoryMovementType.PURCHASE_RECEIVED, "25", "2026-05-02T00:00:00Z");
    add(tenantId, productId, locationId, InventoryMovementType.RETURN_IN, "5", "2026-05-03T00:00:00Z");
    add(tenantId, productId, locationId, InventoryMovementType.SALE, "30", "2026-05-04T00:00:00Z");
    add(tenantId, productId, locationId, InventoryMovementType.WRITE_OFF, "4", "2026-05-05T00:00:00Z");
    add(tenantId, productId, locationId, InventoryMovementType.MANUAL_ADJUSTMENT, "-2", "2026-05-06T00:00:00Z");
    add(tenantId, productId, locationId, InventoryMovementType.MANUAL_ADJUSTMENT, "3", "2026-05-07T00:00:00Z");
    add(tenantId, productId, locationId, InventoryMovementType.ACTUAL_STOCK_COUNT, "97", "2026-05-08T00:00:00Z");

    ReconciliationRunResponse response = service.runInventoryReconciliation(productId, locationId);

    assertThat(response.expectedStock()).isEqualByComparingTo("97");
    assertThat(response.mismatchQuantity()).isEqualByComparingTo("0");
  }

  @Test
  void tenantIsolationPreventsCrossTenantMovementsFromAffectingResult() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    UUID productId = UUID.randomUUID();
    UUID locationId = UUID.randomUUID();
    add(tenantA, productId, locationId, InventoryMovementType.OPENING_STOCK, "150", "2026-05-01T00:00:00Z");
    add(tenantA, productId, locationId, InventoryMovementType.ACTUAL_STOCK_COUNT, "150", "2026-05-03T00:00:00Z");
    add(tenantB, productId, locationId, InventoryMovementType.SALE, "50", "2026-05-02T00:00:00Z");
    TenantContext.setTenantId(tenantA);

    ReconciliationRunResponse response = service.runInventoryReconciliation(productId, locationId);

    assertThat(response.expectedStock()).isEqualByComparingTo("150");
    assertThat(response.mismatchQuantity()).isEqualByComparingTo("0");
    assertThat(service.listCases(0, 50).totalElements()).isZero();
  }

  @Test
  void reconciliationDoesNotCreateQuotesOrdersOrMutateMasterData() {
    UUID tenantId = UUID.randomUUID();
    UUID productId = UUID.randomUUID();
    UUID locationId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    add(tenantId, productId, locationId, InventoryMovementType.OPENING_STOCK, "150", "2026-05-01T00:00:00Z");
    add(tenantId, productId, locationId, InventoryMovementType.SALE, "34", "2026-05-02T00:00:00Z");
    add(tenantId, productId, locationId, InventoryMovementType.ACTUAL_STOCK_COUNT, "100", "2026-05-03T00:00:00Z");
    long quotes = draftQuoteRepository.count();
    long orders = draftOrderRepository.count();
    long inventory = inventorySnapshotRepository.count();
    long prices = priceRuleRepository.count();
    long customers = customerAccountRepository.count();

    service.runInventoryReconciliation(productId, locationId);

    assertThat(draftQuoteRepository.count()).isEqualTo(quotes);
    assertThat(draftOrderRepository.count()).isEqualTo(orders);
    assertThat(inventorySnapshotRepository.count()).isEqualTo(inventory);
    assertThat(priceRuleRepository.count()).isEqualTo(prices);
    assertThat(customerAccountRepository.count()).isEqualTo(customers);
  }

  private void add(UUID tenantId, UUID productId, UUID locationId, InventoryMovementType type, String quantity, String occurredAt) {
    movementRepository.save(new InventoryMovement(tenantId, productId, locationId, type, new BigDecimal(quantity), Instant.parse(occurredAt), "TEST", type.name(), Instant.parse(occurredAt)));
  }
}
