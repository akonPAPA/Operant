package com.orderpilot.application.services;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderpilot.api.dto.Stage2Dtos.ImportJobRequest;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.audit.AuditEventRepository;
import com.orderpilot.domain.imports.ImportValidationIssueRepository;
import com.orderpilot.domain.customer.CustomerAccountRepository;
import com.orderpilot.domain.inventory.InventorySnapshotRepository;
import com.orderpilot.domain.location.LocationRepository;
import com.orderpilot.domain.pricing.PriceRuleRepository;
import com.orderpilot.domain.product.ProductAliasRepository;
import com.orderpilot.domain.product.ProductRepository;
import com.orderpilot.domain.product.ProductSubstituteRepository;
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
  @Autowired private CustomerAccountRepository customerAccountRepository;
  @Autowired private LocationRepository locationRepository;
  @Autowired private InventorySnapshotRepository inventorySnapshotRepository;
  @Autowired private ProductAliasRepository productAliasRepository;
  @Autowired private PriceRuleRepository priceRuleRepository;
  @Autowired private ProductSubstituteRepository productSubstituteRepository;
  @Autowired private AuditEventRepository auditEventRepository;

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

  @Test
  void missingRequiredColumnReturnsValidationError() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);

    var job = service.create(new ImportJobRequest(null, "PRODUCTS", "products.csv", null, "sku,baseUom\nBAD-1,EA"));
    var report = service.validate(job.getId());

    assertThat(report.invalidRows()).isEqualTo(1);
    assertThat(report.validationErrors()).singleElement()
        .satisfies(error -> assertThat(error.errors()).contains("name is required"));
  }

  @Test
  void badNumericValueReturnsValidationError() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);

    var job = service.create(new ImportJobRequest(null, "PRODUCTS", "products.csv", null, "sku,name,baseUom,cost\nBAD-2,Bad Price,EA,not-a-number"));
    var report = service.validate(job.getId());

    assertThat(report.invalidRows()).isEqualTo(1);
    assertThat(report.validationErrors()).singleElement()
        .satisfies(error -> assertThat(error.errors()).contains("cost must be non-negative if present"));
  }

  @Test
  void duplicateImportReturnsValidationErrorsInsteadOfCrashing() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);

    var first = service.create(new ImportJobRequest(null, "PRODUCTS", "products.csv", null, "sku,name,baseUom\nREPEAT-1,Repeat Pad,EA"));
    service.validate(first.getId());
    service.activate(first.getId());

    var duplicate = service.create(new ImportJobRequest(null, "PRODUCTS", "products.csv", null, "sku,name,baseUom\nREPEAT-1,Repeat Pad,EA"));
    var report = service.validate(duplicate.getId());

    assertThat(report.invalidRows()).isEqualTo(1);
    assertThat(report.validationErrors()).singleElement()
        .satisfies(error -> assertThat(error.errors()).contains("duplicate SKU exists for tenant"));
  }

  @Test
  void stage2DemoImportActivatesTenantScopedOperationalDataAndAuditEvents() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);

    activate("LOCATIONS", "locations.csv", "code,name,type,city,country\nWH-ALM,Almaty Main Warehouse,WAREHOUSE,Almaty,KZ\nWH-AST,Astana Overflow Warehouse,WAREHOUSE,Astana,KZ");
    activate("CUSTOMERS", "customers.csv", "accountCode,legalName,displayName,defaultCurrency,locationCode\nCUST-001,Steppe Logistics LLP,Steppe Logistics,USD,WH-ALM");
    activate("PRODUCTS", "products.csv", "sku,name,baseUom,cost,currency\nPAD-OE-04465,OEM Front Brake Pad Set,SET,42.00,USD\nPAD-SUB-ADV,Advantage Ceramic Brake Pad Set,SET,25.00,USD");
    activate("PRODUCT_ALIASES", "aliases.csv", "sku,aliasType,rawAlias\nPAD-OE-04465,OLD_SKU,CAMRY-BPAD-OLD");
    activate("INVENTORY", "inventory.csv", "sku,locationCode,quantityOnHand,quantityAvailable,quantityReserved,source\nPAD-OE-04465,WH-ALM,0,0,0,DEMO\nPAD-SUB-ADV,WH-ALM,18,18,0,DEMO");
    activate("PRICE_RULES", "prices.csv", "sku,accountCode,minQuantity,uom,unitPrice,currency,activeFrom,priority\nPAD-SUB-ADV,CUST-001,1,SET,39.00,USD,2026-01-01T00:00:00Z,10");
    activate("PRODUCT_SUBSTITUTES", "subs.csv", "sourceSku,substituteSku,substituteType,riskLevel,requiresApproval,notes\nPAD-OE-04465,PAD-SUB-ADV,AFTERMARKET,LOW,false,Demo substitute");

    assertThat(locationRepository.findByTenantIdAndCode(tenantId, "WH-ALM")).isPresent();
    assertThat(customerAccountRepository.findByTenantIdAndAccountCodeAndDeletedAtIsNull(tenantId, "CUST-001")).isPresent();
    assertThat(productRepository.findByTenantIdAndDeletedAtIsNullOrderBySku(tenantId)).hasSize(2);
    assertThat(productAliasRepository.findByTenantIdAndActiveTrue(tenantId)).hasSize(1);
    assertThat(inventorySnapshotRepository.findTop50ByTenantIdOrderByCapturedAtDesc(tenantId)).hasSize(2);
    assertThat(priceRuleRepository.findByTenantIdOrderByPriorityAsc(tenantId)).hasSize(1);
    assertThat(productSubstituteRepository.findByTenantIdAndSourceProductIdAndActiveTrue(tenantId, productRepository.findByTenantIdAndSkuAndDeletedAtIsNull(tenantId, "PAD-OE-04465").orElseThrow().getId())).hasSize(1);
    assertThat(auditEventRepository.findByTenantIdOrderByOccurredAtDesc(tenantId))
        .extracting("action")
        .contains("import_job.activated");
  }

  @Test
  void tenantCannotSeeOtherTenantImportedProducts() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    TenantContext.setTenantId(tenantA);
    activate("PRODUCTS", "products.csv", "sku,name,baseUom\nTENANT-A-ONLY,Tenant A Pad,EA");

    TenantContext.setTenantId(tenantB);

    assertThat(productRepository.findByTenantIdAndDeletedAtIsNullOrderBySku(tenantB)).isEmpty();
    assertThat(productRepository.findByTenantIdAndSkuAndDeletedAtIsNull(tenantB, "TENANT-A-ONLY")).isEmpty();
  }

  private void activate(String importType, String filename, String csv) {
    var job = service.create(new ImportJobRequest(null, importType, filename, null, csv));
    var report = service.validate(job.getId());
    assertThat(report.validationErrors()).isEmpty();
    service.activate(job.getId());
  }
}
