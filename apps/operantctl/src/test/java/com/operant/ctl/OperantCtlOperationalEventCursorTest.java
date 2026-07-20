package com.operant.ctl;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Local fail-fast proof for the signed operational-event cursor argument. */
class OperantCtlOperationalEventCursorTest {

  @Test
  void acceptsTheFullNonNegativeLongDomainAndNormalizesLeadingZeros() {
    assertThat(OperantCtl.normalizeEventBefore("0")).isEqualTo("0");
    assertThat(OperantCtl.normalizeEventBefore("00042")).isEqualTo("42");
    assertThat(OperantCtl.normalizeEventBefore(Long.toString(Long.MAX_VALUE)))
        .isEqualTo(Long.toString(Long.MAX_VALUE));
  }

  @Test
  void rejectsOverflowAndMalformedValuesBeforeAnyRequestCanBeBuilt() {
    assertThat(OperantCtl.normalizeEventBefore("9223372036854775808")).isNull();
    assertThat(OperantCtl.normalizeEventBefore("9999999999999999999")).isNull();
    assertThat(OperantCtl.normalizeEventBefore("-1")).isNull();
    assertThat(OperantCtl.normalizeEventBefore("not-a-number")).isNull();
    assertThat(OperantCtl.normalizeEventBefore(null)).isNull();
  }
}
