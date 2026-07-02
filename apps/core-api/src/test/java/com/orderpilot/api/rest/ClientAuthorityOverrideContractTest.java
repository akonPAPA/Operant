package com.orderpilot.api.rest;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

class ClientAuthorityOverrideContractTest {
  private static final Set<String> FORBIDDEN_REQUEST_FIELDS = Set.of(
      "tenantId",
      "actorId",
      "actorRole",
      "actorUserId",
      "userId",
      "staffUserId",
      "createdBy",
      "approvedBy",
      "decidedBy",
      "reviewedBy",
      "correctedByUserId",
      "linkedByUserId",
      "approvalStatus",
      "executionStatus",
      "externalExecutionStatus",
      "riskLevel",
      "margin",
      "supportGrantId",
      "externalWriteAuthority");

  @Test
  void everyPublicRequestBodyCarriesBusinessIntentOnly() throws Exception {
    List<String> violations = new ArrayList<>();

    for (Class<?> controller : ControllerEntityReturnBanTest.controllerClasses()) {
      for (Method method : controller.getDeclaredMethods()) {
        if (!AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class)) {
          continue;
        }
        for (Parameter parameter : method.getParameters()) {
          if (parameter.getAnnotation(RequestBody.class) == null) {
            continue;
          }
          collectForbiddenFields(
              parameter.getParameterizedType(),
              controller.getSimpleName() + "." + method.getName(),
              new HashSet<>(),
              violations);
        }
      }
    }

    assertThat(violations)
        .as("Public request DTOs must not accept backend-owned authority fields")
        .isEmpty();
  }

  private static void collectForbiddenFields(
      Type type, String path, Set<Type> visited, List<String> violations) {
    if (!visited.add(type)) {
      return;
    }
    if (type instanceof ParameterizedType parameterized) {
      for (Type argument : parameterized.getActualTypeArguments()) {
        collectForbiddenFields(argument, path, visited, violations);
      }
      return;
    }
    if (!(type instanceof Class<?> clazz)
        || !clazz.getName().startsWith("com.orderpilot.api.dto.")) {
      return;
    }

    if (clazz.isRecord()) {
      for (RecordComponent component : clazz.getRecordComponents()) {
        inspectProperty(component.getName(), component.getGenericType(), path, visited, violations);
      }
      return;
    }

    for (var field : clazz.getDeclaredFields()) {
      if (!Modifier.isStatic(field.getModifiers())) {
        inspectProperty(field.getName(), field.getGenericType(), path, visited, violations);
      }
    }
  }

  private static void inspectProperty(
      String name, Type type, String path, Set<Type> visited, List<String> violations) {
    String propertyPath = path + " -> " + name;
    if (FORBIDDEN_REQUEST_FIELDS.contains(name)) {
      violations.add(propertyPath);
    }
    collectForbiddenFields(type, propertyPath, visited, violations);
  }
}
