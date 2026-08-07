package com.orderpilot.aibot.infrastructure.security;

import com.orderpilot.aibot.application.port.out.PublicIdGenerator;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class UuidPublicIdGenerator implements PublicIdGenerator {
  @Override
  public String next(String prefix) {
    String p = prefix == null || prefix.isBlank() ? "id" : prefix.trim();
    String raw = UUID.randomUUID().toString().replace("-", "");
    String value = p + "_" + raw;
    return value.length() <= 40 ? value : value.substring(0, 40);
  }
}
