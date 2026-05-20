package com.orderpilot.application.services.integration.sandbox;

import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class SandboxConnectorAdapterRegistry {
  private final List<SandboxConnectorAdapter> adapters;

  public SandboxConnectorAdapterRegistry(List<SandboxConnectorAdapter> adapters) {
    this.adapters = adapters == null ? List.of() : List.copyOf(adapters);
  }

  public SandboxConnectorAdapter requireAdapter(String targetSystemType) {
    return adapters.stream()
        .filter(adapter -> adapter.supports(targetSystemType))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unsupported sandbox target system type: " + targetSystemType));
  }
}
