import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import test from "node:test";
import { registeredBffRoutes } from "../lib/bff/bff-route-registry.ts";
import {
  navigationDestinations,
  paletteDestinations,
  tenantPrimaryDestinations
} from "../components/navigation-registry.ts";
import { shippedTenantSurfaceContracts } from "../lib/frontend-surface-contract.ts";
import { PERMISSION_TO_UI_CAPABILITY, projectPermissionsToUiCapabilities } from "../lib/ui-capability-model.ts";

const ROOT = join(process.cwd());

const FORBIDDEN_SEGMENT_PREFIXES = [
  "internal",
  "control",
  "support",
  "maintenance",
  "lifecycle",
  "backups",
  "restores",
  "upgrades",
  "rollbacks"
];

const FORBIDDEN_LABELS =
  /backup|restore|upgrade|rollback|lifecycle operations|control plane|support operations/i;

function normalizedSegments(pathname) {
  return pathname.split("/").filter(Boolean).map((s) => s.toLowerCase());
}

test("shipped tenant surfaces exclude staff/control path segments", () => {
  for (const surface of shippedTenantSurfaceContracts()) {
    const segments = normalizedSegments(surface.canonicalPath);
    for (const forbidden of FORBIDDEN_SEGMENT_PREFIXES) {
      assert.equal(
        segments.includes(forbidden),
        false,
        `${surface.canonicalPath} must not contain segment ${forbidden}`
      );
    }
  }
});

test("tenant navigation and palette exclude lifecycle/control labels", () => {
  const allTenantLabels = [
    ...tenantPrimaryDestinations(
      new Set(Object.values(PERMISSION_TO_UI_CAPABILITY))
    ).map((d) => d.label),
    ...paletteDestinations("TENANT").map((d) => d.label)
  ];
  for (const label of allTenantLabels) {
    assert.equal(FORBIDDEN_LABELS.test(label), false, `forbidden label in tenant nav: ${label}`);
  }
});

test("tenant BFF registry does not proxy internal control routes", () => {
  for (const rule of registeredBffRoutes()) {
    const pattern = rule.pattern.toLowerCase();
    assert.equal(pattern.includes("internal/control"), false, rule.pattern);
    assert.equal(pattern.startsWith("api/v1/internal/control"), false, rule.pattern);
  }
});

test("staff and executor permissions do not map to tenant UI capabilities", () => {
  const staffLike = [
    "STAFF_CONTROL_READ",
    "STAFF_CONTROL_DIAGNOSE",
    "STAFF_CONTROL_OPERATIONAL_EVENT_READ",
    "INTERNAL_CONTROL_READ",
    "LIFECYCLE_EXECUTOR_LEASE",
    "LIFECYCLE_BACKUP_EXECUTE"
  ];
  const caps = projectPermissionsToUiCapabilities(staffLike);
  assert.equal(caps.size, 0);
});

test("frontend sources do not reference control HMAC or executor credentials", () => {
  const paths = [
    "lib/bff/bff-route-registry.ts",
    "lib/ui-capability-model.ts",
    "components/navigation-registry.ts",
    "lib/quote-transaction-api.ts"
  ];
  const forbidden =
    /controlHmac|lifecycleExecutor|staffPrivateKey|releaseSigningKey|backupEncryptionKey|HMAC_SECRET/i;
  for (const rel of paths) {
    const source = readFileSync(join(ROOT, rel), "utf8");
    assert.equal(forbidden.test(source), false, rel);
  }
});

test("safe workspace resolver module stays tenant-plane only", () => {
  const source = readFileSync(join(ROOT, "lib/server/resolve-safe-workspace.server.ts"), "utf8");
  assert.doesNotMatch(source, /\/internal-support/);
  assert.doesNotMatch(source, /\/control/);
  assert.match(source, /resolveSafeWorkspacePath/);
});

test("non-tenant navigation entries remain unsupported for tenant offer paths", () => {
  const nonTenant = navigationDestinations.filter((d) => d.plane !== "TENANT");
  assert.ok(nonTenant.length >= 1);
  for (const dest of nonTenant) {
    assert.equal(tenantPrimaryDestinations().some((d) => d.path === dest.path), false, dest.path);
    assert.equal(paletteDestinations("TENANT").some((d) => d.path === dest.path), false, dest.path);
  }
});
