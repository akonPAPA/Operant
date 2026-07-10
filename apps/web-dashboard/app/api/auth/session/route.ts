import { NextResponse } from "next/server";
import {
  BFF_CSRF_COOKIE,
  BFF_SESSION_COOKIE,
  bffCookieSecure,
  bffRuntimeMode,
  bffSessionSecret
} from "@/lib/bff/bff-config";
import { createSessionToken, newCsrfToken, sessionMaxAgeSeconds } from "@/lib/bff/bff-session";
import { newSessionId } from "@/lib/bff/bff-session-revocation";
import { isOidcLoginEnabled } from "@/lib/bff/oidc-config";

const SAFE_FAILURE = "Sign-in is not available.";

export async function POST() {
  if (isOidcLoginEnabled()) {
    return NextResponse.json({ message: SAFE_FAILURE }, { status: 503 });
  }
  if (bffRuntimeMode() !== "bff-production") {
    return NextResponse.json({ message: SAFE_FAILURE }, { status: 503 });
  }
  const secret = bffSessionSecret();
  if (!secret || secret.length < 32) {
    return NextResponse.json({ message: SAFE_FAILURE }, { status: 503 });
  }
  const tenantId = process.env.ORDERPILOT_BFF_BOOTSTRAP_TENANT_ID?.trim();
  const actorId = process.env.ORDERPILOT_BFF_BOOTSTRAP_ACTOR_ID?.trim();
  const permissions = process.env.ORDERPILOT_BFF_BOOTSTRAP_PERMISSIONS?.trim();
  if (!tenantId || !actorId || !permissions) {
    return NextResponse.json({ message: SAFE_FAILURE }, { status: 503 });
  }
  const expiresAtEpochSec = Math.floor(Date.now() / 1000) + sessionMaxAgeSeconds();
  const token = createSessionToken(
    {
      sessionId: newSessionId(),
      tenantId,
      actorId,
      permissions: permissions.split(",").map((p) => p.trim()).filter(Boolean),
      expiresAtEpochSec
    },
    secret
  );
  const csrf = newCsrfToken();
  const response = NextResponse.json({ ok: true });
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
