package com.orderpilot.aibot.application.service;

public record AiJobDrainSummary(
    int claimed, int completed, int invalid, int failed, int deferred, int leaseConflicts) {}
