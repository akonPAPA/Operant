package com.orderpilot.domain.intake;
import java.util.UUID; import org.springframework.data.jpa.repository.JpaRepository;
public interface InboundAttachmentRepository extends JpaRepository<InboundAttachment, UUID> {}