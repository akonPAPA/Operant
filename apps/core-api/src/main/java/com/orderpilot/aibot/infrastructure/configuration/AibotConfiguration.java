package com.orderpilot.aibot.infrastructure.configuration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({
  OperantAiProperties.class,
  GeminiProviderProperties.class,
  BotRuntimeProperties.class
})
public class AibotConfiguration {}
