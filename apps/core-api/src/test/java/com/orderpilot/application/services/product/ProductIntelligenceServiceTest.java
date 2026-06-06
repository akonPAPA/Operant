package com.orderpilot.application.services.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.orderpilot.api.dto.ProductIntelligenceDtos.ProductResolutionResult;
import com.orderpilot.api.dto.ProductIntelligenceDtos.SubstituteCandidate;
import com.orderpilot.api.dto.ProductIntelligenceDtos.VehicleContext;
import com.orderpilot.application.services.ProductCodeNormalizer;
import com.orderpilot.domain.inventory.InventorySnapshot;
import com.orderpilot.domain.inventory.InventorySnapshotRepository;
import com.orderpilot.domain.product.CompatibilityStatus;
import com.orderpilot.domain.product.CustomerPreferenceStatus;
import com.orderpilot.domain.product.CustomerSubstitutionPreference;
import com.orderpilot.domain.product.CustomerSubstitutionPreferenceRepository;
import com.orderpilot.domain.product.OEMReference;
import com.orderpilot.domain.product.OEMReferenceRepository;
import com.orderpilot.domain.product.Product;
import com.orderpilot.domain.product.ProductAlias;
import com.orderpilot.domain.product.ProductAliasRepository;
import com.orderpilot.domain.product.ProductCompatibility;
import com.orderpilot.domain.product.ProductCompatibilityRepository;
import com.orderpilot.domain.product.ProductMatchType;
import com.orderpilot.domain.product.ProductRepository;
import com.orderpilot.domain.product.ProductSubstitute;
import com.orderpilot.domain.product.ProductSubstituteRepository;
import com.orderpilot.domain.product.SubstituteRiskLevel;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProductIntelligenceServiceTest {
  private final ProductRepository productRepository = mock(ProductRepository.class);
  private final ProductAliasRepository productAliasRepository = mock(ProductAliasRepository.class);
  private final OEMReferenceRepository oemReferenceRepository = mock(OEMReferenceRepository.class);
  private final ProductSubstituteRepository productSubstituteRepository = mock(ProductSubstituteRepository.class);
  private final ProductCompatibilityRepository productCompatibilityRepository = mock(ProductCompatibilityRepository.class);
  private final CustomerSubstitutionPreferenceRepository customerSubstitutionPreferenceRepository = mock(CustomerSubstitutionPreferenceRepository.class);
  private final InventorySnapshotRepository inventorySnapshotRepository = mock(InventorySnapshotRepository.class);
  private final Clock clock = Clock.fixed(Instant.parse("2026-06-06T00:00:00Z"), ZoneOffset.UTC);

  private final ProductIntelligenceService service = new ProductIntelligenceService(
      productRepository, productAliasRepository, oemReferenceRepository, productSubstituteRepository,
      productCompatibilityRepository, customerSubstitutionPreferenceRepository, inventorySnapshotRepository, clock);

  private final UUID tenantId = UUID.randomUUID();

  // --- resolution ---

  @Test void resolvesExactSku() {
    UUID pid = stubSku("PAD-100", "Brake Pad");
    ProductResolutionResult r = service.resolveRequestedProduct(tenantId, "brake pads", "PAD-100", VehicleContext.empty());
    assertThat(r.matchType()).isEqualTo(ProductMatchType.EXACT_SKU);
    assertThat(r.productId()).isEqualTo(pid);
    assertThat(r.candidate().confidence()).isEqualByComparingTo("0.95");
  }

  @Test void resolvesAlias() {
    UUID pid = UUID.randomUUID();
    String norm = ProductCodeNormalizer.normalize("ALT-9");
    when(productRepository.findByTenantIdAndNormalizedSkuAndDeletedAtIsNullAndStatus(tenantId, norm, "ACTIVE")).thenReturn(List.of());
    ProductAlias alias = mock(ProductAlias.class);
    when(alias.getProductId()).thenReturn(pid);
    when(productAliasRepository.findByTenantIdAndNormalizedAliasAndActiveTrue(tenantId, norm)).thenReturn(List.of(alias));
    Product p = product(pid, "PAD-1", "Pad");
    when(productRepository.findByIdAndTenantIdAndDeletedAtIsNull(pid, tenantId)).thenReturn(Optional.of(p));

    ProductResolutionResult r = service.resolveRequestedProduct(tenantId, null, "ALT-9", VehicleContext.empty());
    assertThat(r.matchType()).isEqualTo(ProductMatchType.ALIAS);
  }

  @Test void resolvesOem() {
    UUID pid = UUID.randomUUID();
    String norm = ProductCodeNormalizer.normalize("OEM-55");
    when(productRepository.findByTenantIdAndNormalizedSkuAndDeletedAtIsNullAndStatus(tenantId, norm, "ACTIVE")).thenReturn(List.of());
    when(productAliasRepository.findByTenantIdAndNormalizedAliasAndActiveTrue(tenantId, norm)).thenReturn(List.of());
    OEMReference oem = mock(OEMReference.class);
    when(oem.getProductId()).thenReturn(pid);
    when(oemReferenceRepository.findByTenantIdAndNormalizedOemCodeAndActiveTrue(tenantId, norm)).thenReturn(List.of(oem));
    Product p = product(pid, "PAD-2", "Pad2");
    when(productRepository.findByIdAndTenantIdAndDeletedAtIsNull(pid, tenantId)).thenReturn(Optional.of(p));

    ProductResolutionResult r = service.resolveRequestedProduct(tenantId, null, "OEM-55", VehicleContext.empty());
    assertThat(r.matchType()).isEqualTo(ProductMatchType.OEM_REFERENCE);
  }

  @Test void ambiguousWhenMultipleSku() {
    String norm = ProductCodeNormalizer.normalize("DUP");
    Product a = product(UUID.randomUUID(), "DUP", "A");
    Product b = product(UUID.randomUUID(), "DUP", "B");
    when(productRepository.findByTenantIdAndNormalizedSkuAndDeletedAtIsNullAndStatus(tenantId, norm, "ACTIVE"))
        .thenReturn(List.of(a, b));
    ProductResolutionResult r = service.resolveRequestedProduct(tenantId, null, "DUP", VehicleContext.empty());
    assertThat(r.ambiguous()).isTrue();
    assertThat(r.matchType()).isEqualTo(ProductMatchType.NONE);
  }

  @Test void noMatchReturnsNoneNotThrow() {
    ProductResolutionResult r = service.resolveRequestedProduct(tenantId, null, "NOPE", VehicleContext.empty());
    assertThat(r.unmatched()).isTrue();
    assertThat(r.matchType()).isEqualTo(ProductMatchType.NONE);
  }

  @Test void deterministicTextCandidateIsLowConfidence() {
    UUID pid = UUID.randomUUID();
    Product p = product(pid, "PAD-9", "Brake Pad Premium");
    when(productRepository.findTop25ByTenantIdAndDeletedAtIsNullAndSkuContainingIgnoreCaseOrTenantIdAndDeletedAtIsNullAndNameContainingIgnoreCase(
        tenantId, "brake pad premium", tenantId, "brake pad premium")).thenReturn(List.of(p));
    when(productRepository.findByIdAndTenantIdAndDeletedAtIsNull(pid, tenantId)).thenReturn(Optional.of(p));

    ProductResolutionResult r = service.resolveRequestedProduct(tenantId, "brake pad premium", null, VehicleContext.empty());
    assertThat(r.matchType()).isEqualTo(ProductMatchType.TEXT_CANDIDATE);
    assertThat(r.candidate().confidence()).isEqualByComparingTo("0.50");
  }

  // --- compatibility ---

  @Test void compatibilityConfirmedThenConflict() {
    UUID pid = UUID.randomUUID();
    ProductCompatibility row = mock(ProductCompatibility.class);
    lenient().when(row.getCompatibleType()).thenReturn("VEHICLE");
    lenient().when(row.getMake()).thenReturn("Toyota");
    lenient().when(row.getModel()).thenReturn("Camry");
    lenient().when(row.getYearFrom()).thenReturn(2015);
    lenient().when(row.getYearTo()).thenReturn(2019);
    lenient().when(row.getRiskLevel()).thenReturn("LOW");
    when(productCompatibilityRepository.findByTenantIdAndProductIdAndActiveTrue(tenantId, pid)).thenReturn(List.of(row));

    assertThat(service.explainCompatibility(tenantId, pid, new VehicleContext("Toyota", "Camry", 2018, null)).status())
        .isEqualTo(CompatibilityStatus.CONFIRMED);
    assertThat(service.explainCompatibility(tenantId, pid, new VehicleContext("Toyota", "Camry", 2022, null)).status())
        .isEqualTo(CompatibilityStatus.CONFLICT);
    // Missing vehicle context must not produce a conflict.
    assertThat(service.explainCompatibility(tenantId, pid, VehicleContext.empty()).status())
        .isEqualTo(CompatibilityStatus.UNKNOWN);
  }

  // --- substitutes ---

  @Test void generatesSubstituteWithExplanation() {
    UUID source = UUID.randomUUID();
    UUID subId = UUID.randomUUID();
    stubSubstitute(source, subId, "OEM", "LOW", false);
    Product subProduct = product(subId, "SUB-1", "Alt Pad");
    when(productRepository.findByIdAndTenantIdAndDeletedAtIsNull(subId, tenantId)).thenReturn(Optional.of(subProduct));
    stubStock(subId, "999");

    List<SubstituteCandidate> subs = service.findSubstituteCandidates(tenantId, source, null, null, BigDecimal.ONE, VehicleContext.empty());
    assertThat(subs).hasSize(1);
    assertThat(subs.get(0).sku()).isEqualTo("SUB-1");
    assertThat(subs.get(0).explanation()).contains("SUB-1");
    assertThat(subs.get(0).stockStatus()).isEqualTo("AVAILABLE");
  }

  @Test void blockedSubstituteIsFlaggedAndRankedLast() {
    UUID source = UUID.randomUUID();
    UUID okId = UUID.randomUUID();
    UUID blockedId = UUID.randomUUID();
    UUID customerId = UUID.randomUUID();
    stubSubstitutes(source, List.of(sub(source, blockedId, "AFTERMARKET", "LOW", false), sub(source, okId, "OEM", "LOW", false)));
    Product blockedProduct = product(blockedId, "BLK", "Blocked");
    Product okProduct = product(okId, "OK", "Ok");
    when(productRepository.findByIdAndTenantIdAndDeletedAtIsNull(blockedId, tenantId)).thenReturn(Optional.of(blockedProduct));
    when(productRepository.findByIdAndTenantIdAndDeletedAtIsNull(okId, tenantId)).thenReturn(Optional.of(okProduct));
    stubStock(okId, "10");
    CustomerSubstitutionPreference pref = mock(CustomerSubstitutionPreference.class);
    lenient().when(pref.getProductId()).thenReturn(source);
    lenient().when(pref.getBlockedSubstituteProductId()).thenReturn(blockedId);
    lenient().when(pref.isAllowAftermarket()).thenReturn(true);
    when(customerSubstitutionPreferenceRepository.findByTenantIdAndCustomerAccountId(tenantId, customerId)).thenReturn(List.of(pref));

    List<SubstituteCandidate> subs = service.findSubstituteCandidates(tenantId, source, customerId, null, BigDecimal.ONE, VehicleContext.empty());
    assertThat(subs).hasSize(2);
    SubstituteCandidate blocked = subs.stream().filter(c -> c.substituteProductId().equals(blockedId)).findFirst().orElseThrow();
    assertThat(blocked.blocked()).isTrue();
    assertThat(blocked.customerPreferenceStatus()).isEqualTo(CustomerPreferenceStatus.BLOCKED);
    assertThat(blocked.riskLevel()).isEqualTo(SubstituteRiskLevel.BLOCKED);
    // Non-blocked, in-stock candidate ranks first.
    assertThat(subs.get(0).substituteProductId()).isEqualTo(okId);
  }

  @Test void highRiskSubstituteRequiresApproval() {
    UUID source = UUID.randomUUID();
    UUID subId = UUID.randomUUID();
    stubSubstitute(source, subId, "AFTERMARKET", "HIGH", true);
    Product subProduct = product(subId, "SUB-H", "High Risk");
    when(productRepository.findByIdAndTenantIdAndDeletedAtIsNull(subId, tenantId)).thenReturn(Optional.of(subProduct));
    stubStock(subId, "5");

    List<SubstituteCandidate> subs = service.findSubstituteCandidates(tenantId, source, null, null, BigDecimal.ONE, VehicleContext.empty());
    assertThat(subs.get(0).requiresApproval()).isTrue();
    assertThat(subs.get(0).riskLevel()).isEqualTo(SubstituteRiskLevel.HIGH);
  }

  @Test void noSubstitutesWhenNoSourceOrNoneConfigured() {
    assertThat(service.findSubstituteCandidates(tenantId, null, null, null, BigDecimal.ONE, VehicleContext.empty())).isEmpty();
    UUID source = UUID.randomUUID();
    assertThat(service.findSubstituteCandidates(tenantId, source, null, null, BigDecimal.ONE, VehicleContext.empty())).isEmpty();
  }

  @Test void tenantIsolationForResolution() {
    UUID otherTenant = UUID.randomUUID();
    String norm = ProductCodeNormalizer.normalize("PAD-100");
    Product otherTenantProduct = product(UUID.randomUUID(), "PAD-100", "Brake Pad");
    when(productRepository.findByTenantIdAndNormalizedSkuAndDeletedAtIsNullAndStatus(otherTenant, norm, "ACTIVE"))
        .thenReturn(List.of(otherTenantProduct));
    // Current tenant has no such product -> NONE.
    ProductResolutionResult r = service.resolveRequestedProduct(tenantId, null, "PAD-100", VehicleContext.empty());
    assertThat(r.matchType()).isEqualTo(ProductMatchType.NONE);
  }

  @Test void readOnlyNoMutations() {
    UUID source = UUID.randomUUID();
    UUID subId = UUID.randomUUID();
    stubSubstitute(source, subId, "OEM", "LOW", false);
    Product subProduct = product(subId, "SUB-1", "Alt");
    when(productRepository.findByIdAndTenantIdAndDeletedAtIsNull(subId, tenantId)).thenReturn(Optional.of(subProduct));
    stubStock(subId, "1");
    service.findSubstituteCandidates(tenantId, source, null, null, BigDecimal.ONE, VehicleContext.empty());
    service.resolveRequestedProduct(tenantId, "x", "PAD-100", VehicleContext.empty());

    verify(productRepository, never()).save(any());
    verify(productAliasRepository, never()).save(any());
    verify(oemReferenceRepository, never()).save(any());
    verify(productSubstituteRepository, never()).save(any());
    verify(productCompatibilityRepository, never()).save(any());
    verify(customerSubstitutionPreferenceRepository, never()).save(any());
    verify(inventorySnapshotRepository, never()).save(any());
  }

  // --- helpers ---

  private UUID stubSku(String sku, String name) {
    UUID pid = UUID.randomUUID();
    Product p = product(pid, sku, name);
    when(productRepository.findByTenantIdAndNormalizedSkuAndDeletedAtIsNullAndStatus(tenantId, ProductCodeNormalizer.normalize(sku), "ACTIVE"))
        .thenReturn(List.of(p));
    return pid;
  }

  private void stubSubstitute(UUID source, UUID subId, String type, String risk, boolean requiresApproval) {
    stubSubstitutes(source, List.of(sub(source, subId, type, risk, requiresApproval)));
  }

  private void stubSubstitutes(UUID source, List<ProductSubstitute> subs) {
    when(productSubstituteRepository.findByTenantIdAndSourceProductIdAndActiveTrue(tenantId, source)).thenReturn(subs);
  }

  private ProductSubstitute sub(UUID source, UUID subId, String type, String risk, boolean requiresApproval) {
    ProductSubstitute s = mock(ProductSubstitute.class);
    lenient().when(s.getSourceProductId()).thenReturn(source);
    lenient().when(s.getSubstituteProductId()).thenReturn(subId);
    lenient().when(s.getSubstituteType()).thenReturn(type);
    lenient().when(s.getRiskLevel()).thenReturn(risk);
    lenient().when(s.isRequiresApproval()).thenReturn(requiresApproval);
    return s;
  }

  private void stubStock(UUID productId, String available) {
    InventorySnapshot snap = mock(InventorySnapshot.class);
    lenient().when(snap.getQuantityAvailable()).thenReturn(new BigDecimal(available));
    lenient().when(snap.getCapturedAt()).thenReturn(clock.instant());
    when(inventorySnapshotRepository.findTop50ByTenantIdAndProductIdOrderByCapturedAtDesc(tenantId, productId)).thenReturn(List.of(snap));
  }

  private Product product(UUID id, String sku, String name) {
    Product product = mock(Product.class);
    lenient().when(product.getId()).thenReturn(id);
    lenient().when(product.getSku()).thenReturn(sku);
    lenient().when(product.getName()).thenReturn(name);
    return product;
  }
}
