package com.orderpilot.application.services.integration;

import com.orderpilot.domain.integration.ChangeRequest;
import com.orderpilot.domain.integration.ConnectorFailureType;
import org.springframework.stereotype.Service;

@Service
public class DemoErpAdapterService {
  public ExternalCommandResult createDraftQuote(ChangeRequest request) {
    return execute("DEMO-QUOTE-", request);
  }

  public ExternalCommandResult createDraftOrder(ChangeRequest request) {
    return execute("DEMO-ORDER-", request);
  }

  public ExternalCommandResult fetchExternalStatus(ChangeRequest request) {
    return ExternalCommandResult.success(request.getExternalReference(), "DEMO_STATUS_OK", "Demo ERP status fetched locally; no network call performed");
  }

  private ExternalCommandResult execute(String prefix, ChangeRequest request) {
    if (request.getRequestPayloadJson() != null && request.getRequestPayloadJson().contains("\"simulateFailure\":true")) {
      return ExternalCommandResult.failure("DEMO_ERP_TRANSIENT_FAILURE", "Simulated demo ERP adapter transient failure", ConnectorFailureType.TRANSIENT_ERROR, true);
    }
    if (request.getRequestPayloadJson() != null && request.getRequestPayloadJson().contains("\"simulatePermanentFailure\":true")) {
      return ExternalCommandResult.failure("DEMO_ERP_PERMANENT_FAILURE", "Simulated demo ERP adapter permanent failure", ConnectorFailureType.PERMANENT_ERROR, false);
    }
    String id = request.getId().toString().replace("-", "").substring(0, 12).toUpperCase();
    return ExternalCommandResult.success(prefix + id, "DEMO_SUCCESS", "Demo ERP adapter generated a deterministic local external reference");
  }
}
