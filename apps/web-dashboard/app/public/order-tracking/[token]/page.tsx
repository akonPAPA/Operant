import type { Metadata } from "next";

import { PublicOrderTracking } from "@/components/public-order-tracking";

// OP-CAP-46E — minimal public, unauthenticated customer tracking route.
//
// Lives OUTSIDE the (dashboard) route group so it inherits no dashboard chrome, navigation, or
// session expectation — only the root <html>/<body> layout. The token comes from the path and is
// passed straight to the server-side fetch; it is never persisted or logged. Read-only: there are
// no mutation controls on this page.
export const metadata: Metadata = {
  title: "Order tracking"
};

// Always render fresh — tracking status must never be served from a stale cache.
export const dynamic = "force-dynamic";

export default async function PublicOrderTrackingPage({
  params
}: Readonly<{ params: Promise<{ token: string }> }>) {
  const { token } = await params;
  return <PublicOrderTracking token={token} />;
}
