package com.orderpilot.api.rest;

import com.orderpilot.api.dto.TrustDtos.DocumentTrustRunView;
import com.orderpilot.application.services.trust.DocumentTrustService;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * OP-CAP-17A Document Trust Signal Foundation.
 *
 * Narrow, read-only, tenant-scoped trust read surface. Returns the bounded deterministic trust
 * decision and signals for a single run. No write/list endpoints are exposed in this stage.
 */
@RestController
public class DocumentTrustController {
  private final DocumentTrustService trustService;

  public DocumentTrustController(DocumentTrustService trustService) {
    this.trustService = trustService;
  }

  @GetMapping("/api/v1/trust/document-runs/{id}")
  public DocumentTrustRunView getDocumentRun(@PathVariable UUID id) {
    return trustService.getRunView(id);
  }
}
