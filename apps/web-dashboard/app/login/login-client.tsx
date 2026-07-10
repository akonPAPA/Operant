"use client";

import { useRouter, useSearchParams } from "next/navigation";
import { useState } from "react";

export default function LoginClient() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const [pending, setPending] = useState(false);
  const [message, setMessage] = useState("");

  async function signIn() {
    setPending(true);
    setMessage("");
    try {
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
      <p>Production BFF session (bootstrap until OIDC in P1-C).</p>
      {message ? <p role="status">{message}</p> : null}
      <button disabled={pending} onClick={() => void signIn()} type="button">
        {pending ? "Signing in…" : "Continue"}
      </button>
    </main>
  );
}
