package com.orderpilot.application.services.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * OP-CAP-16F Runtime Unit Estimation — pure unit tests for {@link DefaultRuntimeUnitEstimator}. No
 * Spring, no DB, no I/O. Verifies the cheap-metadata policy, the always-{@code >= 1} contract, clamp
 * of zero/negative values, and overflow saturation.
 */
class DefaultRuntimeUnitEstimatorStage16FTest {
  private final DefaultRuntimeUnitEstimator estimator = new DefaultRuntimeUnitEstimator();
  private static final UUID TENANT = UUID.randomUUID();

  @Test
  void nullRequestReturnsOne() {
    assertThat(estimator.estimate(null)).isEqualTo(1);
  }

  @Test
  void noMetadataReturnsOne() {
    assertThat(
            estimator.estimate(
                RuntimeUnitEstimateRequest.fallback(
                    TENANT,
                    RuntimeOperationType.AI_DOCUMENT_EXTRACTION,
                    RuntimeFeatureType.AI_DOCUMENT_EXTRACTION)))
        .isEqualTo(1);
  }

  @Test
  void pageCountMapsToUnits() {
    assertThat(
            estimator.estimate(
                RuntimeUnitEstimateRequest.forDocumentExtraction(TENANT, 10, null, null)))
        .isEqualTo(10);
  }

  @Test
  void fileSizeMapsToUnitsForExtraction() {
    // 512KB per page → 1.5MB rounds up to 3 pages.
    long threePages = 512L * 1024L * 3L;
    assertThat(
            estimator.estimate(
                RuntimeUnitEstimateRequest.forDocumentExtraction(TENANT, null, threePages, null)))
        .isEqualTo(3);
  }

  @Test
  void lineCountMapsToUnitsForExtraction() {
    // 25 lines per unit → 60 lines → 3.
    assertThat(
            estimator.estimate(
                RuntimeUnitEstimateRequest.forDocumentExtraction(TENANT, null, null, 60)))
        .isEqualTo(3);
  }

  @Test
  void rowCountMapsToUnitsForBulkImport() {
    // 100 rows per unit → 250 rows → 3.
    assertThat(estimator.estimate(RuntimeUnitEstimateRequest.forBulkImport(TENANT, 250, null)))
        .isEqualTo(3);
  }

  @Test
  void itemCountMapsToUnitsForReconciliation() {
    assertThat(estimator.estimate(RuntimeUnitEstimateRequest.forReconciliation(TENANT, 150)))
        .isEqualTo(2);
  }

  @Test
  void rowCountMapsToUnitsForReport() {
    assertThat(estimator.estimate(RuntimeUnitEstimateRequest.forReport(TENANT, 2500))).isEqualTo(3);
  }

  @Test
  void messageCountMapsToUnits() {
    RuntimeUnitEstimateRequest request =
        new RuntimeUnitEstimateRequest(TENANT, null, null, null, null, null, null, 7, null);
    assertThat(estimator.estimate(request)).isEqualTo(7);
  }

  @Test
  void zeroAndNegativeValuesClampToOne() {
    assertThat(
            estimator.estimate(
                RuntimeUnitEstimateRequest.forDocumentExtraction(TENANT, 0, null, null)))
        .isEqualTo(1);
    assertThat(estimator.estimate(RuntimeUnitEstimateRequest.forBulkImport(TENANT, -5, null)))
        .isEqualTo(1);
    assertThat(estimator.estimate(RuntimeUnitEstimateRequest.forReconciliation(TENANT, -100)))
        .isEqualTo(1);
  }

  @Test
  void hugeValuesSaturateAtCap() {
    assertThat(
            estimator.estimate(
                RuntimeUnitEstimateRequest.forDocumentExtraction(
                    TENANT, Integer.MAX_VALUE, null, null)))
        .isEqualTo((int) DefaultRuntimeUnitEstimator.MAX_UNITS);
    assertThat(
            estimator.estimate(
                RuntimeUnitEstimateRequest.forBulkImport(TENANT, null, Long.MAX_VALUE)))
        .isEqualTo((int) DefaultRuntimeUnitEstimator.MAX_UNITS);
  }

  @Test
  void unknownOperationWithoutMetadataReturnsOne() {
    assertThat(
            estimator.estimate(
                RuntimeUnitEstimateRequest.fallback(TENANT, RuntimeOperationType.SEARCH_QUERY, null)))
        .isEqualTo(1);
  }
}
