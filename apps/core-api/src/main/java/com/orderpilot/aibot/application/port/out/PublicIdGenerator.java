package com.orderpilot.aibot.application.port.out;

public interface PublicIdGenerator {
  String next(String prefix);
}
