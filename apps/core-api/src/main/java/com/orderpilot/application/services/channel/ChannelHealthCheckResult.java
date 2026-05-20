package com.orderpilot.application.services.channel;

import com.orderpilot.domain.channel.ChannelProviderType;

public record ChannelHealthCheckResult(ChannelProviderType providerType, boolean healthy, String statusCode, String message) {}
