package com.orderpilot.application.services.journey;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * OP-CAP-25 — proves the controlled scheduled drain is registered ONLY when
 * {@code orderpilot.runtime.order-journey-projection.enabled=true}, and that enabling it starts the context
 * cleanly (Spring scheduling wires without error). The disabled-by-default posture (no scheduler bean, no
 * background processing) is asserted in {@code OrderJourneyProjectionDrainServiceTest} against the default
 * test context. A very long fixed delay ensures the scheduler never actually fires during the test.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "orderpilot.runtime.order-journey-projection.enabled=true",
    "orderpilot.runtime.order-journey-projection.fixed-delay-ms=3600000"
})
class OrderJourneyProjectionScheduledDrainConfigTest {
  @Autowired ApplicationContext context;
  @Autowired OrderJourneyProjectionScheduledDrain scheduledDrain;

  @Test
  void scheduledDrainBeanIsRegisteredAndDrainsWithoutErrorWhenEnabled() {
    assertThat(context.getBeanNamesForType(OrderJourneyProjectionScheduledDrain.class)).hasSize(1);
    // invoking the tick directly is safe and bounded even with no pending work
    scheduledDrain.drainScheduled();
  }
}
