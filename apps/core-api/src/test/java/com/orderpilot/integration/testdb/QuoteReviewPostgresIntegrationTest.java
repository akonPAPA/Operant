package com.orderpilot.integration.testdb;

import static com.orderpilot.support.DatabaseIntegrationTestBase.CLEAN;
import static com.orderpilot.support.DatabaseIntegrationTestBase.CUSTOMERS;
import static com.orderpilot.support.DatabaseIntegrationTestBase.INVENTORY;
import static com.orderpilot.support.DatabaseIntegrationTestBase.PRICING;
import static com.orderpilot.support.DatabaseIntegrationTestBase.PRODUCTS;
import static com.orderpilot.support.DatabaseIntegrationTestBase.PRODUCT_ALIASES;
import static com.orderpilot.support.DatabaseIntegrationTestBase.QUOTE_REVIEW_ATTEMPTS;
import static com.orderpilot.support.DatabaseIntegrationTestBase.TENANTS;
import static com.orderpilot.support.DatabaseIntegrationTestBase.USERS_ROLES;
import static com.orderpilot.support.TestQuoteReviewFixtures.ATTEMPT_A_LINKED;
import static com.orderpilot.support.TestQuoteReviewFixtures.ATTEMPT_A_REVIEW;
import static com.orderpilot.support.TestQuoteReviewFixtures.ATTEMPT_B_REVIEW;
import static com.orderpilot.support.TestTenantFixtures.TENANT_A;
import static com.orderpilot.support.TestTenantFixtures.TENANT_B;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderpilot.api.dto.Stage12CDtos.QuoteConversionAttemptReviewFilter;
import com.orderpilot.application.services.workspace.QuoteConversionAttemptReviewQueryService;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.support.DatabaseIntegrationTestBase;
import com.orderpilot.support.RequiresPostgresIntegration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.jdbc.Sql;

@Sql(scripts = {CLEAN, TENANTS, USERS_ROLES, CUSTOMERS, PRODUCTS, PRODUCT_ALIASES, INVENTORY, PRICING, QUOTE_REVIEW_ATTEMPTS})
@RequiresPostgresIntegration
class QuoteReviewPostgresIntegrationTest extends DatabaseIntegrationTestBase {
  @Autowired private QuoteConversionAttemptReviewQueryService service;

  @Test
  void listShowsOnlyCurrentTenantPreDraftAndLinkedAttempts() {
    TenantContext.setTenantId(TENANT_A);

    var items = service.list(new QuoteConversionAttemptReviewFilter(null, null, null, null, null, null, null));

    assertThat(items).extracting("id").containsExactly(ATTEMPT_A_LINKED, ATTEMPT_A_REVIEW);
    assertThat(items).noneMatch(item -> item.id().equals(ATTEMPT_B_REVIEW));
    assertThat(items).anySatisfy(item -> {
      assertThat(item.id()).isEqualTo(ATTEMPT_A_REVIEW);
      assertThat(item.draftQuoteLinked()).isFalse();
      assertThat(item.reviewRequired()).isTrue();
      assertThat(item.reasonCodes()).containsExactly("CUSTOMER_UNRESOLVED");
    });
    assertThat(items).anySatisfy(item -> {
      assertThat(item.id()).isEqualTo(ATTEMPT_A_LINKED);
      assertThat(item.draftQuoteLinked()).isTrue();
      assertThat(item.reviewRequired()).isFalse();
    });
  }

  @Test
  void detailReturnsOnlySafeReviewMetadata() {
    TenantContext.setTenantId(TENANT_A);

    var detail = service.detail(ATTEMPT_A_REVIEW);

    assertThat(detail.safeMetadata())
        .containsEntry("lineCount", 1)
        .containsEntry("customerResolution", "UNRESOLVED")
        .containsEntry("issueCount", 1);
    assertThat(detail.safeMetadata().keySet()).containsExactly("lineCount", "customerResolution", "issueCount");
    assertThat(detail.validationIssues()).hasSize(1);
    assertThat(detail.toString())
        .doesNotContain("DO_NOT_EXPOSE")
        .doesNotContain("rawPayload")
        .doesNotContain("rawText")
        .doesNotContain("objectStorageKey");
  }

  @Test
  void detailForForeignTenantAttemptReturnsNotFound() {
    TenantContext.setTenantId(TENANT_A);

    assertThatThrownBy(() -> service.detail(ATTEMPT_B_REVIEW))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void tenantBDoesNotSeeTenantAAttempts() {
    TenantContext.setTenantId(TENANT_B);

    var items = service.list(new QuoteConversionAttemptReviewFilter(null, null, null, null, null, null, null));

    assertThat(items).extracting("id").containsExactly(ATTEMPT_B_REVIEW);
    assertThat(items).noneMatch(item -> item.id().equals(ATTEMPT_A_REVIEW));
    assertThatThrownBy(() -> service.detail(ATTEMPT_A_REVIEW))
        .isInstanceOf(NotFoundException.class);
  }
}
