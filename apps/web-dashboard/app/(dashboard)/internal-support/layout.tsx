import { notFound } from "next/navigation";

import { resolveInternalSupportFrontendAccess } from "@/lib/internal-support-access.mjs";

/**
 * Internal Support is an Operant staff plane, never a tenant dashboard capability.
 *
 * The repository has no real server-side staff session/BFF yet. Fail closed for every direct route
 * instead of deriving staff authority from browser-editable input. When a real staff session is
 * introduced, this boundary must resolve it server-side and Core API must still enforce STAFF_* plus
 * the active JIT grant.
 */
export default function InternalSupportLayout({
  children
}: Readonly<{ children: React.ReactNode }>) {
  const access = resolveInternalSupportFrontendAccess();
  if (!access.allowed) {
    notFound();
  }
  return children;
}
