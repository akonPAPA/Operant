/**
 * Fail-closed allowlist for BFF → Core proxy paths (segments after /api/bff/, without leading api).
 * Only operator API prefixes shipped for the dashboard may be forwarded.
 */

const ALLOWED_SEGMENT_PREFIXES = [
  ["api", "v1"],
  ["api", "stage8"],
  ["api", "stage9"]
] as const;

const DENIED_EXACT_SEGMENTS: string[][] = [
  ["api", "v1", "demo"],
  ["api", "v1", "bot", "telegram", "webhook"],
  ["api", "v1", "webhooks"],
  ["api", "v1", "public", "order-tracking"]
];

export function isBffProxyPathAllowed(segments: string[]): boolean {
  if (segments.length < 2) {
    return false;
  }
  const allowed = ALLOWED_SEGMENT_PREFIXES.some((prefix) =>
    prefix.every((part, index) => segments[index] === part)
  );
  if (!allowed) {
    return false;
  }
  for (const denied of DENIED_EXACT_SEGMENTS) {
    if (denied.length <= segments.length && denied.every((part, index) => segments[index] === part)) {
      if (denied.length === segments.length || denied[denied.length - 1] !== "webhooks") {
        if (segments.join("/").startsWith(denied.join("/"))) {
          return false;
        }
      }
    }
  }
  if (segments.join("/").includes("/webhooks/")) {
    return false;
  }
  if (segments.join("/").startsWith("api/v1/demo")) {
    return false;
  }
  return true;
}

export function corePathFromBffSegments(segments: string[]): string {
  return `/${segments.join("/")}`;
}
