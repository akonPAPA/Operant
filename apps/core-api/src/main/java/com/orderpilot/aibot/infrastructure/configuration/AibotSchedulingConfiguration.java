package com.orderpilot.aibot.infrastructure.configuration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables Spring scheduling for the AI job drain only when {@code operant.ai.worker.enabled=true}.
 * Default is false (including tests).
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "operant.ai.worker.enabled", havingValue = "true")
public class AibotSchedulingConfiguration {}
