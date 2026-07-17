import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { join, resolve } from "node:path";
import test from "node:test";
import * as yaml from "js-yaml";
import {
  validateP1dProductionTopology,
  validateP1dProductionTopologyDocument
} from "../scripts/validate-p1d-production-topology.mjs";

const repoRoot = resolve(import.meta.dirname, "..", "..", "..");

function loadValidFixture() {
  return {
    compose: yaml.load(readFileSync(join(repoRoot, "infra", "docker", "docker-compose.production.yml"), "utf8")),
    nginxTemplate: readFileSync(join(repoRoot, "infra", "nginx", "templates", "operant.conf.template"), "utf8")
  };
}

function expectInvalid(name, mutate, expected) {
  test(`P1-D production topology validator rejects ${name}`, () => {
    const fixture = loadValidFixture();
    mutate(fixture);
    const failures = validateP1dProductionTopologyDocument(fixture.compose, fixture.nginxTemplate);
    assert.ok(
      failures.some((failure) => failure.includes(expected)),
      `expected failure containing ${expected}, got:\n${failures.join("\n")}`
    );
  });
}

test("P1-D production topology validator passes", () => {
  assert.deepEqual(validateP1dProductionTopology(repoRoot), []);
});

expectInvalid("public Core port", ({ compose }) => {
  compose.services["core-api"].ports = ["8080:8080"];
}, "core-api publishes host ports");

expectInvalid("public PostgreSQL port", ({ compose }) => {
  compose.services.postgres.ports = ["5432:5432"];
}, "postgres publishes host ports");

expectInvalid("public Redis port", ({ compose }) => {
  compose.services.redis.ports = ["6379:6379"];
}, "redis publishes host ports");

expectInvalid("privileged container", ({ compose }) => {
  compose.services["core-api"].privileged = true;
}, "core-api is privileged");

expectInvalid("Docker socket mount", ({ compose }) => {
  compose.services["web-dashboard"].volumes = ["/var/run/docker.sock:/var/run/docker.sock"];
}, "web-dashboard mounts Docker socket");

expectInvalid("host network", ({ compose }) => {
  compose.services["core-api"].network_mode = "host";
}, "core-api uses host networking");

expectInvalid("root application container", ({ compose }) => {
  compose.services["web-dashboard"].user = "0:0";
}, "web-dashboard is not explicit non-root uid/gid");

expectInvalid("development command", ({ compose }) => {
  compose.services["web-dashboard"].command = "next dev";
}, "web-dashboard uses development command");

expectInvalid("source bind mount", ({ compose }) => {
  compose.services["core-api"].volumes = ["../../apps/core-api:/app"];
}, "core-api uses unsafe source bind mount");

expectInvalid("missing security options", ({ compose }) => {
  compose.services["core-api"].security_opt = [];
}, "core-api missing no-new-privileges");

expectInvalid("missing health check", ({ compose }) => {
  delete compose.services.redis.healthcheck;
}, "redis missing healthcheck");

expectInvalid("ingress routing to Core", (fixture) => {
  fixture.nginxTemplate = fixture.nginxTemplate.replace(
    "proxy_pass http://web-dashboard:3000;",
    "proxy_pass http://core-api:8080;"
  );
}, "did not match");

expectInvalid("missing private network", ({ compose }) => {
  delete compose.networks.data_private;
}, "data_private");

expectInvalid("placeholder secret", ({ compose }) => {
  compose.services["core-api"].environment.ORDERPILOT_GATEWAY_SHARED_SECRET = "change-me-local-dev-only";
}, "change-me-local-dev-only");

expectInvalid("malformed Compose", (fixture) => {
  delete fixture.compose.services;
}, "Expected values to be strictly deep-equal");