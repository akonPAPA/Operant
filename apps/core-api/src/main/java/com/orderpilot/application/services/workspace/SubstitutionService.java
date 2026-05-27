package com.orderpilot.application.services.workspace;

import com.orderpilot.api.dto.Stage12ADtos.SubstituteCandidate;
import com.orderpilot.application.services.ProductSubstitutionService;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SubstitutionService {
  private final ProductSubstitutionService productSubstitutionService;

  public SubstitutionService(ProductSubstitutionService productSubstitutionService) {
    this.productSubstitutionService = productSubstitutionService;
  }

  @Transactional(readOnly = true)
  public List<SubstituteCandidate> suggest(UUID tenantId, UUID lineId, UUID productId, String rawCode, String rawText, UUID customerAccountId, BigDecimal requestedQuantity) {
    return productSubstitutionService.suggest(tenantId, productId, rawCode, rawText, customerAccountId, requestedQuantity).stream()
        .map(candidate -> new SubstituteCandidate(lineId, candidate.productId(), candidate.sku(), candidate.productName(), candidate.riskLevel().name(), candidate.reasonCode(), candidate.availableStock(), candidate.stockStatus().name(), candidate.requiresApproval(), candidate.blocked(), candidate.customerAccepted(), candidate.explanation()))
        .toList();
  }
}
