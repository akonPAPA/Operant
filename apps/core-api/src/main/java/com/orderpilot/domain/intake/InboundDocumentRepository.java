package com.orderpilot.domain.intake;
import java.util.*; import org.springframework.data.jpa.repository.JpaRepository;
public interface InboundDocumentRepository extends JpaRepository<InboundDocument, UUID> {
  List<InboundDocument> findByTenantIdOrderByReceivedAtDesc(UUID tenantId);
  Optional<InboundDocument> findByIdAndTenantId(UUID id, UUID tenantId);
  Optional<InboundDocument> findFirstByTenantIdAndSha256FingerprintOrderByReceivedAtDesc(UUID tenantId, String sha256Fingerprint);
}