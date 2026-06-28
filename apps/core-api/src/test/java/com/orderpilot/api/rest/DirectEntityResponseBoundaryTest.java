package com.orderpilot.api.rest;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import org.junit.jupiter.api.Test;

/**
 * Wave 01A — direct entity response boundary proof.
 * Public/default controller methods in touched workspace boundaries
 * must not return {@code com.orderpilot.domain.*} JPA/domain entity types.
 */
class DirectEntityResponseBoundaryTest {

  private static final String DOMAIN_PACKAGE = "com.orderpilot.domain";

  @Test
  void workspaceDraftQuoteEndpointsDoNotReturnDomainTypes() {
    String[] methods = {"createQuote", "quotes", "quote", "quoteLines", "approveQuote", "rejectQuote", "cancelQuote"};
    for (String methodName : methods) {
      assertNotDomainReturn(WorkspaceController.class, methodName);
    }
  }

  @Test
  void workspaceDraftOrderEndpointsDoNotReturnDomainTypes() {
    String[] methods = {"createOrder", "orders", "order", "orderLines", "approveOrder", "rejectOrder", "cancelOrder"};
    for (String methodName : methods) {
      assertNotDomainReturn(WorkspaceController.class, methodName);
    }
  }

  @Test
  void workspaceNoteEndpointsDoNotReturnDomainTypes() {
    assertNotDomainReturn(WorkspaceController.class, "addNote");
    assertNotDomainReturn(WorkspaceController.class, "notes");
  }

  @Test
  void validationWorkspaceActionDraftEndpointsDoNotReturnDomainTypes() {
    assertNotDomainReturn(ValidationWorkspaceActionController.class, "createQuote");
    assertNotDomainReturn(ValidationWorkspaceActionController.class, "createOrder");
  }

  @Test
  void validationReviewPrepareDraftEndpointsDoNotReturnDomainTypes() {
    assertNotDomainReturn(ValidationReviewController.class, "prepareDraftQuote");
    assertNotDomainReturn(ValidationReviewController.class, "prepareDraftOrder");
  }

  private static void assertNotDomainReturn(Class<?> controller, String methodName) {
    Method m = findPublicMethod(controller, methodName);
    assertThat(m).as("%s.%s must exist", controller.getSimpleName(), methodName).isNotNull();
    String label = controller.getSimpleName() + "." + methodName;
    Type type = m.getGenericReturnType();
    assertThat(type.getTypeName())
        .as("%s return type must not be a domain entity", label)
        .doesNotContain(DOMAIN_PACKAGE);
    if (type instanceof ParameterizedType pt) {
      for (Type arg : pt.getActualTypeArguments()) {
        assertThat(arg.getTypeName())
            .as("%s generic type argument must not be a domain entity", label)
            .doesNotContain(DOMAIN_PACKAGE);
      }
    }
  }

  private static Method findPublicMethod(Class<?> clazz, String methodName) {
    for (Method m : clazz.getMethods()) {
      if (m.getName().equals(methodName)) {
        return m;
      }
    }
    return null;
  }
}
