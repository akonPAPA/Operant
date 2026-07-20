/**
 * Minimal strict-object request-body allowlist for high-authority mutation routes.
 *
 * This is deliberately NOT a general schema framework. It answers exactly one question the
 * quote-authority contract needs: "does this already-canonicalized JSON body contain ONLY the
 * business-intent fields this route accepts, and nothing else?" Any unknown, authority
 * (tenant/actor/permission/role), server-owned state (status/approvalStatus/executionStatus),
 * or prototype-pollution key causes the whole body to be rejected — the caller must resend a
 * clean body, and the BFF makes zero Core calls for a rejected body.
 *
 * The validator runs on the value produced by JSON.parse (see canonicalizeJsonRequestBody).
 * JSON.parse materializes "__proto__"/"constructor"/"prototype" as own enumerable string keys
 * (it never walks the prototype chain), so an own-key allowlist check already rejects them; the
 * explicit FORBIDDEN_KEYS guard is belt-and-suspenders and documents intent.
 */

/** Keys that must never appear at any level, independent of the per-route allowlist. */
const FORBIDDEN_KEYS = new Set(["__proto__", "constructor", "prototype"]);

export type StrictBodyPolicy = {
  /** Exact set of accepted top-level object keys. Any other own key → reject. */
  readonly allowedKeys: readonly string[];
  /**
   * Optional: for a top-level field that holds an array of objects, the exact set of accepted
   * keys for each element. If the field is present it MUST be an array of plain objects, and
   * each element is checked against these keys. Absent field is allowed (element-level
   * requiredness stays a Core concern).
   */
  readonly arrayItemKeys?: Readonly<Record<string, readonly string[]>>;
};

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function ownKeysAllowed(object: Record<string, unknown>, allowed: readonly string[]): boolean {
  const allowSet = new Set(allowed);
  for (const key of Object.keys(object)) {
    if (FORBIDDEN_KEYS.has(key)) {
      return false;
    }
    if (!allowSet.has(key)) {
      return false;
    }
  }
  return true;
}

/**
 * True only if `parsed` is a plain object whose own keys (and any declared array-item object
 * keys) are all inside the route allowlist and free of prototype-pollution keys.
 */
export function bodyMatchesStrictPolicy(parsed: unknown, policy: StrictBodyPolicy): boolean {
  if (!isPlainObject(parsed)) {
    return false;
  }
  if (!ownKeysAllowed(parsed, policy.allowedKeys)) {
    return false;
  }
  if (policy.arrayItemKeys) {
    for (const [field, itemKeys] of Object.entries(policy.arrayItemKeys)) {
      const value = parsed[field];
      if (value === undefined) {
        continue;
      }
      if (!Array.isArray(value)) {
        return false;
      }
      for (const element of value) {
        if (!isPlainObject(element) || !ownKeysAllowed(element, itemKeys)) {
          return false;
        }
      }
    }
  }
  return true;
}
