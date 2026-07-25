package com.orderpilot.application.services.control.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderpilot.api.rest.InternalControlLifecycleController;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Architecture proof that the REST executor completion path cannot bypass
 * {@link BackupArtifactPersistenceService} for terminal BACKUP completion.
 */
class LifecycleTerminalOwnerArchitectureTest {
  @Test
  void controllerDependsOnArtifactCoordinatorAndDoesNotExposeLowerComplete() {
    assertThat(Arrays.stream(InternalControlLifecycleController.class.getDeclaredFields())
            .map(Field::getType))
        .contains(BackupArtifactPersistenceService.class)
        .contains(LifecycleBackupOperationService.class);

    assertThat(Arrays.stream(InternalControlLifecycleController.class.getDeclaredMethods())
            .map(Method::getName))
        .contains("complete", "stage")
        .doesNotContain("completeInTransaction");

    List<Method> completeMethods = Arrays.stream(LifecycleBackupOperationService.class.getDeclaredMethods())
        .filter(method -> method.getName().equals("complete"))
        .toList();
    // Non-empty first: Stream.allMatch on an empty filter is vacuously true and would hide rename/removal.
    assertThat(completeMethods)
        .as("LifecycleBackupOperationService.complete must exist (package-private terminal owner)")
        .isNotEmpty();
    assertThat(completeMethods)
        .as("LifecycleBackupOperationService.complete must not be public")
        .allMatch(method -> !Modifier.isPublic(method.getModifiers()));
  }
}
