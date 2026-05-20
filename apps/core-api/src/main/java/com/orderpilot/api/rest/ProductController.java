package com.orderpilot.api.rest;

import com.orderpilot.api.dto.Stage2Dtos.ProductAliasRequest;
import com.orderpilot.api.dto.Stage2Dtos.ProductAliasResponse;
import com.orderpilot.api.dto.Stage2Dtos.ProductMatchResponse;
import com.orderpilot.api.dto.Stage2Dtos.ProductRequest;
import com.orderpilot.api.dto.Stage2Dtos.ProductResponse;
import com.orderpilot.application.services.ProductCatalogService;
import com.orderpilot.domain.product.Product;
import com.orderpilot.domain.product.ProductAlias;
import java.util.List;
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
@RequestMapping("/api/v1/products")
public class ProductController {
  private final ProductCatalogService service;
  public ProductController(ProductCatalogService service) { this.service = service; }

  @GetMapping public List<ProductResponse> list() { return service.list().stream().map(this::toResponse).toList(); }
  @GetMapping("/{id}") public ProductResponse get(@PathVariable UUID id) { return toResponse(service.get(id)); }
  @PostMapping public ProductResponse create(@RequestBody ProductRequest request) { return toResponse(service.create(request)); }
  @PatchMapping("/{id}") public ProductResponse update(@PathVariable UUID id, @RequestBody ProductRequest request) { return toResponse(service.update(id, request)); }
  @GetMapping("/search") public List<ProductResponse> search(@RequestParam(defaultValue = "") String query) { return service.search(query).stream().map(this::toResponse).toList(); }
  @GetMapping("/match") public ProductMatchResponse match(@RequestParam String code, @RequestParam(required = false) UUID customerAccountId) { return service.match(code, customerAccountId); }
  @GetMapping("/{id}/aliases") public List<ProductAliasResponse> aliases(@PathVariable UUID id) { return service.listAliases(id).stream().map(this::toAliasResponse).toList(); }
  @PostMapping("/{id}/aliases") public ProductAliasResponse addAlias(@PathVariable UUID id, @RequestBody ProductAliasRequest request) { return toAliasResponse(service.addAlias(id, request)); }

  private ProductResponse toResponse(Product p) { return new ProductResponse(p.getId(), p.getSku(), p.getName(), p.getCategory(), p.getStatus(), p.getBaseUom()); }
  private ProductAliasResponse toAliasResponse(ProductAlias a) { return new ProductAliasResponse(a.getId(), a.getProductId(), a.getAliasType(), a.getRawAlias(), a.getNormalizedAlias(), a.isActive()); }
}
