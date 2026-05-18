package com.orderpilot.domain.intake;
import java.util.*; import org.springframework.data.jpa.repository.JpaRepository;
public interface ObjectStorageRecordRepository extends JpaRepository<ObjectStorageRecord, UUID> {
  Optional<ObjectStorageRecord> findByTenantIdAndSha256Fingerprint(UUID tenantId, String sha256Fingerprint);
}