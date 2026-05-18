package com.orderpilot.domain.extraction;
import java.util.UUID; import org.springframework.data.jpa.repository.JpaRepository;
public interface PromptTemplateVersionRepository extends JpaRepository<PromptTemplateVersion, UUID> {}