// OP-CAP-46F — Customer-Facing Tracking URL Mapping (pure helpers; no API transport).

const BACKEND_TRACKING_PREFIX = "/api/v1/public/order-tracking/";
const CUSTOMER_TRACKING_PREFIX = "/public/order-tracking/";

function trackingTokenSegment(path: string, prefix: string): string | null {
  if (!path.startsWith(prefix)) {
    return null;
  }
  const token = path.slice(prefix.length);
  if (!token || token.includes("/")) {
    return null;
  }
  return token;
}

export function toCustomerTrackingPath(trackingPath: string): string | null {
  if (typeof trackingPath !== "string") {
    return null;
  }
  const trimmed = trackingPath.trim();
  const frontendToken = trackingTokenSegment(trimmed, CUSTOMER_TRACKING_PREFIX);
  if (frontendToken) {
    return `${CUSTOMER_TRACKING_PREFIX}${frontendToken}`;
  }
  const backendToken = trackingTokenSegment(trimmed, BACKEND_TRACKING_PREFIX);
  if (backendToken) {
    return `${CUSTOMER_TRACKING_PREFIX}${backendToken}`;
  }
  return null;
}

export function toCustomerTrackingHref(trackingPath: string, origin?: string): string | null {
  const path = toCustomerTrackingPath(trackingPath);
  if (!path) {
    return null;
  }
  if (origin) {
    return `${origin.replace(/\/+$/, "")}${path}`;
  }
  return path;
}
