package com.orderpilot.infrastructure.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * OP-CAP-25 — enables Spring's scheduling infrastructure ONLY when the controlled Order Journey projection
 * drain is explicitly turned on via {@code orderpilot.runtime.order-journey-projection.enabled=true}.
 *
 * <p>Mirrors the project's existing {@code @ConditionalOnProperty} config-gating convention (see
 * {@code RuntimeRateRedisConfiguration}). With the default ({@code enabled=false}, including all tests),
 * {@code @EnableScheduling} is never registered and {@code OrderJourneyProjectionScheduledDrain} is never
 * created — so no scheduler thread exists and the runtime stays on explicit/manual projection processing.
 * There is no unmanaged thread, no infinite loop, and no new infrastructure: the only scheduled work is a
 * bounded, fixed-delay call into {@code OrderJourneyProjectionDrainService}.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "orderpilot.runtime.order-journey-projection.enabled", havingValue = "true")
public class OrderJourneyProjectionSchedulingConfiguration {
}
