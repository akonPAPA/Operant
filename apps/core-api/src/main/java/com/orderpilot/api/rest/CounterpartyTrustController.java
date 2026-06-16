package com.orderpilot.api.rest;

import com.orderpilot.api.dto.TrustDtos.CounterpartyTrustProfileView;
import com.orderpilot.api.dto.TrustDtos.CounterpartyTrustSignalView;
import com.orderpilot.api.dto.TrustDtos.CounterpartyTrustSnapshotView;
import com.orderpilot.application.services.trust.CounterpartyTrustProfileService;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * OP-CAP-17B Counterparty Trust Profile Foundation.
 *
 * Narrow, read-only, tenant-scoped counterparty trust read surface. Tenant is resolved from context;
 * the path counterparty id is never trusted across tenants (lookups are tenant-scoped in the service).
 * Guarded by the existing {@code /api/v1/trust} -> {@code TRUST_READ} permission prefix. No bank
 * fingerprint/hash or raw sensitive evidence is ever returned.
 */
@RestController
public class CounterpartyTrustController {
  private final CounterpartyTrustProfileService profileService;

  public CounterpartyTrustController(CounterpartyTrustProfileService profileService) {
    this.profileService = profileService;
  }

  @GetMapping("/api/v1/trust/counterparties/{counterpartyId}")
  public CounterpartyTrustProfileView getProfile(@PathVariable UUID counterpartyId) {
    return profileService.getProfileView(counterpartyId, CounterpartyTrustProfileService.DEFAULT_LIMIT,
        CounterpartyTrustProfileService.DEFAULT_LIMIT);
  }

  @GetMapping("/api/v1/trust/counterparties/{counterpartyId}/signals")
  public List<CounterpartyTrustSignalView> getSignals(
      @PathVariable UUID counterpartyId,
      @RequestParam(name = "limit", defaultValue = "25") int limit) {
    return profileService.listRecentSignals(counterpartyId, limit);
  }

  @GetMapping("/api/v1/trust/counterparties/{counterpartyId}/snapshots")
  public List<CounterpartyTrustSnapshotView> getSnapshots(
      @PathVariable UUID counterpartyId,
      @RequestParam(name = "limit", defaultValue = "25") int limit) {
    return profileService.listRecentSnapshots(counterpartyId, limit);
  }
}
