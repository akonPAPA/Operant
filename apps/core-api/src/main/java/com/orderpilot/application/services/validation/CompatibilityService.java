package com.orderpilot.application.services.validation;

import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.product.ProductCompatibilityRepository;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CompatibilityService {
  private final ProductCompatibilityRepository repository; private final ValidationIssueService issueService; private final ApprovalRequirementService approvalService;
  public CompatibilityService(ProductCompatibilityRepository repository, ValidationIssueService issueService, ApprovalRequirementService approvalService) { this.repository=repository; this.issueService=issueService; this.approvalService=approvalService; }
  @Transactional
  public void check(UUID runId, UUID extractionResultId, UUID lineId, UUID productId, Map<String, Object> extractedContext, boolean substitutionDependsOnCompatibility) {
    if (productId == null || !substitutionDependsOnCompatibility) return;
    boolean hasVehicleData = extractedContext.keySet().stream().map(String::toLowerCase).anyMatch(k -> k.contains("make") || k.contains("model") || k.contains("year") || k.contains("equipment"));
    if (!hasVehicleData) return;
    var matches = repository.findByTenantIdAndProductIdAndActiveTrue(TenantContext.requireTenantId(), productId);
    boolean highRisk = matches.stream().anyMatch(c -> "HIGH".equalsIgnoreCase(c.getRiskLevel()));
    if (matches.isEmpty()) issueService.open(runId, extractionResultId, lineId, null, "COMPATIBILITY_UNVERIFIED", "WARNING", "Compatibility data is not available for this substitution context", "{}");
    if (highRisk) {
      issueService.open(runId, extractionResultId, lineId, null, "SUBSTITUTE_HIGH_RISK", "ERROR", "Compatibility data marks this product as high risk", "{}");
      approvalService.create(runId, lineId, "SUBSTITUTE_REQUIRES_APPROVAL", "HIGH", "High-risk compatibility requires human approval");
    }
  }
}
