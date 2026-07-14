import assert from "node:assert/strict";
import test from "node:test";
import { safeInternalPath } from "../lib/safe-internal-path.ts";

test("valid internal paths are preserved", () => {
  assert.equal(safeInternalPath("/"), "/");
  assert.equal(safeInternalPath("/order-journey"), "/order-journey");
  assert.equal(safeInternalPath("/quotes/abc-123"), "/quotes/abc-123");
  assert.equal(safeInternalPath("/workspace/draft-quotes?status=OPEN"), "/workspace/draft-quotes?status=OPEN");
});

test("javascript: scheme resolves to /", () => {
  assert.equal(safeInternalPath("javascript:alert(1)"), "/");
  assert.equal(safeInternalPath("/javascript:alert(1)"), "/");
  assert.equal(safeInternalPath("%6Aavascript:alert(1)"), "/");
});

test("absolute external URLs resolve to /", () => {
  assert.equal(safeInternalPath("https://external.example"), "/");
  assert.equal(safeInternalPath("http://external.example/phish"), "/");
  assert.equal(safeInternalPath("/https://external.example"), "/");
});

test("protocol-relative //external resolves to /", () => {
  assert.equal(safeInternalPath("//external.example"), "/");
  assert.equal(safeInternalPath("//external.example/login"), "/");
});

test("encoded external-redirect variants resolve to /", () => {
  assert.equal(safeInternalPath("%2F%2Fexternal.example"), "/");
  assert.equal(safeInternalPath("/%2F%2Fexternal.example"), "/");
  assert.equal(safeInternalPath("/%2Fexternal.example"), "/");
  assert.equal(safeInternalPath("/%68%74%74%70%73%3A%2F%2Fexternal.example"), "/");
  assert.equal(safeInternalPath("/%252F%252Fexternal.example"), "/");
});

test("backslash, control characters, malformed encoding resolve to /", () => {
  assert.equal(safeInternalPath("/\\external.example"), "/");
  assert.equal(safeInternalPath("/%5C%5Cexternal.example"), "/");
  assert.equal(safeInternalPath(`/bad${String.fromCharCode(10)}path`), "/");
  assert.equal(safeInternalPath(`/bad${String.fromCharCode(0)}path`), "/");
  assert.equal(safeInternalPath("/%0d%0aSet-Cookie:x"), "/");
  assert.equal(safeInternalPath("/%zz"), "/");
});

test("duplicate slashes and empty values resolve to /", () => {
  assert.equal(safeInternalPath("/a//b"), "/");
  assert.equal(safeInternalPath(""), "/");
  assert.equal(safeInternalPath(null), "/");
  assert.equal(safeInternalPath(undefined), "/");
});
