package com.orderpilot.api.rest;

import com.orderpilot.api.dto.Stage8Dtos.InventoryReconciliationRunRequest;
import com.orderpilot.api.dto.Stage8Dtos.ReconciliationCaseResponse;
import com.orderpilot.api.dto.Stage8Dtos.ReconciliationCaseStatusRequest;
import com.orderpilot.api.dto.Stage8Dtos.ReconciliationCasesResponse;
import com.orderpilot.api.dto.Stage8Dtos.ReconciliationRunResponse;
import com.orderpilot.application.services.reconciliation.InventoryReconciliationService;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/reconciliation")
public class ReconciliationController {
  private final InventoryReconciliationService service;

  public ReconciliationController(InventoryReconciliationService service) {
    this.service = service;
  }

  @PostMapping("/inventory/run")
  public ReconciliationRunResponse runInventory(@RequestBody InventoryReconciliationRunRequest request) {
    return service.runInventoryReconciliation(request.productId(), request.locationId());
  }

  @GetMapping("/cases")
  public ReconciliationCasesResponse cases(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
    return service.listCases(page, size);
  }

  @GetMapping("/cases/{caseId}")
  public ReconciliationCaseResponse get(@PathVariable UUID caseId) {
    return service.getCase(caseId);
  }

  @PatchMapping("/cases/{caseId}/status")
  public ReconciliationCaseResponse status(@PathVariable UUID caseId, @RequestBody ReconciliationCaseStatusRequest request) {
    return service.updateCaseStatus(caseId, request.status());
  }
}
