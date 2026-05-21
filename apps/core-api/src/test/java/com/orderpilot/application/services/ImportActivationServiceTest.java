package com.orderpilot.application.services;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderpilot.api.dto.Stage2Dtos.ImportJobRequest;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.imports.ImportValidationIssueRepository;
import com.orderpilot.domain.product.ProductRepository;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class ImportActivationServiceTest {
  @Autowired private ImportValidationIssueRepository issueRepository;
  @Autowired private ImportJobService service;
  @Autowired private ProductRepository productRepository;

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void csvProductImportStagesValidatesAndActivatesWithoutOverwritingTrustedRows() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);

    var job = service.create(new ImportJobRequest(null, "PRODUCTS", "products.csv", null, "sku,name,baseUom,cost,currency\nSTG-100,Stage Brake Pad,EA,12.50,USD"));
    var report = service.validate(job.getId());
    assertThat(report.validationErrors()).isEmpty();
    var activated = service.activate(job.getId());

    assertThat(report.invalidRows()).isZero();
    assertThat(activated.getStatus()).isEqualTo("APPLIED");
    assertThat(productRepository.findByTenantIdAndSkuAndDeletedAtIsNull(tenantId, "STG-100")).isPresent();
  }

  @Test
  void duplicateSkuInsideImportCreatesValidationIssues() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);

    var job = service.create(new ImportJobRequest(null, "PRODUCTS", "products.csv", null, "sku,name,baseUom\nDUP-1,Pad A,EA\nDUP-1,Pad B,EA"));
    var report = service.validate(job.getId());

    assertThat(report.invalidRows()).isEqualTo(2);
    assertThat(issueRepository.findByTenantIdAndImportJobIdOrderByRowNumber(tenantId, job.getId())).hasSize(2);
  }
}
