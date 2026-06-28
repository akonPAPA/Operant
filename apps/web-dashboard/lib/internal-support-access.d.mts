export type InternalSupportFrontendAccess = Readonly<{
  allowed: false;
  reason: "STAFF_AUTHENTICATION_UNAVAILABLE";
}>;

export function resolveInternalSupportFrontendAccess(): InternalSupportFrontendAccess;
