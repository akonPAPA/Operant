package com.orderpilot.api.rest;

import com.orderpilot.api.dto.Stage2Dtos.InventorySnapshotRequest;
import com.orderpilot.api.dto.Stage2Dtos.InventorySnapshotResponse;
import com.orderpilot.application.services.InventorySnapshotService;
import com.orderpilot.domain.inventory.InventorySnapshot;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/inventory")
public class InventoryController {
  private final InventorySnapshotService service;
  public InventoryController(InventorySnapshotService service) { this.service = service; }

  @GetMapping
  public List<InventorySnapshotResponse> list(@RequestParam(required = false) UUID productId, @RequestParam(required = false) UUID locationId) {
    return service.latest(productId, locationId).stream().map(this::toResponse).toList();
  }

  @GetMapping("/latest")
  public List<InventorySnapshotResponse> latest(@RequestParam(required = false) UUID productId, @RequestParam(required = false) UUID locationId) {
    return service.latest(productId, locationId).stream().map(this::toResponse).toList();
  }

  @PostMapping("/snapshots")
  public InventorySnapshotResponse create(@RequestBody InventorySnapshotRequest request) {
    return toResponse(service.create(request));
  }

  private InventorySnapshotResponse toResponse(InventorySnapshot s) {
    return new InventorySnapshotResponse(s.getId(), s.getProductId(), s.getLocationId(), s.getQuantityOnHand(), s.getQuantityAvailable(), s.getCapturedAt());
  }
}