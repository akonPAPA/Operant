package com.orderpilot.api.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.orderpilot.api.dto.ControlLifecycleDtos.ControlLifecycleError;
import com.orderpilot.application.services.control.lifecycle.BackupArtifactPersistenceService;
import com.orderpilot.application.services.control.lifecycle.LifecycleBackupOperationService;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * Boundary-mapping proof for the lifecycle control surface exception contract. A domain/boundary
 * validation failure of the executor's artifact report is a bounded, non-enumerating 400 that never
 * leaks the raw exception message; an unexpected {@link NullPointerException} is a programming defect
 * that must fall through to the redacted global 500 rather than be disguised as a client report.
 */
class InternalControlLifecycleControllerExceptionMappingTest {
  private final InternalControlLifecycleController controller = new InternalControlLifecycleController(
      mock(LifecycleBackupOperationService.class), mock(BackupArtifactPersistenceService.class));

  @Test
  void illegalArgumentMapsToBoundedBadRequestWithoutLeakingMessage() {
    ResponseEntity<ControlLifecycleError> response =
        controller.handleInvalidRuntimeContract(new IllegalArgumentException("secret-internal-detail"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().code()).isEqualTo("INVALID_BACKUP_ARTIFACT_REPORT");
    assertThat(response.getBody().code()).doesNotContain("secret-internal-detail");
  }

  @Test
  void noExceptionHandlerCapturesNullPointerOrBroadRuntimeException() {
    for (Method method : InternalControlLifecycleController.class.getDeclaredMethods()) {
      ExceptionHandler handler = method.getAnnotation(ExceptionHandler.class);
      if (handler == null) {
        continue;
      }
      assertThat(handler.value())
          .as("lifecycle controller must not swallow NPE/RuntimeException into a client-facing response")
          .doesNotContain(NullPointerException.class)
          .doesNotContain(RuntimeException.class);
    }
  }
}
