package com.orderpilot.aibot.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.orderpilot.aibot.application.port.out.AiJobRepositoryPort;
import com.orderpilot.aibot.application.port.out.AiJobRepositoryPort.ClaimedAiJob;
import com.orderpilot.aibot.domain.aijob.AiJob;
import com.orderpilot.aibot.domain.aijob.AiJobPurpose;
import com.orderpilot.aibot.infrastructure.configuration.OperantAiProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockMakers;
import org.mockito.Mockito;

/**
 * PR #318 Slice 3 — every claim in a drain batch must use a fresh timestamp and lease window.
 *
 * <p>A single {@code drainOnce()} batch can span real wall-clock time (each claimed job may perform
 * provider I/O). Computing {@code now} once before the loop would anchor every lease deadline to the
 * batch start, handing later claims a lease window that is already partly — or fully — elapsed. This
 * test drives the loop with a stepping clock and asserts each {@code claimNext} call received its own
 * advancing {@code now} and a lease deadline anchored to that same instant. Under the pre-fix code
 * (single batch-start {@code now}) all three values would be identical and this test fails.
 */
class AiJobDrainServiceTest {

  private static final Instant T0 = Instant.parse("2026-06-16T12:00:00Z");
  private static final Duration STEP = Duration.ofSeconds(30);
  private static final Duration LEASE = Duration.ofMinutes(5);

  @Test
  void eachClaimUsesAFreshTimestampAndLeaseWindow() {
    AiJobRepositoryPort repository =
        Mockito.mock(AiJobRepositoryPort.class, Mockito.withSettings().mockMaker(MockMakers.SUBCLASS));
    AiJobProcessingService processing =
        Mockito.mock(AiJobProcessingService.class, Mockito.withSettings().mockMaker(MockMakers.SUBCLASS));
    OperantAiProperties properties =
        Mockito.mock(OperantAiProperties.class, Mockito.withSettings().mockMaker(MockMakers.SUBCLASS));
    when(properties.getWorkerBatchSize()).thenReturn(5);
    when(properties.getWorkerLeaseDuration()).thenReturn(LEASE);

    AiJob job = requestedJob();
    ClaimedAiJob claimed = new ClaimedAiJob(job, "owner", 1L);
    // Two real claims, then an empty result to end the batch: 3 claimNext calls total.
    when(repository.claimNext(any(), any(), any()))
        .thenReturn(Optional.of(claimed))
        .thenReturn(Optional.of(claimed))
        .thenReturn(Optional.empty());
    when(repository.findByPublicIdAndTenantId(any(), any())).thenReturn(Optional.of(job));

    AiJobDrainService service =
        new AiJobDrainService(repository, processing, properties, new SteppingClock(T0, STEP));

    service.drainOnce();

    ArgumentCaptor<Instant> nowCaptor = ArgumentCaptor.forClass(Instant.class);
    ArgumentCaptor<Instant> leaseUntilCaptor = ArgumentCaptor.forClass(Instant.class);
    verify(repository, times(3)).claimNext(any(), nowCaptor.capture(), leaseUntilCaptor.capture());

    List<Instant> nows = nowCaptor.getAllValues();
    List<Instant> leaseUntils = leaseUntilCaptor.getAllValues();

    // Each iteration read the clock freshly: monotonically advancing, all distinct.
    assertThat(nows).containsExactly(T0, T0.plus(STEP), T0.plus(STEP.multipliedBy(2)));
    // Each lease deadline is anchored to ITS OWN claim instant, never the batch start.
    for (int i = 0; i < nows.size(); i++) {
      assertThat(leaseUntils.get(i)).isEqualTo(nows.get(i).plus(LEASE));
    }
  }

  private static AiJob requestedJob() {
    return new AiJob(
        "aijob_drain_test",
        UUID.randomUUID(),
        AiJobPurpose.BOT_DEFINITION_GENERATION,
        UUID.randomUUID(),
        "idem-drain",
        "op-cap-m18.v1",
        T0.minus(Duration.ofHours(1)));
  }

  /** Clock that returns a new, advancing instant on each {@link #instant()} call. */
  private static final class SteppingClock extends Clock {
    private final Instant start;
    private final Duration step;
    private final AtomicInteger calls = new AtomicInteger();

    private SteppingClock(Instant start, Duration step) {
      this.start = start;
      this.step = step;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return start.plus(step.multipliedBy(calls.getAndIncrement()));
    }
  }
}
