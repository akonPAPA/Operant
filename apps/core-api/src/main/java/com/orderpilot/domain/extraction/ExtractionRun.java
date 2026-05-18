package com.orderpilot.domain.extraction;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "extraction_run")
public class ExtractionRun {
  @Id @GeneratedValue private UUID id;
  @Column(name="tenant_id",nullable=false) private UUID tenantId;
  @Column(name="source_type",nullable=false) private String sourceType;
  @Column(name="source_id",nullable=false) private UUID sourceId;
  @Column(name="processing_job_id") private UUID processingJobId;
  @Column(nullable=false) private String status;
  @Column(name="provider_type",nullable=false) private String providerType;
  @Column(name="provider_name") private String providerName;
  @Column(name="model_name") private String modelName;
  @Column(name="prompt_version") private String promptVersion;
  @Column(name="schema_version",nullable=false) private String schemaVersion;
  @Column(name="started_at") private Instant startedAt;
  @Column(name="finished_at") private Instant finishedAt;
  @Column(name="error_message") private String errorMessage;
  @Column(name="created_at",nullable=false) private Instant createdAt;
  @Column(name="updated_at",nullable=false) private Instant updatedAt;
  protected ExtractionRun() {}
  public ExtractionRun(UUID tenantId, String sourceType, UUID sourceId, UUID processingJobId, String providerType, String providerName, String modelName, String promptVersion, String schemaVersion, Instant now) {
    this.tenantId=tenantId; this.sourceType=sourceType; this.sourceId=sourceId; this.processingJobId=processingJobId; this.status="CREATED"; this.providerType=providerType; this.providerName=providerName; this.modelName=modelName; this.promptVersion=promptVersion; this.schemaVersion=schemaVersion; this.createdAt=now; this.updatedAt=now;
  }
  public void markRunning(Instant now){this.status="RUNNING"; this.startedAt=now; this.updatedAt=now;}
  public void markSucceeded(Instant now){this.status="SUCCEEDED"; this.finishedAt=now; this.updatedAt=now;}
  public void markFailed(String error, Instant now){this.status="FAILED"; this.errorMessage=error; this.finishedAt=now; this.updatedAt=now;}
  public UUID getId(){return id;} public UUID getTenantId(){return tenantId;} public String getSourceType(){return sourceType;} public UUID getSourceId(){return sourceId;} public UUID getProcessingJobId(){return processingJobId;} public String getStatus(){return status;} public String getProviderType(){return providerType;} public String getProviderName(){return providerName;} public String getSchemaVersion(){return schemaVersion;} public Instant getCreatedAt(){return createdAt;}
}