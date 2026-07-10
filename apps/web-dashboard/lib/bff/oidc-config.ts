export function isOidcLoginEnabled(): boolean {
  return Boolean(
    process.env.ORDERPILOT_OIDC_ISSUER?.trim()
      && process.env.ORDERPILOT_OIDC_CLIENT_ID?.trim()
      && process.env.ORDERPILOT_OIDC_CLIENT_SECRET?.trim()
      && process.env.ORDERPILOT_OIDC_REDIRECT_URI?.trim()
  );
}

export function oidcIssuer(): string {
  return process.env.ORDERPILOT_OIDC_ISSUER!.replace(/\/$/, "");
}

export function oidcClientId(): string {
  return process.env.ORDERPILOT_OIDC_CLIENT_ID!.trim();
}

export function oidcClientSecret(): string {
  return process.env.ORDERPILOT_OIDC_CLIENT_SECRET!.trim();
}

export function oidcRedirectUri(): string {
  return process.env.ORDERPILOT_OIDC_REDIRECT_URI!.trim();
}

export function oidcScopes(): string {
  return process.env.ORDERPILOT_OIDC_SCOPES?.trim() || "openid profile email";
}

/** Server-side claim → session mapping (P1-C); values must come from trusted IdP claims only. */
export function mapOidcClaimsToSession(claims: Record<string, unknown>): {
  tenantId: string;
  actorId: string;
  permissions: string[];
} | null {
  const tenantClaim = process.env.ORDERPILOT_OIDC_CLAIM_TENANT_ID?.trim() || "op_tenant_id";
  const actorClaim = process.env.ORDERPILOT_OIDC_CLAIM_ACTOR_ID?.trim() || "sub";
  const permissionsClaim = process.env.ORDERPILOT_OIDC_CLAIM_PERMISSIONS?.trim() || "op_permissions";
  const tenantId = String(claims[tenantClaim] ?? "").trim();
  const actorId = String(claims[actorClaim] ?? "").trim();
  const permissionsRaw = claims[permissionsClaim];
  const permissions =
    typeof permissionsRaw === "string"
      ? permissionsRaw.split(",").map((p) => p.trim()).filter(Boolean)
      : Array.isArray(permissionsRaw)
        ? permissionsRaw.map((p) => String(p).trim()).filter(Boolean)
        : [];
  if (!tenantId || !actorId || permissions.length === 0) {
    return null;
  }
  return { tenantId, actorId, permissions };
}
