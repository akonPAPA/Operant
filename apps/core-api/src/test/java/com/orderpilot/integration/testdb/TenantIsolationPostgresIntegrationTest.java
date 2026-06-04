package com.orderpilot.integration.testdb;

import static com.orderpilot.support.DatabaseIntegrationTestBase.CLEAN;
import static com.orderpilot.support.DatabaseIntegrationTestBase.CUSTOMERS;
import static com.orderpilot.support.DatabaseIntegrationTestBase.INVENTORY;
import static com.orderpilot.support.DatabaseIntegrationTestBase.PRICING;
import static com.orderpilot.support.DatabaseIntegrationTestBase.PRODUCTS;
import static com.orderpilot.support.DatabaseIntegrationTestBase.PRODUCT_ALIASES;
import static com.orderpilot.support.DatabaseIntegrationTestBase.TENANTS;
import static com.orderpilot.support.DatabaseIntegrationTestBase.USERS_ROLES;
import static com.orderpilot.support.TestProductFixtures.PRODUCT_A_KNOWN;
import static com.orderpilot.support.TestProductFixtures.PRODUCT_B_KNOWN;
import static com.orderpilot.support.TestTenantFixtures.TENANT_A;
import static com.orderpilot.support.TestTenantFixtures.TENANT_B;
import static org.assertj.core.api.Assertions.assertThat;

import com.orderpilot.domain.customer.CustomerAccountRepository;
import com.orderpilot.domain.product.ProductRepository;
import com.orderpilot.support.DatabaseIntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.jdbc.Sql;

@Sql(scripts = {CLEAN, TENANTS, USERS_ROLES, CUSTOMERS, PRODUCTS, PRODUCT_ALIASES, INVENTORY, PRICING})
class TenantIsolationPostgresIntegrationTest extends DatabaseIntegrationTestBase {
  @Autowired private ProductRepository productRepository;
  @Autowired private CustomerAccountRepository customerAccountRepository;

  @Test
  void productLookupsStayScopedToTenant() {
    assertThat(productRepository.findByTenantIdAndSkuAndDeletedAtIsNull(TENANT_A, "BRK-001"))
        .get()
        .extracting("id", "name")
        .containsExactly(PRODUCT_A_KNOWN, "Brake Pad A");
    assertThat(productRepository.findByTenantIdAndSkuAndDeletedAtIsNull(TENANT_B, "BRK-001"))
        .get()
        .extracting("id", "name")
        .containsExactly(PRODUCT_B_KNOWN, "Brake Pad B");

    assertThat(productRepository.findByIdAndTenantIdAndDeletedAtIsNull(PRODUCT_B_KNOWN, TENANT_A)).isEmpty();
    assertThat(productRepository.findByIdAndTenantIdAndDeletedAtIsNull(PRODUCT_A_KNOWN, TENANT_B)).isEmpty();
  }

  @Test
  void customerLookupsStayScopedToTenant() {
    assertThat(customerAccountRepository.findByTenantIdAndAccountCodeAndDeletedAtIsNull(TENANT_A, "CUST-A"))
        .get()
        .extracting("displayName")
        .isEqualTo("Customer A");
    assertThat(customerAccountRepository.findByTenantIdAndAccountCodeAndDeletedAtIsNull(TENANT_B, "CUST-B"))
        .get()
        .extracting("displayName")
        .isEqualTo("Customer B");

    assertThat(customerAccountRepository.findByTenantIdAndAccountCodeAndDeletedAtIsNull(TENANT_A, "CUST-B")).isEmpty();
    assertThat(customerAccountRepository.findByTenantIdAndAccountCodeAndDeletedAtIsNull(TENANT_B, "CUST-A")).isEmpty();
  }
}
