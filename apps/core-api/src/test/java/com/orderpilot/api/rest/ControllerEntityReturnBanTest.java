package com.orderpilot.api.rest;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

public class ControllerEntityReturnBanTest {
  private static final String DOMAIN_PACKAGE = "com.orderpilot.domain.";

  @Test
  void everyPublicControllerHandlerReturnsApiTypesInsteadOfDomainEntities() throws Exception {
    List<String> violations = new ArrayList<>();

    for (Class<?> controller : controllerClasses()) {
      for (Method method : controller.getDeclaredMethods()) {
        if (!AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class)) {
          continue;
        }
        collectDomainTypes(
            method.getGenericReturnType(),
            controller.getSimpleName() + "." + method.getName(),
            violations);
      }
    }

    assertThat(violations)
        .as("Public controller return types must never contain com.orderpilot.domain types")
        .isEmpty();
  }

  public static List<Class<?>> controllerClasses() throws ClassNotFoundException {
    ClassPathScanningCandidateComponentProvider scanner =
        new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

    List<Class<?>> controllers = new ArrayList<>();
    for (var candidate : scanner.findCandidateComponents("com.orderpilot.api.rest")) {
      controllers.add(Class.forName(candidate.getBeanClassName()));
    }
    return controllers;
  }

  private static void collectDomainTypes(Type type, String path, List<String> violations) {
    if (type instanceof Class<?> clazz) {
      if (clazz.getName().startsWith(DOMAIN_PACKAGE)) {
        violations.add(path + " -> " + clazz.getName());
      } else if (clazz.isArray()) {
        collectDomainTypes(clazz.getComponentType(), path + "[]", violations);
      }
      return;
    }
    if (type instanceof ParameterizedType parameterized) {
      collectDomainTypes(parameterized.getRawType(), path, violations);
      for (Type argument : parameterized.getActualTypeArguments()) {
        collectDomainTypes(argument, path, violations);
      }
      return;
    }
    if (type instanceof GenericArrayType array) {
      collectDomainTypes(array.getGenericComponentType(), path + "[]", violations);
      return;
    }
    if (type instanceof WildcardType wildcard) {
      for (Type bound : wildcard.getUpperBounds()) {
        collectDomainTypes(bound, path, violations);
      }
      for (Type bound : wildcard.getLowerBounds()) {
        collectDomainTypes(bound, path, violations);
      }
    }
  }
}
