import { Suspense } from "react";
import LoginClient from "./login-client";
import { isOidcLoginEnabled } from "@/lib/bff/oidc-config";

export default function LoginPage() {
  return (
    <Suspense fallback={<main style={{ margin: "4rem auto" }}>Loading…</main>}>
      <LoginClient oidcEnabled={isOidcLoginEnabled()} />
    </Suspense>
  );
}
