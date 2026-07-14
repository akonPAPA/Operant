/** Minimal cookie-header parsing (Edge-safe, dependency-free). First match wins (non-security cookies). */
export function readCookie(cookieHeader: string | null | undefined, name: string): string | undefined {
  if (!cookieHeader) {
    return undefined;
  }
  for (const part of cookieHeader.split(";")) {
    const trimmed = part.trim();
    if (trimmed.startsWith(`${name}=`)) {
      const value = trimmed.slice(name.length + 1);
      try {
        return decodeURIComponent(value);
      } catch {
        return undefined;
      }
    }
  }
  return undefined;
}

export type SecurityCookieResult =
  | { status: "missing" }
  | { status: "valid"; value: string }
  | {
      status: "invalid";
      reason: "duplicate" | "empty" | "malformed-encoding" | "invalid-format";
    };

export type SecurityCookiePolicy = {
  minLength: number;
  maxLength: number;
  pattern: RegExp;
};

const DEFAULT_SECURITY_COOKIE_POLICY: SecurityCookiePolicy = {
  minLength: 1,
  maxLength: 4096,
  pattern: /^[A-Za-z0-9_-]+$/
};

/**
 * Security cookies (op_session, op_csrf): typed fail-closed parsing that preserves the
 * difference between missing, duplicate, malformed and invalid values for callers.
 */
export function readSecurityCookie(
  cookieHeader: string | null | undefined,
  name: string,
  policy: SecurityCookiePolicy = DEFAULT_SECURITY_COOKIE_POLICY
): SecurityCookieResult {
  if (!cookieHeader) {
    return { status: "missing" };
  }
  const decodedValues: string[] = [];
  for (const part of cookieHeader.split(";")) {
    const trimmed = part.trim();
    if (!trimmed.startsWith(`${name}=`)) {
      continue;
    }
    const raw = trimmed.slice(name.length + 1);
    if (raw.length === 0) {
      return { status: "invalid", reason: "empty" };
    }
    let decoded: string;
    try {
      decoded = decodeURIComponent(raw);
    } catch {
      return { status: "invalid", reason: "malformed-encoding" };
    }
    if (
      decoded.length < policy.minLength ||
      decoded.length > policy.maxLength ||
      !policy.pattern.test(decoded)
    ) {
      return { status: "invalid", reason: "invalid-format" };
    }
    decodedValues.push(decoded);
  }
  if (decodedValues.length === 0) {
    return { status: "missing" };
  }
  if (decodedValues.length > 1) {
    return { status: "invalid", reason: "duplicate" };
  }
  return { status: "valid", value: decodedValues[0] };
}

export function readSecurityCookieHeader(
  cookieHeader: string | null | undefined,
  name: string,
  policy?: SecurityCookiePolicy
): string | undefined {
  const result = readSecurityCookie(cookieHeader, name, policy);
  return result.status === "valid" ? result.value : undefined;
}
