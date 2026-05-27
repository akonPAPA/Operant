package com.orderpilot.api.rest;

import com.orderpilot.api.dto.Stage8Dtos.ReconciliationCaseResponse;
import com.orderpilot.api.dto.Stage8Dtos.ReconciliationCasesResponse;
import com.orderpilot.api.dto.Stage8Dtos.Stage8ProductTimelineResponse;
import com.orderpilot.api.dto.Stage8Dtos.Stage8ReconciliationRefreshResponse;
import com.orderpilot.api.dto.Stage8Dtos.Stage8ReconciliationSummaryResponse;
import com.orderpilot.application.services.reconciliation.InventoryReconciliationService;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class Stage8ReconciliationController {
  private final InventoryReconciliationService service;

  public Stage8ReconciliationController(InventoryReconciliationService service) {
    this.service = service;
  }

  @GetMapping("/api/stage8/reconciliation/summary")
  public Stage8ReconciliationSummaryResponse summary() {
    return service.summary();
  }

  @GetMapping("/api/stage8/reconciliation/cases")
  public ReconciliationCasesResponse cases(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
    return service.listCases(page, size);
  }

  @GetMapping("/api/stage8/reconciliation/cases/{caseId}")
  public ReconciliationCaseResponse caseDetail(@PathVariable UUID caseId) {
    return service.getCase(caseId);
  }

  @GetMapping("/api/stage8/reconciliation/products/{productId}/timeline")
  public Stage8ProductTimelineResponse productTimeline(@PathVariable UUID productId) {
    return service.productTimeline(productId);
  }

  @PostMapping("/api/stage8/reconciliation/refresh")
  public Stage8ReconciliationRefreshResponse refresh() {
    return service.refreshProjections();
  }
}
