package com.orderpilot.aibot.application.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "operant.ai.worker.enabled", havingValue = "true")
public class AiJobScheduledDrain {
  private static final Logger log = LoggerFactory.getLogger(AiJobScheduledDrain.class);

  private final AiJobDrainService drainService;

  public AiJobScheduledDrain(AiJobDrainService drainService) {
    this.drainService = drainService;
  }

  @Scheduled(
      fixedDelayString = "${operant.ai.worker.fixed-delay-ms:5000}",
      initialDelayString = "${operant.ai.worker.fixed-delay-ms:5000}")
  public void drainScheduled() {
    try {
      AiJobDrainSummary summary = drainService.drainOnce();
      if (summary.claimed() > 0) {
        log.info(
            "aibot scheduled drain claimed={} completed={} failed={} leaseConflicts={}",
            summary.claimed(),
            summary.completed(),
            summary.failed(),
            summary.leaseConflicts());
      }
    } catch (RuntimeException ex) {
      log.warn("aibot scheduled drain cycle failed: {}", ex.getClass().getSimpleName());
    }
  }
}
