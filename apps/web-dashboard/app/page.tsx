import { redirect } from "next/navigation";
import { resolveSafeWorkspacePath } from "@/lib/server/resolve-safe-workspace.server";

/**
 * Authenticated entry resolves a server-owned safe destination.
 * Never selects landing from client-supplied role, browser storage, or query parameters.
 */
export default async function Home() {
  const destination = await resolveSafeWorkspacePath();
  if (destination.reason === "SESSION_DENIED") {
    redirect("/login");
  }
  redirect(destination.path);
}
