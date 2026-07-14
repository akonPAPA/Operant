import { type UploadCapability, uploadCapability } from "../lib/upload-capability.ts";

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

const baseNavigationGroups: NavigationGroup[] = [
  {
    label: "Command Center",
    href: "/command-center",
    code: "CC",
    items: [
      { label: "Command Center", href: "/command-center" },
      { label: "Analytics", href: "/analytics" },
      { label: "Pilot Readiness", href: "/pilot-readiness" },
      { label: "Pilot Evidence Report", href: "/pilot-readiness/evidence-report" },
      { label: "Pilot Demo Scenarios", href: "/pilot-readiness/demo-scenarios" },
      { label: "Investor Demo", href: "/demo" }
    ]
  },
  {
    label: "Inbox",
    href: "/inbox",
    code: "IN",
    items: [
      { label: "Inbox", href: "/inbox" },
      { label: "Upload", href: "/upload" },
      { label: "Documents", href: "/documents" },
      { label: "Messages", href: "/messages" },
      { label: "Extractions", href: "/extractions" },
      { label: "Processing Jobs", href: "/processing-jobs" }
    ]
  },
  {
    label: "Work Queue",
    href: "/validation-review",
    code: "WQ",
    items: [
      { label: "Validation Review", href: "/validation-review" },
      { label: "Exception Cockpit", href: "/exception-cockpit" },
      { label: "Conversion Review", href: "/conversion-review" },
      { label: "Quote Review", href: "/quote-review" },
      { label: "Review-Origin Drafts", href: "/workspace/review-drafts" },
      { label: "RFQ Handoffs", href: "/channels/rfq-handoffs" }
    ]
  },
  {
    label: "Transactions",
    href: "/quotes",
    code: "TX",
    items: [
      { label: "Draft Quotes", href: "/quotes" },
      { label: "Draft Orders", href: "/orders" },
      { label: "Draft Quote Review", href: "/workspace/draft-quotes" },
      { label: "Draft Order Review", href: "/workspace/draft-orders" },
      { label: "Order Journey", href: "/order-journey" }
    ]
  },
  {
    label: "Catalog",
    href: "/products",
    code: "CA",
    items: [
      { label: "Customers", href: "/customers" },
      { label: "Products", href: "/products" },
      { label: "Inventory", href: "/inventory" },
      { label: "Pricing", href: "/pricing" },
      { label: "Imports", href: "/imports" }
    ]
  },
  {
    label: "Intelligence",
    href: "/ai-work",
    code: "AI",
    items: [
      { label: "Commerce Intelligence", href: "/commerce-intelligence" },
      { label: "Runtime Control Telemetry", href: "/runtime-control" },
      { label: "AI Work Assistant", href: "/ai-work" },
      { label: "Reconciliation", href: "/reconciliation" },
      { label: "Business Value", href: "/analytics" }
    ]
  },
  {
    label: "Channels",
    href: "/channels",
    code: "CH",
    items: [
      { label: "Channels", href: "/channels" },
      { label: "Bot / Conversations", href: "/bot-conversations" },
      { label: "Bot Conversations", href: "/bot/conversations" },
      { label: "Messenger Bridge", href: "/messenger-bridge" },
      { label: "Channel Identities", href: "/channel-identities" },
      { label: "Inbound Events", href: "/inbound-events" },
      { label: "Webhook Events", href: "/webhook-events" },
      { label: "Bot Runtime", href: "/bot-runtime" },
      { label: "Bot Settings", href: "/bot-settings" }
    ]
  },
  {
    label: "Control Center",
    href: "/integrations",
    code: "CT",
    items: [
      { label: "Integrations", href: "/integrations" },
      { label: "Sync Events", href: "/sync-events" },
      { label: "Audit", href: "/audit" },
      { label: "Audit / Security", href: "/audit-log" },
    ]
  },
  {
    label: "Settings",
    href: "/settings",
    code: "ST",
    items: [
      { label: "Settings", href: "/settings" }
    ]
  }
];

export function navigationGroupsForUploadCapability(
  capability: UploadCapability = uploadCapability()
): NavigationGroup[] {
  if (capability === "AVAILABLE") {
    return baseNavigationGroups;
  }
  return baseNavigationGroups.map((group) => ({
    ...group,
    items: group.items.filter((item) => item.href !== "/upload")
  }));
}

export const navigationGroups: NavigationGroup[] = navigationGroupsForUploadCapability();
export const navigationItems: NavigationItem[] = navigationGroups.flatMap((group) => group.items);
