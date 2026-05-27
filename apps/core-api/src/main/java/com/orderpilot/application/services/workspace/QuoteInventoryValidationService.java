package com.orderpilot.application.services.workspace;

import com.orderpilot.domain.inventory.InventorySnapshot;
import com.orderpilot.domain.inventory.InventorySnapshotRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QuoteInventoryValidationService {
  private final InventorySnapshotRepository repository;

  public QuoteInventoryValidationService(InventorySnapshotRepository repository) {
    this.repository = repository;
  }

  @Transactional(readOnly = true)
  public InventoryValidation validate(UUID tenantId, UUID productId, UUID locationId, BigDecimal requestedQuantity) {
    List<InventorySnapshot> snapshots = locationId == null
        ? repository.findTop50ByTenantIdAndProductIdOrderByCapturedAtDesc(tenantId, productId)
        : repository.findTop50ByTenantIdAndProductIdAndLocationIdOrderByCapturedAtDesc(tenantId, productId, locationId);
    if (snapshots.isEmpty()) {
      return new InventoryValidation(null, false, "NOT_EVALUATED");
    }
    BigDecimal available = snapshots.get(0).getQuantityAvailable();
    BigDecimal requested = requestedQuantity == null ? BigDecimal.ZERO : requestedQuantity;
    return new InventoryValidation(available, available.compareTo(requested) >= 0, available.compareTo(BigDecimal.ZERO) <= 0 ? "OUT_OF_STOCK" : "LOW_STOCK");
  }

  public record InventoryValidation(BigDecimal availableStock, boolean sufficient, String stockStatus) {}
}
