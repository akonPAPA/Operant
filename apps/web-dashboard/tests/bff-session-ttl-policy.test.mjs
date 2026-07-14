import assert from "node:assert/strict";
import { describe, it } from "node:test";
import {
  BFF_SESSION_TTL_DEFAULT_SECONDS,
  BFF_SESSION_TTL_MAX_SECONDS,
  BFF_SESSION_TTL_MIN_SECONDS,
  parseBffSessionMaxAgeSeconds
} from "../lib/bff/bff-session-ttl-policy.ts";

describe("BFF session TTL policy", () => {
  it("defaults to 28800", () => {
    const result = parseBffSessionMaxAgeSeconds(undefined);
    assert.equal(result.ok, true);
    assert.equal(result.seconds, BFF_SESSION_TTL_DEFAULT_SECONDS);
  });

  it("accepts min and max", () => {
    assert.deepEqual(parseBffSessionMaxAgeSeconds(String(BFF_SESSION_TTL_MIN_SECONDS)), {
      ok: true,
      seconds: BFF_SESSION_TTL_MIN_SECONDS
    });
    assert.deepEqual(parseBffSessionMaxAgeSeconds(String(BFF_SESSION_TTL_MAX_SECONDS)), {
      ok: true,
      seconds: BFF_SESSION_TTL_MAX_SECONDS
    });
  });

  it("rejects malformed and out-of-range values", () => {
    for (const raw of ["0", "-1", "1.5", "1e9", "86400abc", "+300", "90000"]) {
      const parsed = parseBffSessionMaxAgeSeconds(raw);
      assert.equal(parsed.ok, false);
    }
  });
});
