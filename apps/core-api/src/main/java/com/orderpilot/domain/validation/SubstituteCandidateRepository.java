package com.orderpilot.domain.validation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubstituteCandidateRepository extends JpaRepository<SubstituteCandidate, UUID> {
  List<SubstituteCandidate> findByTenantIdAndValidationRunIdOrderByRankScoreDesc(UUID tenantId, UUID validationRunId);
  Optional<SubstituteCandidate> findByIdAndTenantId(UUID id, UUID tenantId);
}
