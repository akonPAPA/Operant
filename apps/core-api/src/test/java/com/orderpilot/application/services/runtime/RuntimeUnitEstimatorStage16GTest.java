package com.orderpilot.application.services.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * OP-CAP-16G — estimator policy added for AI explanation/summary units and exercised report/export
 * units. Pure unit test (no Spring); the estimator is O(1) and side-effect-free.
 */
class RuntimeUnitEstimatorStage16GTest {
  private final RuntimeUnitEstimator estimator = new DefaultRuntimeUnitEstimator();
  private static final UUID TENANT = UUID.randomUUID();

  @Test
  void reportUnitsFromKnownRowCount() {
    // ceil(2500 / 1000) = 3
    assertThat(estimator.estimate(RuntimeUnitEstimateRequest.forReport(TENANT, 2500))).isEqualTo(3);
  }

  @Test
  void reportUnitsFallBackToOneWhenNoRowCount() {
    assertThat(estimator.estimate(RuntimeUnitEstimateRequest.forReport(TENANT, 0))).isEqualTo(1);
    assertThat(estimator.estimate(RuntimeUnitEstimateRequest.forReport(TENANT, null))).isEqualTo(1);
  }

  @Test
  void explanationUnitsFromKnownLineCount() {
    // ceil(50 / 25) = 2
    assertThat(estimator.estimate(RuntimeUnitEstimateRequest.forExplanation(TENANT, 50, null)))
        .isEqualTo(2);
  }

  @Test
  void explanationUnitsFromMessageCountWhenNoLines() {
    assertThat(estimator.estimate(RuntimeUnitEstimateRequest.forExplanation(TENANT, null, 7)))
        .isEqualTo(7);
  }

  @Test
  void explanationLineCountTakesPrecedenceOverMessageCount() {
    // lines present → use ceil(50/25)=2, ignoring the larger message count.
    assertThat(estimator.estimate(RuntimeUnitEstimateRequest.forExplanation(TENANT, 50, 999)))
        .isEqualTo(2);
  }

  @Test
  void explanationFallsBackToOne() {
    assertThat(estimator.estimate(RuntimeUnitEstimateRequest.forExplanation(TENANT, null, null)))
        .isEqualTo(1);
    assertThat(estimator.estimate(RuntimeUnitEstimateRequest.forExplanation(TENANT, 0, 0)))
        .isEqualTo(1);
  }

  @Test
  void explanationUnitsAreClampedToMax() {
    int units =
        estimator.estimate(RuntimeUnitEstimateRequest.forExplanation(TENANT, Integer.MAX_VALUE, null));
    assertThat(units).isEqualTo((int) DefaultRuntimeUnitEstimator.MAX_UNITS);
  }
}
