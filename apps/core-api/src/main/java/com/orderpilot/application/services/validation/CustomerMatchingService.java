package com.orderpilot.application.services.validation;

import com.orderpilot.application.services.JsonSupport;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.customer.CustomerAccount;
import com.orderpilot.domain.customer.CustomerAccountRepository;
import com.orderpilot.domain.extraction.ExtractedField;
import com.orderpilot.domain.validation.CustomerMatchResult;
import com.orderpilot.domain.validation.CustomerMatchResultRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CustomerMatchingService {
  private final CustomerAccountRepository customerRepository;
  private final CustomerMatchResultRepository resultRepository;
  private final ValidationIssueService issueService;
  private final ApprovalRequirementService approvalService;
  private final JsonSupport jsonSupport;
  private final Clock clock;

  public CustomerMatchingService(CustomerAccountRepository customerRepository, CustomerMatchResultRepository resultRepository, ValidationIssueService issueService, ApprovalRequirementService approvalService, JsonSupport jsonSupport, Clock clock) {
    this.customerRepository = customerRepository; this.resultRepository = resultRepository; this.issueService = issueService; this.approvalService = approvalService; this.jsonSupport = jsonSupport; this.clock = clock;
  }

  @Transactional
  public CustomerMatchResult match(UUID validationRunId, UUID extractionResultId, List<ExtractedField> fields) {
    UUID tenantId = TenantContext.requireTenantId();
    String hint = customerHint(fields);
    if (hint == null || hint.isBlank()) {
      issueService.open(validationRunId, extractionResultId, null, null, "CUSTOMER_NOT_FOUND", "ERROR", "No customer hint was present in extracted fields", "{}");
      return resultRepository.save(new CustomerMatchResult(tenantId, validationRunId, extractionResultId, null, null, "NONE", BigDecimal.ZERO, "NOT_FOUND", null, clock.instant()));
    }
    String normalized = normalize(hint);
    List<CustomerAccount> customers = customerRepository.findByTenantIdAndDeletedAtIsNullOrderByAccountCode(tenantId);
    List<CustomerAccount> exactCode = customers.stream().filter(c -> normalize(c.getAccountCode()).equals(normalized)).toList();
    if (exactCode.size() == 1) return save(tenantId, validationRunId, extractionResultId, exactCode.get(0), hint, "EXACT_CODE", "MATCHED", "0.9900", null);
    List<CustomerAccount> exactName = customers.stream().filter(c -> normalize(c.getLegalName()).equals(normalized) || normalize(c.getDisplayName()).equals(normalized)).toList();
    if (exactName.size() == 1) return save(tenantId, validationRunId, extractionResultId, exactName.get(0), hint, "EXACT_NAME", "MATCHED", "0.9500", null);
    List<CustomerAccount> fuzzy = customers.stream().filter(c -> normalize(c.getLegalName()).contains(normalized) || normalize(c.getDisplayName()).contains(normalized) || normalized.contains(normalize(c.getDisplayName()))).sorted(Comparator.comparing(CustomerAccount::getAccountCode)).limit(5).toList();
    if (fuzzy.size() == 1) return save(tenantId, validationRunId, extractionResultId, fuzzy.get(0), hint, "FUZZY_NAME", "NEEDS_REVIEW", "0.7000", candidates(fuzzy));
    if (exactCode.size() + exactName.size() + fuzzy.size() > 1) {
      issueService.open(validationRunId, extractionResultId, null, null, "CUSTOMER_AMBIGUOUS", "ERROR", "Customer hint matched multiple accounts", candidates(fuzzy.isEmpty() ? exactName : fuzzy));
      approvalService.create(validationRunId, null, "CUSTOMER_AMBIGUOUS", "HIGH", "Operator must choose the correct customer account");
      return resultRepository.save(new CustomerMatchResult(tenantId, validationRunId, extractionResultId, null, hint, "AMBIGUOUS", new BigDecimal("0.5000"), "AMBIGUOUS", candidates(fuzzy.isEmpty() ? exactName : fuzzy), clock.instant()));
    }
    issueService.open(validationRunId, extractionResultId, null, null, "CUSTOMER_NOT_FOUND", "ERROR", "Customer hint did not match a customer account", "{\"hint\":\"" + hint + "\"}");
    return resultRepository.save(new CustomerMatchResult(tenantId, validationRunId, extractionResultId, null, hint, "NONE", BigDecimal.ZERO, "NOT_FOUND", null, clock.instant()));
  }

  @Transactional(readOnly = true)
  public CustomerMatchResult get(UUID validationRunId) {
    return resultRepository.findFirstByTenantIdAndValidationRunId(TenantContext.requireTenantId(), validationRunId).orElse(null);
  }

  private CustomerMatchResult save(UUID tenantId, UUID runId, UUID resultId, CustomerAccount customer, String hint, String matchType, String status, String confidence, String candidatesJson) {
    return resultRepository.save(new CustomerMatchResult(tenantId, runId, resultId, customer.getId(), hint, matchType, new BigDecimal(confidence), status, candidatesJson, clock.instant()));
  }

  private String customerHint(List<ExtractedField> fields) {
    return fields.stream().filter(f -> {
      String name = normalize(f.getFieldName());
      return name.contains("CUSTOMER") || name.contains("ACCOUNT") || name.contains("BUYER") || name.contains("COMPANY");
    }).map(f -> f.getNormalizedValue() == null ? f.getRawValue() : f.getNormalizedValue()).filter(v -> v != null && !v.isBlank()).findFirst().orElse(null);
  }

  private String candidates(List<CustomerAccount> customers) {
    return jsonSupport.writeObject(Map.of("candidates", customers.stream().map(c -> Map.of("id", c.getId(), "accountCode", c.getAccountCode(), "displayName", c.getDisplayName())).toList()));
  }

  private String normalize(String value) {
    return value == null ? "" : value.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
  }
}
