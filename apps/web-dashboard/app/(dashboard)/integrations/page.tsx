import { DashboardShell } from "@/components/dashboard-shell";
import { UnavailableState } from "@/components/page-states";

/**
 * Integrations is marked UNSUPPORTED in the navigation registry until a single coherent
 * surface contract exists. The page tree previously mixed ADMIN_SETTINGS_READ (integrations /
 * connector policy) with CHANGE_REQUEST_READ and converted denied change-request reads into
 * empty lists — indistinguishable from "no records".
 *
 * Preferred bounded closure: do not offer the destination; retain backend direct-route
 * authorization; show an honest unavailable state on deep link. No fake integration-control
 * completeness.
 */
export default function Page() {
  return (
    <DashboardShell title="Settings / Integrations">
      <UnavailableState
        title="Integrations unavailable"
        description="This workspace destination is not offered until integrations and change-request surfaces have separate, coherent authorization contracts."
        reason="Mixed ADMIN_SETTINGS_READ and CHANGE_REQUEST_READ reads previously made denied change-request access look like an empty queue. Direct Core/BFF authorization still applies to each registered route."
      />
    </DashboardShell>
  );
}
