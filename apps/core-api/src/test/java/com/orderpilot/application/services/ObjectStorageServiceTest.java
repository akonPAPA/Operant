package com.orderpilot.application.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.intake.ObjectStorageRecord;
import com.orderpilot.domain.intake.ObjectStorageRecordRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ObjectStorageServiceTest {
  private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final String SHA_256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

  @TempDir Path storageRoot;

  private final ObjectStorageRecordRepository repository = mock(ObjectStorageRecordRepository.class);
  private final Clock clock = Clock.fixed(Instant.parse("2026-06-04T00:00:00Z"), ZoneOffset.UTC);

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void storesValidObjectUnderTenantScopedStorageRoot() throws Exception {
    when(repository.save(any(ObjectStorageRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));
    TenantContext.setTenantId(TENANT_ID);
    ObjectStorageService service = service();

    ObjectStorageRecord record = service.store("customer-rfq.pdf", "application/pdf", "safe bytes".getBytes(StandardCharsets.UTF_8));

    Path root = storageRoot.toAbsolutePath().normalize();
    Path target = root.resolve(record.getObjectKey()).normalize();
    assertThat(target.startsWith(root)).isTrue();
    assertThat(record.getObjectKey()).startsWith(TENANT_ID + "/" + record.getSha256Fingerprint() + "/");
    assertThat(record.getObjectKey()).endsWith(".pdf");
    assertThat(Files.readString(target)).isEqualTo("safe bytes");
  }

  @ParameterizedTest
  @ValueSource(strings = {"../evil", "..\\evil", "/tmp/evil", "C:\\evil", "object/key", "object\\key", "..%2fevil"})
  void rejectsUnsafeObjectIds(String objectId) {
    ObjectStorageService service = service();

    assertThatThrownBy(() -> service.resolveLocalObjectPath(TENANT_ID, SHA_256, objectId, ".pdf"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("object id");
  }

  @Test
  void rejectsUnsafeExtension() {
    ObjectStorageService service = service();

    assertThatThrownBy(() -> service.resolveLocalObjectPath(TENANT_ID, SHA_256, "server-object-1", ".jsp"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unsupported file extension");
  }

  @Test
  void rejectsControlCharactersAndNullBytesInObjectId() {
    ObjectStorageService service = service();

    assertThatThrownBy(() -> service.resolveLocalObjectPath(TENANT_ID, SHA_256, "evil" + (char) 0 + "name", ".pdf"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("object id");
    assertThatThrownBy(() -> service.resolveLocalObjectPath(TENANT_ID, SHA_256, "evil" + (char) 31 + "name", ".pdf"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("object id");
  }

  @Test
  void resolvedPathMustRemainUnderStorageRoot() {
    ObjectStorageService service = service();
    Path root = storageRoot.toAbsolutePath().normalize();

    Path target = service.resolveLocalObjectPath(TENANT_ID, SHA_256, "server-object-1", ".pdf");

    assertThat(target.startsWith(root)).isTrue();
  }

  private ObjectStorageService service() {
    return new ObjectStorageService(repository, new IntakeValidationService(), clock, storageRoot.toString());
  }
}
