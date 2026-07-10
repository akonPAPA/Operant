"use client";

import { useRouter, useSearchParams } from "next/navigation";
import { useState } from "react";

type Props = { oidcEnabled: boolean };

export default function LoginClient({ oidcEnabled }: Props) {
  const router = useRouter();
  const searchParams = useSearchParams();
  const [pending, setPending] = useState(false);
  const [message, setMessage] = useState("");

  async function signIn() {
    setPending(true);
    setMessage("");
    try {
      if (oidcEnabled) {
        window.location.href = "/api/auth/oidc/login";
        return;
      }
      const response = await fetch("/api/auth/session", { method: "POST" });
      if (!response.ok) {
        setMessage("Sign-in is not available.");
        return;
      }
      router.replace(searchParams.get("next") ?? "/");
    } catch {
      setMessage("Sign-in is not available.");
    } finally {
      setPending(false);
    }
  }

  return (
    <main style={{ margin: "4rem auto", maxWidth: 420, padding: "0 1rem" }}>
      <h1>Operant sign-in</h1>
      <p>{oidcEnabled ? "Production OIDC sign-in." : "Bootstrap session (until OIDC is configured)."}</p>
      {message ? <p role="status">{message}</p> : null}
      <button disabled={pending} onClick={() => void signIn()} type="button">
        {pending ? "Signing in…" : "Continue"}
      </button>
    </main>
  );
}
