package com.orderpilot.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.application.services.ProductCodeNormalizer;
import com.orderpilot.domain.customer.CustomerAccountRepository;
import com.orderpilot.domain.product.ProductAliasRepository;
import com.orderpilot.domain.product.ProductCompatibilityRepository;
import com.orderpilot.domain.product.ProductRepository;
import com.orderpilot.domain.product.ProductSubstituteRepository;
import com.orderpilot.domain.product.CustomerSubstitutionPreferenceRepository;
import com.orderpilot.domain.inventory.InventorySnapshotRepository;
import com.orderpilot.domain.reconciliation.InventoryMovementRepository;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({DemoDataService.class, CoreConfiguration.class})
class DemoFixturesTest {
  @Autowired private DemoDataService demoDataService;
  @Autowired private ProductRepository productRepository;
  @Autowired private ProductAliasRepository aliasRepository;
  @Autowired private ProductSubstituteRepository substituteRepository;
  @Autowired private ProductCompatibilityRepository compatibilityRepository;
  @Autowired private CustomerSubstitutionPreferenceRepository substitutionPreferenceRepository;
  @Autowired private CustomerAccountRepository customerRepository;
  @Autowired private InventorySnapshotRepository inventorySnapshotRepository;
  @Autowired private InventoryMovementRepository movementRepository;

  @Test
  void fixturesLoadAndSeedIsRepeatable() {
    DemoDataService.DemoSeedResult first = demoDataService.seedCoreV1Demo();
    DemoDataService.DemoSeedResult second = demoDataService.seedCoreV1Demo();

    assertThat(second.tenantId()).isEqualTo(first.tenantId());
    assertThat(second.primaryProductId()).isEqualTo(first.primaryProductId());
    assertThat(productRepository.findByTenantIdAndDeletedAtIsNullOrderBySku(first.tenantId())).hasSize(3);
    assertThat(customerRepository.findByTenantIdAndDeletedAtIsNullOrderByAccountCode(first.tenantId())).hasSize(1);
    String brakePadsAlias = ProductCodeNormalizer.normalize("brake pads for Toyota Camry 2018");
    assertThat(brakePadsAlias).isEqualTo("BRAKEPADSFORTOYOTACAMRY2018");
    assertThat(aliasRepository.findByTenantIdAndNormalizedAliasAndActiveTrue(first.tenantId(), brakePadsAlias)).hasSize(1);
    assertThat(substituteRepository.findByTenantIdAndSourceProductIdAndActiveTrue(first.tenantId(), first.primaryProductId())).hasSize(2);
    assertThat(compatibilityRepository.findByTenantIdAndProductIdAndActiveTrue(first.tenantId(), first.primaryProductId())).hasSize(1);
    assertThat(substitutionPreferenceRepository.findByTenantIdAndCustomerAccountId(first.tenantId(), first.customerId())).hasSize(2);
    assertThat(inventorySnapshotRepository.findTop50ByTenantIdOrderByCapturedAtDesc(first.tenantId())).hasSize(3);
    assertThat(movementRepository.countByTenantId(first.tenantId())).isEqualTo(3);
    assertThat(first.primaryProductSku()).isEqualTo("PAD-OE-04465");
    assertThat(second.locationCode()).isEqualTo("WH-ALM");
    assertThat(customerRepository.findByTenantIdAndDeletedAtIsNullOrderByAccountCode(first.tenantId()).getFirst().getAccountCode()).isEqualTo("CUST-001");
    assertThat(demoDataService.fixtureText("telegram-rfq-demo.json"))
        .contains("Need 2 EA PAD-OE-04465 brake pads for Toyota Camry 2018, wholesale, Almaty.")
        .doesNotContain("20 pcs");
    assertThat(demoDataService.fixtureText("reconciliation-demo.json")).contains("\"mismatchQuantity\": -16");
  }

  @Test
  void runnableDemoDocsAndFixturesDoNotUseOldRfqText() throws Exception {
    String walkthrough = Files.readString(Path.of("../../docs/investor/demo-api-walkthrough.http"));
    String checkScript = Files.readString(Path.of("../../scripts/check-local-demo.ps1"));
    String telegramFixture = demoDataService.fixtureText("telegram-rfq-demo.json");

    assertThat(walkthrough).contains("Need 2 EA PAD-OE-04465 brake pads for Toyota Camry 2018, wholesale, Almaty.");
    assertThat(walkthrough).doesNotContain("20 pcs");
    assertThat(checkScript).doesNotContain("Need brake pads for Toyota Camry 2018, 20 pcs, wholesale, Almaty.");
    assertThat(telegramFixture).doesNotContain("20 pcs");
  }

  @Test
  void localDemoSeedScriptSetsNormalizedSkuAndVerifiesFrozenRows() throws Exception {
    String seedScript = Files.readString(Path.of("../../scripts/seed-local-demo.ps1"));

    assertThat(ProductCodeNormalizer.normalize("PAD-OE-04465")).isEqualTo("PADOE04465");
    assertThat(seedScript)
        .contains("normalized_sku")
        .contains("'PAD-OE-04465', 'PADOE04465'")
        .contains("normalized_sku = EXCLUDED.normalized_sku")
        .contains("Demo seed verification failed: tenant")
        .contains("Demo seed verification failed: product PAD-OE-04465 / normalized_sku PADOE04465 is missing")
        .contains("Demo seed verification failed: warehouse WH-ALM is missing")
        .contains("Target datasource: jdbc:postgresql://")
        .contains("Target database:")
        .contains("Spring profile:");
  }

  @TestConfiguration
  static class JsonConfig {
    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper();
    }
  }
}
