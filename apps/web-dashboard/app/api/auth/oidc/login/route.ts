import { createHash, randomBytes } from "node:crypto";
import { NextResponse } from "next/server";
import {
  isOidcLoginEnabled,
  oidcClientId,
  oidcIssuer,
  oidcRedirectUri,
  oidcScopes
} from "@/lib/bff/oidc-config";
import { setOidcPkceCookies } from "@/lib/bff/oidc-pkce";

function base64Url(buffer: Buffer): string {
  return buffer.toString("base64url");
}

export async function GET() {
  if (!isOidcLoginEnabled()) {
    return NextResponse.json({ message: "OIDC is not configured." }, { status: 503 });
  }
  const verifier = base64Url(randomBytes(32));
  const challenge = base64Url(createHash("sha256").update(verifier).digest());
  const state = base64Url(randomBytes(16));
  const params = new URLSearchParams({
    client_id: oidcClientId(),
    response_type: "code",
    scope: oidcScopes(),
    redirect_uri: oidcRedirectUri(),
    state,
    code_challenge: challenge,
    code_challenge_method: "S256"
  });
  const response = NextResponse.redirect(`${oidcIssuer()}/oauth2/authorize?${params.toString()}`);
  setOidcPkceCookies(response, state, verifier);
  return response;
}
