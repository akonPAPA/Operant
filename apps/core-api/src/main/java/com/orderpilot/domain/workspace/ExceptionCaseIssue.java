package com.orderpilot.domain.workspace;

import jakarta.persistence.*; import java.time.Instant; import java.util.UUID;
@Entity @Table(name="exception_case_issue")
public class ExceptionCaseIssue {
  @Id @GeneratedValue private UUID id; @Column(name="tenant_id",nullable=false) private UUID tenantId; @Column(name="exception_case_id",nullable=false) private UUID exceptionCaseId; @Column(name="validation_issue_id") private UUID validationIssueId; @Column(name="issue_type",nullable=false) private String issueType; @Column(nullable=false) private String severity; @Column(nullable=false) private String status; @Column(nullable=false) private String message; @Column(name="created_at",nullable=false) private Instant createdAt; @Column(name="updated_at",nullable=false) private Instant updatedAt;
  protected ExceptionCaseIssue() {}
  public ExceptionCaseIssue(UUID tenantId, UUID caseId, UUID validationIssueId, String issueType, String severity, String status, String message, Instant now){this.tenantId=tenantId;this.exceptionCaseId=caseId;this.validationIssueId=validationIssueId;this.issueType=issueType;this.severity=severity;this.status=status;this.message=message;this.createdAt=now;this.updatedAt=now;}
  public void setStatus(String status, Instant now){this.status=status;this.updatedAt=now;} public UUID getId(){return id;} public UUID getExceptionCaseId(){return exceptionCaseId;} public UUID getValidationIssueId(){return validationIssueId;} public String getIssueType(){return issueType;} public String getSeverity(){return severity;} public String getStatus(){return status;} public String getMessage(){return message;}
}
