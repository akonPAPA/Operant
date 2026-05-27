package com.orderpilot.application.services.validation;

import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.customer.CustomerAccount;
import com.orderpilot.domain.customer.CustomerAccountRepository;
import com.orderpilot.domain.extraction.ExtractedLineItem;
import com.orderpilot.domain.inventory.InventorySnapshot;
import com.orderpilot.domain.inventory.InventorySnapshotRepository;
import com.orderpilot.domain.validation.InventoryCheckResult;
import com.orderpilot.domain.validation.InventoryCheckResultRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InventoryValidationService {
  private final InventorySnapshotRepository inventoryRepository; private final CustomerAccountRepository customerRepository; private final InventoryCheckResultRepository resultRepository; private final ValidationIssueService issueService; private final Clock clock;
  public InventoryValidationService(InventorySnapshotRepository inventoryRepository, CustomerAccountRepository customerRepository, InventoryCheckResultRepository resultRepository, ValidationIssueService issueService, Clock clock) { this.inventoryRepository=inventoryRepository; this.customerRepository=customerRepository; this.resultRepository=resultRepository; this.issueService=issueService; this.clock=clock; }

  @Transactional
  public InventoryCheckResult check(UUID validationRunId, UUID extractionResultId, ExtractedLineItem line, UUID productId, UUID customerAccountId) {
    UUID tenantId = TenantContext.requireTenantId();
    BigDecimal requested = line.getNormalizedQuantity() == null ? BigDecimal.ONE : line.getNormalizedQuantity();
    if (productId == null) return save(tenantId, validationRunId, line.getId(), null, null, requested, null, null, null, "UNKNOWN_PRODUCT", null);
    UUID locationId = customerAccountId == null ? null : customerRepository.findByIdAndTenantIdAndDeletedAtIsNull(customerAccountId, tenantId).map(CustomerAccount::getDefaultLocationId).orElse(null);
    List<InventorySnapshot> snapshots = locationId == null ? inventoryRepository.findTop50ByTenantIdAndProductIdOrderByCapturedAtDesc(tenantId, productId) : inventoryRepository.findTop50ByTenantIdAndProductIdAndLocationIdOrderByCapturedAtDesc(tenantId, productId, locationId);
    if (snapshots.isEmpty()) {
      issueService.open(validationRunId, extractionResultId, line.getId(), null, "NEEDS_HUMAN_REVIEW", "WARNING", "No inventory snapshot exists for matched product", "{}");
      return save(tenantId, validationRunId, line.getId(), productId, locationId, requested, null, null, null, "NO_SNAPSHOT", null);
    }
    InventorySnapshot snapshot = snapshots.get(0);
    BigDecimal available = snapshot.getQuantityAvailable();
    String status = available.compareTo(BigDecimal.ZERO) <= 0 ? "OUT_OF_STOCK" : available.compareTo(requested) < 0 ? "LOW_STOCK" : "AVAILABLE";
    if ("OUT_OF_STOCK".equals(status)) issueService.open(validationRunId, extractionResultId, line.getId(), null, "OUT_OF_STOCK", "ERROR", "Requested product is out of stock", "{}");
    if ("LOW_STOCK".equals(status)) issueService.open(validationRunId, extractionResultId, line.getId(), null, "LOW_STOCK", "WARNING", "Requested quantity exceeds available stock", "{}");
    return save(tenantId, validationRunId, line.getId(), productId, snapshot.getLocationId(), requested, snapshot.getQuantityOnHand(), available, snapshot.getQuantityReserved(), status, snapshot.getId());
  }

  @Transactional(readOnly = true)
  public List<InventoryCheckResult> list(UUID validationRunId) { return resultRepository.findByTenantIdAndValidationRunId(TenantContext.requireTenantId(), validationRunId); }

  private InventoryCheckResult save(UUID tenantId, UUID runId, UUID lineId, UUID productId, UUID locationId, BigDecimal requested, BigDecimal onHand, BigDecimal available, BigDecimal reserved, String status, UUID snapshotId) {
    return resultRepository.save(new InventoryCheckResult(tenantId, runId, lineId, productId, locationId, requested, onHand, available, reserved, status, snapshotId, clock.instant()));
  }
}
