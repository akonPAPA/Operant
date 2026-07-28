package com.orderpilot.application.services.control.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.orderpilot.domain.control.BackupArtifact;
import com.orderpilot.domain.control.BackupArtifactFailureCode;
import com.orderpilot.domain.control.BackupArtifactRepository;
import com.orderpilot.domain.control.LifecycleOperation;
import com.orderpilot.domain.control.LifecycleOperationRepository;
import com.orderpilot.domain.control.LifecycleOperationState;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Proves orphan drain keeps Pageable at offset 0 across batches larger than the page size. Each orphan
 * leaves the STAGED filter, so re-querying page 0 is required; advancing offset would skip rows.
 */
class LifecycleBackupOperationOrphanDrainTest {
  private static final Instant NOW = Instant.parse("2026-07-24T12:00:00Z");
  private static final String EXECUTOR = "executor-fingerprint-drain";
  private static final int PAGE_SIZE = 32;

  @Test
  void reLeaseDrainsMoreThanOneStagedPageUsingFixedOffsetZero() {
    LifecycleOperationRepository operationRepository = mock(LifecycleOperationRepository.class);
    BackupArtifactRepository artifactRepository = mock(BackupArtifactRepository.class);
    LifecycleOperationAuditor auditor = mock(LifecycleOperationAuditor.class);
    PlatformTransactionManager tx = mock(PlatformTransactionManager.class);

    UUID operationId = UUID.randomUUID();
    LifecycleOperation expiredLease = mock(LifecycleOperation.class);
    when(expiredLease.getId()).thenReturn(operationId);
    when(expiredLease.getState()).thenReturn(LifecycleOperationState.LEASED);
    when(operationRepository.findLeasableWithLock(any(), any(), any(), any()))
        .thenReturn(List.of(expiredLease));
    when(operationRepository.save(expiredLease)).thenReturn(expiredLease);

    List<BackupArtifact> firstPage = stagedMocks(PAGE_SIZE);
    List<BackupArtifact> secondPage = stagedMocks(1);
    when(artifactRepository.findStagedWithLockByLifecycleOperationId(eq(operationId), any(Pageable.class)))
        .thenReturn(firstPage, secondPage);

    LifecycleBackupOperationService service = new LifecycleBackupOperationService(
        operationRepository,
        artifactRepository,
        auditor,
        Clock.fixed(NOW, ZoneOffset.UTC),
        tx,
        true,
        300L);

    assertThat(service.leaseNext(EXECUTOR)).contains(expiredLease);

    ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
    verify(artifactRepository, times(2))
        .findStagedWithLockByLifecycleOperationId(eq(operationId), pageableCaptor.capture());
    assertThat(pageableCaptor.getAllValues())
        .hasSize(2)
        .allSatisfy(pageable -> {
          assertThat(pageable.getPageNumber()).isZero();
          assertThat(pageable.getPageSize()).isEqualTo(PAGE_SIZE);
        });

    List<BackupArtifact> all = new ArrayList<>(firstPage);
    all.addAll(secondPage);
    for (BackupArtifact artifact : all) {
      verify(artifact).markOrphaned(BackupArtifactFailureCode.EXPIRED_LEASE_REPLACED, NOW);
      verify(artifactRepository).save(artifact);
      verify(auditor).artifactOrphaned(
          expiredLease, artifact, EXECUTOR, BackupArtifactFailureCode.EXPIRED_LEASE_REPLACED.name());
    }
    verify(expiredLease).lease(eq(EXECUTOR), eq(NOW), any());
  }

  private static List<BackupArtifact> stagedMocks(int count) {
    return IntStream.range(0, count)
        .mapToObj(i -> mock(BackupArtifact.class))
        .toList();
  }
}
