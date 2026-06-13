package com.orderpilot.application.services.reconciliation;

import com.orderpilot.api.dto.Stage8Dtos.ReconciliationCaseResponse;
import com.orderpilot.api.dto.Stage8Dtos.ReconciliationCasesResponse;
import com.orderpilot.api.dto.Stage8Dtos.ReconciliationRunResponse;
import com.orderpilot.api.dto.Stage8Dtos.Stage8InventoryMovementResponse;
import com.orderpilot.api.dto.Stage8Dtos.Stage8ProductTimelineResponse;
import com.orderpilot.api.dto.Stage8Dtos.Stage8ReconciliationRefreshResponse;
import com.orderpilot.api.dto.Stage8Dtos.Stage8ReconciliationSummaryResponse;
import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.application.services.runtime.RuntimeFeatureType;
import com.orderpilot.application.services.runtime.RuntimeGuardRequest;
import com.orderpilot.application.services.runtime.RuntimeGuardService;
import com.orderpilot.application.services.runtime.RuntimeOperationType;
import com.orderpilot.application.services.runtime.RuntimeUnitEstimateRequest;
import com.orderpilot.application.services.runtime.RuntimeUnitEstimator;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.inventory.InventorySnapshot;
import com.orderpilot.domain.inventory.InventorySnapshotRepository;
import com.orderpilot.domain.reconciliation.*;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InventoryReconciliationService {
  private static final Duration STALE_INVENTORY_AFTER = Duration.ofDays(7);
  private static final List<String> UNSUPPORTED_MOVEMENT_TYPES = List.of();
  private final InventoryMovementRepository movementRepository;
  private final ReconciliationCaseRepository caseRepository;
  private final InventorySnapshotRepository inventorySnapshotRepository;
  private final AuditEventService auditEventService;
  private final RuntimeGuardService runtimeGuardService;
  private final RuntimeUnitEstimator runtimeUnitEstimator;
  private final Clock clock;

  public InventoryReconciliationService(InventoryMovementRepository movementRepository, ReconciliationCaseRepository caseRepository, InventorySnapshotRepository inventorySnapshotRepository, AuditEventService auditEventService, RuntimeGuardService runtimeGuardService, RuntimeUnitEstimator runtimeUnitEstimator, Clock clock) {
    this.movementRepository = movementRepository;
    this.caseRepository = caseRepository;
    this.inventorySnapshotRepository = inventorySnapshotRepository;
    this.auditEventService = auditEventService;
    this.runtimeGuardService = runtimeGuardService;
    this.runtimeUnitEstimator = runtimeUnitEstimator;
    this.clock = clock;
  }

  @Transactional
  public ReconciliationRunResponse runInventoryReconciliation(UUID productId, UUID locationId) {
    if (productId == null || locationId == null) throw new IllegalArgumentException("productId and locationId are required");
    UUID tenantId = TenantContext.requireTenantId();
    Instant now = clock.instant();
    InventoryMovement actualCount = movementRepository.findFirstByTenantIdAndProductIdAndLocationIdAndMovementTypeOrderByOccurredAtDesc(tenantId, productId, locationId, InventoryMovementType.ACTUAL_STOCK_COUNT);
    if (actualCount == null) throw new IllegalArgumentException("No actual stock count exists for product/location");
    List<InventoryMovement> movements = movementRepository.findByTenantIdAndProductIdAndLocationIdAndOccurredAtLessThanEqualOrderByOccurredAtAsc(tenantId, productId, locationId, actualCount.getOccurredAt());
    BigDecimal expected = expectedStock(movements);
    BigDecimal actual = actualCount.getQuantity();
    BigDecimal mismatch = actual.subtract(expected);
    if (mismatch.compareTo(BigDecimal.ZERO) == 0) {
      return new ReconciliationRunResponse(tenantId, productId, locationId, expected, actual, mismatch, "NONE", "MATCHED", null, false, now);
    }

    ReconciliationSeverity severity = severity(expected, mismatch);
    String likelyCauses = likelyCauses(movements, actualCount, mismatch);
    ReconciliationCase reconciliationCase = caseRepository.findFirstByTenantIdAndProductIdAndLocationIdAndStatusInOrderByUpdatedAtDesc(tenantId, productId, locationId, Set.of(ReconciliationStatus.OPEN, ReconciliationStatus.INVESTIGATING))
        .orElse(null);
    boolean created = reconciliationCase == null;
    boolean materiallyChanged;
    if (created) {
      reconciliationCase = new ReconciliationCase(tenantId, productId, locationId, expected, actual, mismatch, severity, likelyCauses, now);
      materiallyChanged = true;
    } else {
      materiallyChanged = reconciliationCase.update(expected, actual, mismatch, severity, likelyCauses, now);
    }
    reconciliationCase = caseRepository.save(reconciliationCase);
    if (created || materiallyChanged) {
      auditEventService.record(created ? "RECONCILIATION_CASE_CREATED" : "RECONCILIATION_CASE_UPDATED", "RECONCILIATION_CASE", reconciliationCase.getId().toString(), null, "{\"productId\":\"" + productId + "\",\"locationId\":\"" + locationId + "\",\"mismatchQuantity\":\"" + mismatch + "\"}");
    }
    return new ReconciliationRunResponse(tenantId, productId, locationId, expected, actual, mismatch, severity.name(), reconciliationCase.getStatus().name(), reconciliationCase.getId(), created || materiallyChanged, now);
  }

  @Transactional(readOnly = true)
  public ReconciliationCasesResponse listCases(int page, int size) {
    int boundedSize = Math.min(Math.max(size, 1), 100);
    Pageable pageable = PageRequest.of(Math.max(page, 0), boundedSize);
    Page<ReconciliationCase> cases = caseRepository.findByTenantIdOrderByUpdatedAtDesc(TenantContext.requireTenantId(), pageable);
    return new ReconciliationCasesResponse(cases.getContent().stream().map(this::toResponse).toList(), cases.getNumber(), cases.getSize(), cases.getTotalElements(), cases.getTotalPages());
  }

  @Transactional(readOnly = true)
  public ReconciliationCaseResponse getCase(UUID caseId) {
    return toResponse(caseRepository.findByIdAndTenantId(caseId, TenantContext.requireTenantId()).orElseThrow(() -> new NotFoundException("Reconciliation case not found")));
  }

  @Transactional
  public ReconciliationCaseResponse updateCaseStatus(UUID caseId, String status) {
    ReconciliationStatus nextStatus = ReconciliationStatus.valueOf(status);
    ReconciliationCase reconciliationCase = caseRepository.findByIdAndTenantId(caseId, TenantContext.requireTenantId()).orElseThrow(() -> new NotFoundException("Reconciliation case not found"));
    reconciliationCase.setStatus(nextStatus, clock.instant());
    reconciliationCase = caseRepository.save(reconciliationCase);
    auditEventService.record("RECONCILIATION_CASE_STATUS_UPDATED", "RECONCILIATION_CASE", reconciliationCase.getId().toString(), null, "{\"status\":\"" + nextStatus + "\"}");
    return toResponse(reconciliationCase);
  }

  @Transactional(readOnly = true)
  public Stage8ReconciliationSummaryResponse summary() {
    UUID tenantId = TenantContext.requireTenantId();
    Instant staleBefore = clock.instant().minus(STALE_INVENTORY_AFTER);
    return new Stage8ReconciliationSummaryResponse(
        tenantId,
        caseRepository.countByTenantIdAndStatus(tenantId, ReconciliationStatus.OPEN),
        caseRepository.countByTenantIdAndSeverityAndStatus(tenantId, ReconciliationSeverity.HIGH, ReconciliationStatus.OPEN),
        inventorySnapshotRepository.countByTenantIdAndCapturedAtBefore(tenantId, staleBefore),
        inventorySnapshotRepository.countByTenantIdAndQuantityAvailableLessThanEqual(tenantId, BigDecimal.ZERO),
        caseRepository.countByTenantIdAndStatus(tenantId, ReconciliationStatus.OPEN),
        movementRepository.countByTenantId(tenantId),
        UNSUPPORTED_MOVEMENT_TYPES,
        clock.instant());
  }

  @Transactional(readOnly = true)
  public Stage8ProductTimelineResponse productTimeline(UUID productId) {
    UUID tenantId = TenantContext.requireTenantId();
    List<Stage8InventoryMovementResponse> movements = movementRepository.findTop100ByTenantIdAndProductIdOrderByOccurredAtDesc(tenantId, productId).stream()
        .map(movement -> new Stage8InventoryMovementResponse(movement.getId(), movement.getProductId(), movement.getLocationId(), movement.getMovementType().name(), movement.getQuantity(), movement.getOccurredAt(), movement.getSourceType(), movement.getSourceReference()))
        .toList();
    return new Stage8ProductTimelineResponse(tenantId, productId, movements, clock.instant());
  }

  @Transactional
  public Stage8ReconciliationRefreshResponse refreshProjections() {
    UUID tenantId = TenantContext.requireTenantId();
    // OP-CAP-16F runtime guard before bulk reconciliation case generation. The distinct
    // product/location pairs are the operation's own first read (not an extra estimation query);
    // requestedUnits are estimated from that already-known pair count. Entitlement + quota only (no
    // rate): a reconciliation refresh is an operator/scheduled bulk action, not a high-frequency hot
    // path. A denial throws a stable mapped exception (403) and creates/updates no reconciliation case.
    List<InventoryMovementRepository.ProductLocationPair> pairs = movementRepository.findDistinctProductLocationsByTenantIdAndMovementType(tenantId, InventoryMovementType.ACTUAL_STOCK_COUNT);
    int requestedUnits = runtimeUnitEstimator.estimate(
        RuntimeUnitEstimateRequest.forReconciliation(tenantId, pairs.size()));
    runtimeGuardService.enforceWithoutRate(
        RuntimeGuardRequest.of(tenantId, RuntimeOperationType.RECONCILIATION_RUN, requestedUnits),
        RuntimeFeatureType.RECONCILIATION_RUN);
    long beforeCases = caseRepository.count();
    long changed = 0;
    for (InventoryMovementRepository.ProductLocationPair pair : pairs) {
      ReconciliationRunResponse response = runInventoryReconciliation(pair.getProductId(), pair.getLocationId());
      if (response.discrepancyCreatedOrUpdated()) changed++;
    }
    long staleWarnings = createStaleInventoryWarnings(tenantId);
    long afterCases = caseRepository.count();
    auditEventService.record("RECONCILIATION_PROJECTION_REFRESHED", "RECONCILIATION", tenantId.toString(), null, "{\"pairsEvaluated\":" + pairs.size() + ",\"casesCreatedOrUpdated\":" + changed + ",\"staleInventoryWarnings\":" + staleWarnings + "}");
    return new Stage8ReconciliationRefreshResponse(tenantId, pairs.size(), changed + Math.max(0, afterCases - beforeCases), staleWarnings, false, false, clock.instant());
  }

  private BigDecimal expectedStock(List<InventoryMovement> movements) {
    BigDecimal expected = BigDecimal.ZERO;
    for (InventoryMovement movement : movements) {
      if (movement.getMovementType() == InventoryMovementType.ACTUAL_STOCK_COUNT) continue;
      BigDecimal quantity = movement.getQuantity();
      expected = switch (movement.getMovementType()) {
        case OPENING_STOCK, PURCHASE_RECEIVED, RETURN_IN, TRANSFER_IN -> expected.add(quantity);
        case SALE, RETURN_OUT, WRITE_OFF, TRANSFER_OUT -> expected.subtract(quantity);
        case MANUAL_ADJUSTMENT -> expected.add(quantity);
        case ACTUAL_STOCK_COUNT -> expected;
      };
    }
    return expected;
  }

  private ReconciliationSeverity severity(BigDecimal expected, BigDecimal mismatch) {
    BigDecimal absMismatch = mismatch.abs();
    if (expected.add(mismatch).compareTo(BigDecimal.ZERO) < 0 || absMismatch.compareTo(new BigDecimal("10")) >= 0) return ReconciliationSeverity.HIGH;
    if (absMismatch.compareTo(new BigDecimal("3")) >= 0) return ReconciliationSeverity.MEDIUM;
    return ReconciliationSeverity.LOW;
  }

  private long createStaleInventoryWarnings(UUID tenantId) {
    Instant staleBefore = clock.instant().minus(STALE_INVENTORY_AFTER);
    List<InventorySnapshot> staleSnapshots = inventorySnapshotRepository.findTop50ByTenantIdOrderByCapturedAtDesc(tenantId).stream()
        .filter(snapshot -> snapshot.getCapturedAt().isBefore(staleBefore))
        .toList();
    long warnings = 0;
    for (InventorySnapshot snapshot : staleSnapshots) {
      ReconciliationCase reconciliationCase = caseRepository.findFirstByTenantIdAndProductIdAndLocationIdAndStatusInOrderByUpdatedAtDesc(tenantId, snapshot.getProductId(), snapshot.getLocationId(), Set.of(ReconciliationStatus.OPEN, ReconciliationStatus.INVESTIGATING))
          .orElse(null);
      if (reconciliationCase == null) {
        caseRepository.save(new ReconciliationCase(tenantId, snapshot.getProductId(), snapshot.getLocationId(), snapshot.getQuantityOnHand(), snapshot.getQuantityOnHand(), BigDecimal.ZERO, ReconciliationSeverity.WARNING, "[\"STALE_INVENTORY_SNAPSHOT\"]", clock.instant()));
        warnings++;
      }
    }
    return warnings;
  }

  private String likelyCauses(List<InventoryMovement> movements, InventoryMovement actualCount, BigDecimal mismatch) {
    if (movements.stream().anyMatch(m -> m.getMovementType() == InventoryMovementType.MANUAL_ADJUSTMENT)) return "[\"MANUAL_ADJUSTMENT\"]";
    if (actualCount.getOccurredAt().isBefore(clock.instant().minus(STALE_INVENTORY_AFTER))) return "[\"STALE_INVENTORY_SNAPSHOT\"]";
    if (mismatch.compareTo(BigDecimal.ZERO) < 0) return "[\"MISSING_STOCK_MOVEMENT\",\"POSSIBLE_SHIPMENT_DISCREPANCY\"]";
    if (mismatch.compareTo(BigDecimal.ZERO) > 0) return "[\"UNLINKED_ORDER_OR_INVOICE\",\"MISSING_STOCK_MOVEMENT\"]";
    return "[\"UNKNOWN\"]";
  }

  private ReconciliationCaseResponse toResponse(ReconciliationCase reconciliationCase) {
    return new ReconciliationCaseResponse(reconciliationCase.getId(), reconciliationCase.getTenantId(), reconciliationCase.getProductId(), reconciliationCase.getLocationId(), reconciliationCase.getExpectedStock(), reconciliationCase.getActualStock(), reconciliationCase.getMismatchQuantity(), reconciliationCase.getSeverity().name(), reconciliationCase.getStatus().name(), reconciliationCase.getLikelyCauses(), reconciliationCase.getCalculatedAt(), reconciliationCase.getCreatedAt(), reconciliationCase.getUpdatedAt());
  }
}
