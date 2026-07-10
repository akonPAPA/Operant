import { Suspense } from "react";
import LoginClient from "./login-client";

export default function LoginPage() {
  return (
    <Suspense fallback={<main style={{ margin: "4rem auto" }}>Loading…</main>}>
      <LoginClient />
    </Suspense>
  );
}
