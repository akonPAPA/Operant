package com.orderpilot.api.rest;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Entity;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.core.annotation.AnnotatedElementUtils;

class ControllerEntityReturnBoundaryTest {

  @Test
  void restEndpointsDoNotReturnJpaEntities() throws Exception {
    ClassPathScanningCandidateComponentProvider scanner =
        new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
    List<String> violations = new ArrayList<>();

    for (var bean : scanner.findCandidateComponents("com.orderpilot")) {
      Class<?> controller = Class.forName(bean.getBeanClassName());
      for (Method method : controller.getDeclaredMethods()) {
        if (!AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class)) {
          continue;
        }
        collectEntityTypes(method.getGenericReturnType(), violations,
            controller.getSimpleName() + "." + method.getName());
      }
    }

    assertThat(violations)
        .as("REST endpoint return types must not expose JPA entities")
        .isEmpty();
  }

  private static void collectEntityTypes(Type type, List<String> violations, String endpoint) {
    if (type instanceof Class<?> clazz) {
      boolean domainType = clazz.getPackageName().startsWith("com.orderpilot.domain");
      if (clazz.isAnnotationPresent(Entity.class) || (domainType && !clazz.isEnum())) {
        violations.add(endpoint + " -> " + clazz.getName());
      }
      if (clazz.isArray()) {
        collectEntityTypes(clazz.getComponentType(), violations, endpoint);
      }
    } else if (type instanceof ParameterizedType parameterized) {
      collectEntityTypes(parameterized.getRawType(), violations, endpoint);
      for (Type argument : parameterized.getActualTypeArguments()) {
        collectEntityTypes(argument, violations, endpoint);
      }
    } else if (type instanceof GenericArrayType arrayType) {
      collectEntityTypes(arrayType.getGenericComponentType(), violations, endpoint);
    } else if (type instanceof WildcardType wildcard) {
      for (Type upper : wildcard.getUpperBounds()) {
        collectEntityTypes(upper, violations, endpoint);
      }
      for (Type lower : wildcard.getLowerBounds()) {
        collectEntityTypes(lower, violations, endpoint);
      }
    }
  }
}
