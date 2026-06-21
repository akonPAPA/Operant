package com.orderpilot.support;

import com.orderpilot.common.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = "spring.main.web-application-type=none")
@ActiveProfiles("integration-test")
@Tag("postgres-integration")
@EnabledIfSystemProperty(
    named = "orderpilot.postgres.integration.enabled",
    matches = "true",
    disabledReason = "Postgres integration tests require an explicit local/CI Postgres opt-in")
public abstract class DatabaseIntegrationTestBase {
  public static final String CLEAN = "/db/testdata/clean.sql";
  public static final String TENANTS = "/db/testdata/tenants.sql";
  public static final String USERS_ROLES = "/db/testdata/users_roles.sql";
  public static final String CUSTOMERS = "/db/testdata/customers.sql";
  public static final String PRODUCTS = "/db/testdata/products.sql";
  public static final String PRODUCT_ALIASES = "/db/testdata/product_aliases.sql";
  public static final String INVENTORY = "/db/testdata/inventory.sql";
  public static final String PRICING = "/db/testdata/pricing.sql";
  public static final String QUOTE_REVIEW_ATTEMPTS = "/db/testdata/quote_review_attempts.sql";

  @AfterEach
  void clearTenantContext() {
    TenantContext.clear();
  }
}
