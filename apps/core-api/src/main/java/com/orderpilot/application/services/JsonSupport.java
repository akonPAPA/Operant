package com.orderpilot.application.services;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class JsonSupport {
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
  private final ObjectMapper objectMapper;

  public JsonSupport(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public Map<String, Object> parseObject(String json) {
    if (json == null || json.isBlank()) {
      return new LinkedHashMap<>();
    }
    try {
      return objectMapper.readValue(json, MAP_TYPE);
    } catch (JsonProcessingException ex) {
      throw new IllegalArgumentException("Invalid JSON payload");
    }
  }

  public String writeObject(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException ex) {
      throw new IllegalArgumentException("Unable to serialize JSON payload");
    }
  }

  public String errors(List<String> errors) {
    return writeObject(Map.of("errors", errors));
  }
}