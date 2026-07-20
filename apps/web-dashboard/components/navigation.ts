import { type UploadCapability, uploadCapability } from "../lib/upload-capability.ts";
import { tenantNavigationGroupsFromRegistry } from "./navigation-registry.ts";

/**
 * WP1/WP3 — shell-facing tenant navigation view.
 *
 * The navigation registry is the single source of truth. This module only:
 * - groups registry destinations for the sidebar
 * - applies upload availability gating
 *
 * Capability offer-filtering is applied by callers that pass a UI capability set into
 * `navigationGroupsForUploadCapability` / `tenantNavigationGroupsFromRegistry`.
 */

export type NavigationItem = {
  label: string;
  href: string;
  badge?: string;
};

export type NavigationGroup = {
  label: string;
  href: string;
  code: string;
  items: NavigationItem[];
};

export function navigationGroupsForUploadCapability(
  capability: UploadCapability = uploadCapability(),
  uiCapabilities?: ReadonlySet<string>
): NavigationGroup[] {
  const groups = tenantNavigationGroupsFromRegistry(uiCapabilities).map((group) => ({
    label: group.label,
    href: group.href,
    code: group.code,
    items: group.items.map((item) => ({ label: item.label, href: item.href }))
  }));
  if (capability === "AVAILABLE_LOCAL_DEMO") {
    return groups;
  }
  return groups
    .map((group) => ({
      ...group,
      items: group.items.filter((item) => item.href !== "/upload")
    }))
    .filter((group) => group.items.length > 0);
}

export const navigationGroups: NavigationGroup[] = navigationGroupsForUploadCapability();
export const navigationItems: NavigationItem[] = navigationGroups.flatMap((group) => group.items);
