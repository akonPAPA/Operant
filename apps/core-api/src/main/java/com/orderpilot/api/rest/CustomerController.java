package com.orderpilot.api.rest;

import com.orderpilot.api.dto.Stage2Dtos.CustomerRequest;
import com.orderpilot.api.dto.Stage2Dtos.CustomerResponse;
import com.orderpilot.application.services.CustomerAccountService;
import com.orderpilot.domain.customer.CustomerAccount;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/customers")
public class CustomerController {
  private final CustomerAccountService service;
  public CustomerController(CustomerAccountService service) { this.service = service; }

  @GetMapping public List<CustomerResponse> list() { return service.list().stream().map(this::toResponse).toList(); }
  @GetMapping("/{id}") public CustomerResponse get(@PathVariable UUID id) { return toResponse(service.get(id)); }
  @PostMapping public CustomerResponse create(@RequestBody CustomerRequest request) { return toResponse(service.create(request)); }
  @PatchMapping("/{id}") public CustomerResponse update(@PathVariable UUID id, @RequestBody CustomerRequest request) { return toResponse(service.update(id, request)); }

  private CustomerResponse toResponse(CustomerAccount c) {
    return new CustomerResponse(c.getId(), c.getAccountCode(), c.getLegalName(), c.getDisplayName(), c.getSegmentId(), c.getStatus(), c.getDefaultCurrency());
  }
}