// Wave 01 fail-closed staff-plane boundary.
//
// The dashboard currently has no server-side staff session/BFF convention. Until one exists, there
// is no trustworthy staff identity or STAFF_SUPPORT_READ authority that this Next application can
// propagate to Core API. Do not accept browser headers, query parameters, environment-exposed
// NEXT_PUBLIC values, staff ids, grant ids, or permission strings as a substitute.
const unavailable = Object.freeze({
  allowed: false,
  reason: "STAFF_AUTHENTICATION_UNAVAILABLE"
});

export function resolveInternalSupportFrontendAccess() {
  return unavailable;
}
