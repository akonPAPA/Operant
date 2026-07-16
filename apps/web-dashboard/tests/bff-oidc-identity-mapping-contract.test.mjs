import assert from "node:assert/strict";
import test from "node:test";

import {
  createStaticOidcIdentityMappingResolver,
  resolveOidcIdentityMapping
} from "../lib/bff/bff-oidc-identity-mapping.ts";

const ISSUER = "https://idp.example.test/tenant-a";
const AUDIENCE = "operant-dashboard-client";
const TENANT_ID = "11111111-1111-4111-8111-111111111111";
const ACTOR_ID = "22222222-2222-4222-8222-222222222222";
const STAFF_ID = "33333333-3333-4333-8333-333333333333";
const SERVICE_ACCOUNT_ID = "44444444-4444-4444-8444-444444444444";
const CUSTOMER_ID = "55555555-5555-4555-8555-555555555555";

function principal(overrides = {}) {
  return {
    issuer: ISSUER,
    subject: "oidc-sub-tenant-user-1",
    audience: AUDIENCE,
    email: "operator@example.test",
    emailVerified: true,
    claims: {},
    tokenExpiresAtEpochSec: 4_102_444_800,
    ...overrides
  };
}

function tenantMapping(overrides = {}) {
  return {
    issuer: ISSUER,
    subject: "oidc-sub-tenant-user-1",
    audience: AUDIENCE,
    enabled: true,
    accessPlane: "TENANT_USER",
    tenantRef: TENANT_ID,
    actorRef: ACTOR_ID,
    bffPermissions: ["REVIEW_READ", "REVIEW_ACTION"],
    safeDisplayName: "Tenant Operator",
    safeEmail: "operator@example.test",
    requireEmail: "operator@example.test",
    mappingVersion: "fixture-v1",
    source: "STATIC_TEST_FIXTURE",
    ...overrides
  };
}

function staffMapping(overrides = {}) {
  return {
    issuer: ISSUER,
    subject: "oidc-sub-staff-1",
    audience: AUDIENCE,
    enabled: true,
    accessPlane: "OPERANT_SUPPORT",
    staffRef: STAFF_ID,
    safeDisplayName: "Support Engineer",
    safeEmail: "support@example.test",
    mappingVersion: "fixture-v1",
    source: "STATIC_TEST_FIXTURE",
    ...overrides
  };
}

function serviceAccountMapping(overrides = {}) {
  return {
    issuer: ISSUER,
    subject: "oidc-sub-service-1",
    audience: AUDIENCE,
    enabled: true,
    accessPlane: "SERVICE_ACCOUNT",
    serviceAccountRef: SERVICE_ACCOUNT_ID,
    safeDisplayName: "Connector Worker",
    mappingVersion: "fixture-v1",
    source: "STATIC_TEST_FIXTURE",
    ...overrides
  };
}

function externalCustomerMapping(overrides = {}) {
  return {
    issuer: ISSUER,
    subject: "oidc-sub-customer-1",
    audience: AUDIENCE,
    enabled: true,
    accessPlane: "EXTERNAL_CUSTOMER",
    externalCustomerRef: CUSTOMER_ID,
    safeDisplayName: "Buyer Contact",
    safeEmail: "buyer@example.test",
    mappingVersion: "fixture-v1",
    source: "STATIC_TEST_FIXTURE",
    ...overrides
  };
}

test("default OIDC identity mapping resolver fails closed without a server-side mapping source", () => {
  const result = resolveOidcIdentityMapping(principal());
  assert.equal(result.status, "DENIED_UNTRUSTED_ISSUER");
});

test("verified issuer and subject map to exactly one tenant-user identity with a safe session projection", () => {
  const resolve = createStaticOidcIdentityMappingResolver([tenantMapping()]);
  const result = resolve(principal({ claims: { harmless: "RAW_CLAIM_SENTINEL", access_token: "RAW_TOKEN_SENTINEL" } }));

  assert.equal(result.status, "MAPPED");
  assert.equal(result.accessPlane, "TENANT_USER");
  assert.equal(result.tenantRef, TENANT_ID);
  assert.equal(result.actorRef, ACTOR_ID);
  assert.deepEqual(result.safeProjection, {
    accessPlane: "TENANT_USER",
    tenantId: TENANT_ID,
    actorId: ACTOR_ID,
    permissions: ["REVIEW_READ", "REVIEW_ACTION"],
    displayName: "Tenant Operator",
    email: "operator@example.test"
  });

  const json = JSON.stringify(result);
  assert.doesNotMatch(json, /RAW_TOKEN_SENTINEL|RAW_CLAIM_SENTINEL|refresh_token|id_token|claims/i);
});

test("unknown subject, issuer, and audience deny fail-closed", () => {
  const resolve = createStaticOidcIdentityMappingResolver([tenantMapping()]);

  assert.equal(resolve(principal({ subject: "unknown-subject" })).status, "DENIED_NOT_FOUND");
  assert.equal(resolve(principal({ issuer: "https://other-idp.example.test" })).status, "DENIED_UNTRUSTED_ISSUER");
  assert.equal(resolve(principal({ audience: "wrong-client" })).status, "DENIED_UNTRUSTED_ISSUER");
  assert.equal(resolve(principal({ subject: "" })).status, "DENIED_NOT_FOUND");
});

test("disabled mapping and unverified required email deny without a session-ready result", () => {
  assert.equal(
    createStaticOidcIdentityMappingResolver([tenantMapping({ enabled: false })])(principal()).status,
    "DENIED_DISABLED"
  );
  assert.equal(
    createStaticOidcIdentityMappingResolver([tenantMapping()])(principal({ emailVerified: false })).status,
    "DENIED_UNVERIFIED_EMAIL"
  );
});

test("ambiguous subject mappings across access planes fail closed", () => {
  const resolve = createStaticOidcIdentityMappingResolver([
    tenantMapping({ requireEmail: undefined }),
    staffMapping({ subject: "oidc-sub-tenant-user-1" })
  ]);

  assert.equal(resolve(principal()).status, "DENIED_AMBIGUOUS");
});

test("staff identity maps only to the support plane and never inherits tenant authority", () => {
  const resolve = createStaticOidcIdentityMappingResolver([staffMapping()]);
  const result = resolve(principal({ subject: "oidc-sub-staff-1", email: "support@example.test" }));

  assert.equal(result.status, "MAPPED");
  assert.equal(result.accessPlane, "OPERANT_SUPPORT");
  assert.equal(result.staffRef, STAFF_ID);
  assert.equal("tenantRef" in result, false);
  assert.equal("actorRef" in result, false);
  assert.deepEqual(result.safeProjection, {
    accessPlane: "OPERANT_SUPPORT",
    staffRef: STAFF_ID,
    displayName: "Support Engineer",
    email: "support@example.test"
  });
});

test("tenant identity cannot become staff through claim-supplied staff authority", () => {
  const resolve = createStaticOidcIdentityMappingResolver([tenantMapping()]);
  const result = resolve(principal({ claims: { staffUserId: STAFF_ID, roles: ["STAFF_SUPPORT_READ"] } }));

  assert.equal(result.status, "DENIED_UNTRUSTED_CLAIM");
});

test("service-account mapping is not accepted through the browser-user OIDC mapping path", () => {
  const resolve = createStaticOidcIdentityMappingResolver([serviceAccountMapping()]);
  const result = resolve(principal({ subject: "oidc-sub-service-1", email: undefined, emailVerified: undefined }));

  assert.equal(result.status, "DENIED_UNSUPPORTED_PLANE");
  assert.equal("safeProjection" in result, false);
});

test("external customer mapping stays separate from tenant operator authority", () => {
  const resolve = createStaticOidcIdentityMappingResolver([externalCustomerMapping()]);
  const result = resolve(principal({ subject: "oidc-sub-customer-1", email: "buyer@example.test" }));

  assert.equal(result.status, "MAPPED");
  assert.equal(result.accessPlane, "EXTERNAL_CUSTOMER");
  assert.equal("tenantRef" in result, false);
  assert.equal("actorRef" in result, false);
  assert.deepEqual(result.safeProjection, {
    accessPlane: "EXTERNAL_CUSTOMER",
    externalCustomerRef: CUSTOMER_ID,
    displayName: "Buyer Contact",
    email: "buyer@example.test"
  });
});

test("claim-supplied tenant actor role or permission authority is rejected before mapping", () => {
  const resolve = createStaticOidcIdentityMappingResolver([tenantMapping()]);
  for (const claims of [
    { tenantId: "evil-tenant" },
    { actorId: "evil-actor" },
    { roles: ["ADMIN"] },
    { permissions: ["STAFF_SUPPORT_READ"] },
    { serviceAccountId: SERVICE_ACCOUNT_ID }
  ]) {
    const result = resolve(principal({ claims }));
    assert.equal(result.status, "DENIED_UNTRUSTED_CLAIM", JSON.stringify(claims));
  }
});

test("invalid mapping records deny instead of minting tenant staff or service authority", () => {
  for (const mapping of [
    tenantMapping({ bffPermissions: ["STAFF_SUPPORT_READ"] }),
    tenantMapping({ staffRef: STAFF_ID }),
    staffMapping({ tenantRef: TENANT_ID }),
    serviceAccountMapping({ bffPermissions: ["REVIEW_ACTION"] })
  ]) {
    const result = createStaticOidcIdentityMappingResolver([mapping])(principal({ subject: mapping.subject }));
    assert.equal(result.status, "DENIED_UNSUPPORTED_PLANE");
  }
});