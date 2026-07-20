import assert from "node:assert/strict";
import test from "node:test";
import {
  navigationDestinations,
  legacyAliasMap,
  resolveCanonicalPath,
  destinationForPath,
  tenantPrimaryDestinations,
  paletteDestinations,
  breadcrumbTrailForPath,
  breadcrumbTrailForTitle,
  UNIVERSAL_TENANT_PATHS
} from "../components/navigation-registry.ts";
import { navigationItems, navigationGroupsForUploadCapability } from "../components/navigation.ts";

test("every canonical destination path is unique", () => {
  const paths = navigationDestinations.map((dest) => dest.path);
  assert.equal(new Set(paths).size, paths.length, "duplicate canonical path in registry");
});

test("every destination id is unique", () => {
  const ids = navigationDestinations.map((dest) => dest.id);
  assert.equal(new Set(ids).size, ids.length, "duplicate destination id in registry");
});

test("no two tenant primary destinations share the same href (dedupe proof)", () => {
  const hrefs = tenantPrimaryDestinations().map((dest) => dest.path);
  assert.equal(new Set(hrefs).size, hrefs.length, "duplicate tenant primary destination");
  // /analytics must appear exactly once (was previously duplicated as "Business Value").
  assert.equal(hrefs.filter((h) => h === "/analytics").length, 1);
});

test("tenant primary navigation never surfaces staff or customer planes", () => {
  const planes = new Set(tenantPrimaryDestinations().map((dest) => dest.plane));
  assert.deepEqual([...planes], ["TENANT"]);
  const paths = tenantPrimaryDestinations().map((dest) => dest.path);
  assert.equal(paths.includes("/internal-support"), false);
  assert.equal(paths.includes("/public/order-tracking"), false);
});

test("staff support destinations are OPERANT_STAFF plane, hidden from nav and palette (plane separation)", () => {
  const support = navigationDestinations.filter((dest) => dest.plane === "OPERANT_STAFF");
  assert.ok(support.length >= 1, "expected at least one staff destination");
  for (const dest of support) {
    assert.equal(dest.showInPrimaryNav, false, `${dest.path} must not be in tenant primary nav`);
    assert.equal(dest.paletteVisible, false, `${dest.path} must not be palette-visible`);
  }
});

test("legacy aliases resolve to existing canonical destinations", () => {
  assert.equal(resolveCanonicalPath("/audit"), "/audit-log");
  assert.equal(resolveCanonicalPath("/bot/conversations"), "/bot-conversations");
  for (const [alias, canonical] of Object.entries(legacyAliasMap)) {
    assert.notEqual(destinationForPath(alias), null, `alias ${alias} has no destination`);
    assert.equal(destinationForPath(alias)?.path, canonical);
    assert.ok(
      navigationDestinations.some((dest) => dest.path === canonical),
      `canonical ${canonical} for alias ${alias} must exist`
    );
  }
});

test("resolveCanonicalPath returns null for unknown paths", () => {
  assert.equal(resolveCanonicalPath("/does-not-exist"), null);
});

test("every destination declares plane, capability and availability metadata", () => {
  for (const dest of navigationDestinations) {
    assert.ok(
      ["TENANT", "EXTERNAL_CUSTOMER", "SERVICE", "OPERANT_STAFF", "PUBLIC", "INTERNAL_ONLY"].includes(dest.plane),
      `${dest.id} plane`
    );
    assert.ok(dest.capability === null || typeof dest.capability === "string", `${dest.id} capability`);
    assert.ok(
      ["AVAILABLE", "UPLOAD_CAPABILITY_GATED", "UNSUPPORTED"].includes(dest.availability),
      `${dest.id} availability`
    );
    assert.equal(typeof dest.section, "string");
    assert.ok(dest.section.length > 0);
  }
});

test("capability-aware filtering offers null-capability items and gates capability items", () => {
  const none = new Set();
  const offered = tenantPrimaryDestinations(none).map((dest) => dest.path);
  // Command Center requires no capability and is always offered.
  assert.equal(offered.includes("/command-center"), true);
  // Quote Review requires VIEW_REVIEW_QUEUE and must be withheld when the capability is absent.
  assert.equal(offered.includes("/quote-review"), false);

  const withReview = new Set(["VIEW_REVIEW_QUEUE"]);
  const offeredWithReview = tenantPrimaryDestinations(withReview).map((dest) => dest.path);
  assert.equal(offeredWithReview.includes("/quote-review"), true);
  assert.equal(offeredWithReview.includes("/command-center"), true);
});

test("palette destinations exclude non-palette and non-tenant entries", () => {
  const palette = paletteDestinations("TENANT");
  assert.ok(palette.every((dest) => dest.plane === "TENANT" && dest.paletteVisible));
  const palettePaths = palette.map((dest) => dest.path);
  assert.equal(palettePaths.includes("/internal-support"), false);
});

test("breadcrumb trail resolves section then destination for a path", () => {
  assert.deepEqual(breadcrumbTrailForPath("/quote-review"), [
    { label: "Work Queue" },
    { label: "Quote Review", path: "/quote-review" }
  ]);
  // Alias resolves to the canonical destination breadcrumb.
  assert.deepEqual(breadcrumbTrailForPath("/audit"), [
    { label: "Control Center" },
    { label: "Audit / Security", path: "/audit-log" }
  ]);
  assert.equal(breadcrumbTrailForPath("/unknown"), null);
});

test("breadcrumb trail resolves from a page title", () => {
  assert.deepEqual(breadcrumbTrailForTitle("Audit / Security"), [
    { label: "Control Center" },
    { label: "Audit / Security", path: "/audit-log" }
  ]);
  assert.equal(breadcrumbTrailForTitle("Not A Real Title"), null);
});

test("shell navigation view is a subset of the registry tenant plane (no drift)", () => {
  for (const item of navigationItems) {
    const dest = destinationForPath(item.href);
    assert.notEqual(dest, null, `nav href ${item.href} is not a known destination`);
    assert.equal(dest?.plane, "TENANT", `nav href ${item.href} must be a tenant destination`);
  }
});

test("shell navigation view has no duplicate hrefs (duplicate entries removed in WP1)", () => {
  const hrefs = navigationItems.map((item) => item.href);
  assert.equal(new Set(hrefs).size, hrefs.length, "duplicate href in shell navigation");
  // Regression guard for the specific WP1 dedupe removals.
  assert.equal(hrefs.includes("/bot/conversations"), false, "legacy /bot/conversations must not be a nav entry");
  assert.equal(hrefs.includes("/audit"), false, "legacy /audit must not be a nav entry");
  assert.equal(hrefs.filter((h) => h === "/analytics").length, 1, "/analytics must appear once");
});

test("upload capability filtering is preserved through the registry-backed nav view", () => {
  const localHrefs = navigationGroupsForUploadCapability("AVAILABLE_LOCAL_DEMO").flatMap((group) =>
    group.items.map((item) => item.href)
  );
  assert.equal(localHrefs.includes("/upload"), true);

  const productionHrefs = navigationGroupsForUploadCapability("NOT_AVAILABLE_PRODUCTION_BFF").flatMap((group) =>
    group.items.map((item) => item.href)
  );
  assert.equal(productionHrefs.includes("/upload"), false);
});

test("unsupported destinations are never offered in primary nav or palette", () => {
  const primary = tenantPrimaryDestinations().map((dest) => dest.path);
  const palette = paletteDestinations("TENANT").map((dest) => dest.path);
  assert.equal(primary.includes("/extractions"), false);
  assert.equal(primary.includes("/pricing"), false);
  assert.equal(primary.includes("/imports"), false);
  assert.equal(primary.includes("/audit-log"), false);
  assert.equal(primary.includes("/integrations"), false);
  assert.equal(primary.includes("/orders"), false);
  assert.equal(primary.includes("/exception-cockpit"), false);
  assert.equal(primary.includes("/channels"), false);
  assert.equal(primary.includes("/webhook-events"), false);
  assert.equal(primary.includes("/processing-jobs"), false);
  assert.equal(primary.includes("/sync-events"), false);
  assert.equal(palette.includes("/extractions"), false);
  assert.equal(palette.includes("/audit-log"), false);
  assert.equal(palette.includes("/integrations"), false);
});

test("upload requires VIEW_DOCUMENTS and is excluded from UNIVERSAL_TENANT_PATHS", () => {
  const upload = destinationForPath("/upload");
  assert.equal(upload?.capability, "VIEW_DOCUMENTS");
  assert.equal(upload?.availability, "UPLOAD_CAPABILITY_GATED");
  assert.equal(UNIVERSAL_TENANT_PATHS.has("/upload"), false);
  assert.equal(tenantPrimaryDestinations(new Set()).some((d) => d.path === "/upload"), false);
  assert.equal(tenantPrimaryDestinations(new Set(["VIEW_DOCUMENTS"])).some((d) => d.path === "/upload"), true);
});

test("TENANT capability null is reserved for proven universal destinations only", () => {
  for (const dest of navigationDestinations.filter((entry) => entry.plane === "TENANT")) {
    if (dest.capability === null) {
      assert.equal(
        UNIVERSAL_TENANT_PATHS.has(dest.path),
        true,
        `${dest.path} has capability null but is not in UNIVERSAL_TENANT_PATHS`
      );
    } else if (dest.availability !== "UNSUPPORTED") {
      assert.equal(
        UNIVERSAL_TENANT_PATHS.has(dest.path),
        false,
        `${dest.path} is universal-listed but declares a capability`
      );
    }
  }
});
