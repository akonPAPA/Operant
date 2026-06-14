package com.orderpilot.domain.trust.learning;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * OP-CAP-18 Operator Correction Learning Loop. Tenant-scoped, bounded queries only.
 */
public interface OperatorCorrectionLearningRecordRepository
    extends JpaRepository<OperatorCorrectionLearningRecord, UUID> {
  Optional<OperatorCorrectionLearningRecord> findByIdAndTenantId(UUID id, UUID tenantId);

  List<OperatorCorrectionLearningRecord> findByTenantIdOrderByCreatedAtDesc(UUID tenantId, Pageable pageable);

  List<OperatorCorrectionLearningRecord> findByTenantIdAndStatusOrderByCreatedAtDesc(
      UUID tenantId, OperatorCorrectionStatus status, Pageable pageable);

  List<OperatorCorrectionLearningRecord> findByTenantIdAndCorrectionTypeOrderByCreatedAtDesc(
      UUID tenantId, OperatorCorrectionType correctionType, Pageable pageable);

  List<OperatorCorrectionLearningRecord> findByTenantIdAndStatusAndCorrectionTypeOrderByCreatedAtDesc(
      UUID tenantId, OperatorCorrectionStatus status, OperatorCorrectionType correctionType, Pageable pageable);
}
