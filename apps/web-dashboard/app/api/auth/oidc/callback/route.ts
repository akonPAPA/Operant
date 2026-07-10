import { NextResponse } from "next/server";
import {
  BFF_CSRF_COOKIE,
  BFF_SESSION_COOKIE,
  bffCookieSecure,
  bffRuntimeMode,
  bffSessionSecret
} from "@/lib/bff/bff-config";
import {
  isOidcLoginEnabled,
  mapOidcClaimsToSession,
  oidcClientId,
  oidcClientSecret,
  oidcIssuer,
  oidcRedirectUri
} from "@/lib/bff/oidc-config";
import { clearOidcPkceCookies, readOidcPkceCookies } from "@/lib/bff/oidc-pkce";
import { createSessionToken, newCsrfToken, sessionMaxAgeSeconds } from "@/lib/bff/bff-session";
import { newSessionId } from "@/lib/bff/bff-session-revocation";

const SAFE_FAILURE = "Sign-in could not be completed.";

export async function GET(request: Request) {
  if (bffRuntimeMode() !== "bff-production" || !isOidcLoginEnabled()) {
    return NextResponse.redirect(new URL("/login", request.url));
  }
  const url = new URL(request.url);
  const code = url.searchParams.get("code");
  const state = url.searchParams.get("state");
  const pkce = await readOidcPkceCookies();
  if (!code || !state || !pkce || state !== pkce.state) {
    return NextResponse.redirect(new URL("/login?error=oidc", request.url));
  }
  const tokenResponse = await fetch(`${oidcIssuer()}/oauth2/token`, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "authorization_code",
      client_id: oidcClientId(),
      client_secret: oidcClientSecret(),
      code,
      redirect_uri: oidcRedirectUri(),
      code_verifier: pkce.verifier
    })
  });
  if (!tokenResponse.ok) {
    return NextResponse.redirect(new URL("/login?error=token", request.url));
  }
  const tokenJson = (await tokenResponse.json()) as { access_token?: string };
  if (!tokenJson.access_token) {
    return NextResponse.redirect(new URL("/login?error=token", request.url));
  }
  const userInfoResponse = await fetch(`${oidcIssuer()}/oauth2/userinfo`, {
    headers: { Authorization: `Bearer ${tokenJson.access_token}` }
  });
  if (!userInfoResponse.ok) {
    return NextResponse.redirect(new URL("/login?error=userinfo", request.url));
  }
  const claims = (await userInfoResponse.json()) as Record<string, unknown>;
  const mapped = mapOidcClaimsToSession(claims);
  if (!mapped) {
    return NextResponse.redirect(new URL("/login?error=claims", request.url));
  }
  const secret = bffSessionSecret();
  if (!secret || secret.length < 32) {
    return NextResponse.redirect(new URL("/login?error=config", request.url));
  }
  const expiresAtEpochSec = Math.floor(Date.now() / 1000) + sessionMaxAgeSeconds();
  const token = createSessionToken(
    {
      sessionId: newSessionId(),
      tenantId: mapped.tenantId,
      actorId: mapped.actorId,
      permissions: mapped.permissions,
      expiresAtEpochSec
    },
    secret
  );
  const csrf = newCsrfToken();
  const response = NextResponse.redirect(new URL("/", request.url));
  clearOidcPkceCookies(response);
  const cookieBase = {
    httpOnly: true,
    secure: bffCookieSecure(),
    sameSite: "lax" as const,
    path: "/",
    maxAge: sessionMaxAgeSeconds()
  };
  response.cookies.set(BFF_SESSION_COOKIE, token, { ...cookieBase });
  response.cookies.set(BFF_CSRF_COOKIE, csrf, { ...cookieBase, httpOnly: false });
  return response;
}
