package com.orderpilot.api.rest;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ServiceInfoController {
  @GetMapping("/")
  public Map<String, Object> serviceInfo() {
    return Map.of(
        "service", "orderpilot-core-api",
        "status", "UP",
        "health", "/actuator/health");
  }

  @GetMapping("/favicon.ico")
  public ResponseEntity<Void> favicon() {
    return ResponseEntity.noContent().build();
  }
}
