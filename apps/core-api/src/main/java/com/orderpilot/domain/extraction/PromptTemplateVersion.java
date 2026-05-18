package com.orderpilot.domain.extraction;
import jakarta.persistence.*; import java.time.Instant; import java.util.UUID;
@Entity @Table(name="prompt_template_version")
public class PromptTemplateVersion {
  @Id @GeneratedValue private UUID id; @Column(nullable=false) private String name; @Column(nullable=false) private String version; @Column(nullable=false) private String purpose; @Column(name="schema_version",nullable=false) private String schemaVersion; @Column(nullable=false) private boolean active; @Column(name="created_at",nullable=false) private Instant createdAt; @Column(name="updated_at",nullable=false) private Instant updatedAt; protected PromptTemplateVersion() {}
}