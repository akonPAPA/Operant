package com.orderpilot.application.services;

import com.orderpilot.api.dto.Stage2Dtos.InventorySnapshotRequest;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.inventory.InventorySnapshot;
import com.orderpilot.domain.inventory.InventorySnapshotRepository;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InventorySnapshotService {
  private final InventorySnapshotRepository repository;
  private final AuditEventService auditEventService;
  private final Clock clock;

  public InventorySnapshotService(InventorySnapshotRepository repository, AuditEventService auditEventService, Clock clock) {
    this.repository = repository;
    this.auditEventService = auditEventService;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public List<InventorySnapshot> latest(UUID productId, UUID locationId) {
    UUID tenantId = TenantContext.requireTenantId();
    if (productId != null && locationId != null) return repository.findTop50ByTenantIdAndProductIdAndLocationIdOrderByCapturedAtDesc(tenantId, productId, locationId);
    if (productId != null) return repository.findTop50ByTenantIdAndProductIdOrderByCapturedAtDesc(tenantId, productId);
    return repository.findTop50ByTenantIdOrderByCapturedAtDesc(tenantId);
  }

  @Transactional
  public InventorySnapshot create(InventorySnapshotRequest request) {
    InventorySnapshot snapshot = new InventorySnapshot(TenantContext.requireTenantId(), request.productId(), request.locationId(), request.quantityOnHand(), request.quantityAvailable(), request.quantityReserved(), request.capturedAt() == null ? clock.instant() : request.capturedAt(), request.source(), request.importJobId(), clock.instant());
    InventorySnapshot saved = repository.save(snapshot);
    auditEventService.record("inventory_snapshot.created", "inventory_snapshot", saved.getId().toString(), null, "{\"source\":\"core-api\"}");
    return saved;
  }
}