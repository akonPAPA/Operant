import assert from "node:assert/strict";
import { chmodSync, existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { execFileSync, spawnSync } from "node:child_process";
import { delimiter, join, resolve } from "node:path";
import { tmpdir } from "node:os";
import test from "node:test";
import * as yaml from "js-yaml";
import {
  validateP1dProductionTopology,
  validateP1dProductionTopologyDocument
} from "../scripts/validate-p1d-production-topology.mjs";

const repoRoot = resolve(import.meta.dirname, "..", "..", "..");
const deployScript = join(repoRoot, "scripts", "deploy", "operant-production.sh");

function loadValidFixture() {
  return {
    compose: yaml.load(readFileSync(join(repoRoot, "infra", "docker", "docker-compose.production.yml"), "utf8")),
    nginxTemplate: readFileSync(join(repoRoot, "infra", "nginx", "templates", "operant.conf.template"), "utf8"),
    artifacts: {
      deployScript: readFileSync(deployScript, "utf8"),
      systemdUnit: readFileSync(join(repoRoot, "infra", "systemd", "operant-production.service"), "utf8")
    }
  };
}

function validateFixture(fixture) {
  return validateP1dProductionTopologyDocument(fixture.compose, fixture.nginxTemplate, fixture.artifacts);
}

function expectInvalid(name, mutate, expected) {
  test(`P1-D production topology validator rejects ${name}`, () => {
    const fixture = loadValidFixture();
    mutate(fixture);
    const failures = validateFixture(fixture);
    assert.ok(
      failures.some((failure) => failure.includes(expected)),
      `expected failure containing ${expected}, got:\n${failures.join("\n")}`
    );
  });
}

function toBashPath(path) {
  return path.replace(/^([A-Za-z]):\\/, (_match, drive) => `/${drive.toLowerCase()}/`).replaceAll("\\", "/");
}

function fakeDockerHarness() {
  const dir = mkdtempSync(join(tmpdir(), "operant-p1d-"));
  const fakeDocker = join(dir, "docker");
  const logFile = join(dir, "docker.log");
  const envFile = join(dir, "production.env");
  writeFileSync(envFile, "OPERANT_SYNTHETIC_ENV=1\n", "utf8");
  writeFileSync(
    fakeDocker,
    `#!/usr/bin/env sh
printf '%s\\n' "$*" >> "$OPERANT_DOCKER_LOG"
case "$*" in
  *" up --help"*)
    if [ "\${OPERANT_FAKE_NO_WAIT:-}" = "1" ]; then
      echo "Usage: docker compose up"
    else
      echo "Usage: docker compose up --wait --wait-timeout"
    fi
    exit 0
    ;;
  *" config --quiet") exit 0 ;;
  *" up -d"*) exit "\${OPERANT_FAKE_UP_STATUS:-0}" ;;
  *" stop") exit 0 ;;
  *" ps") exit 0 ;;
  *" logs"*) exit 0 ;;
esac
exit 0
`,
    "utf8"
  );
  chmodSync(fakeDocker, 0o755);
  return { dir, logFile, envFile, cleanup: () => rmSync(dir, { recursive: true, force: true }) };
}

function runDeploy(action, options = {}) {
  const harness = fakeDockerHarness();
  const env = {
    ...process.env,
    PATH: `${toBashPath(harness.dir)}:${process.env.PATH ?? ""}`,
    OPERANT_ENV_FILE: toBashPath(harness.envFile),
    OPERANT_DOCKER_LOG: toBashPath(harness.logFile),
    OPERANT_COMPOSE_PROJECT: "operant-test",
    ORDERPILOT_REDIS_PASSWORD: "p/a?s#s@w:ord%25",
    ...(options.env ?? {})
  };
  if (options.missingEnvFile) {
    env.OPERANT_ENV_FILE = toBashPath(join(harness.dir, "missing.env"));
  }
  const result = spawnSync("bash", [toBashPath(deployScript), action], {
    cwd: repoRoot,
    env,
    encoding: "utf8"
  });
  const log = existsSync(harness.logFile) ? readFileSync(harness.logFile, "utf8") : "";
  harness.cleanup();
  return { ...result, log };
}

test("P1-D production topology validator passes", () => {
  assert.deepEqual(validateP1dProductionTopology(repoRoot), []);
});

test("deployment script is tracked executable in Git index", () => {
  const stage = execFileSync("git", ["ls-files", "--stage", "scripts/deploy/operant-production.sh"], {
    cwd: repoRoot,
    encoding: "utf8"
  });
  assert.match(stage, /^100755\s/);
});

test("deployment validate checks compose config and wait support", () => {
  const result = runDeploy("validate");
  assert.equal(result.status, 0, `${result.stdout}\n${result.stderr}`);
  assert.match(result.log, /config --quiet/);
  assert.match(result.log, /up --help/);
});

test("deployment start waits for bounded health", () => {
  const result = runDeploy("start", { env: { OPERANT_STARTUP_TIMEOUT_SECONDS: "31" } });
  assert.equal(result.status, 0, `${result.stdout}\n${result.stderr}`);
  assert.match(result.log, /up -d --remove-orphans --wait --wait-timeout 31/);
});

test("deployment start propagates unhealthy compose result", () => {
  const result = runDeploy("start", { env: { OPERANT_FAKE_UP_STATUS: "17" } });
  assert.equal(result.status, 17);
  assert.match(result.log, /up -d --remove-orphans --wait --wait-timeout 240/);
});

test("deployment restart recreates services and waits", () => {
  const result = runDeploy("restart", { env: { OPERANT_STARTUP_TIMEOUT_SECONDS: "60" } });
  assert.equal(result.status, 0, `${result.stdout}\n${result.stderr}`);
  assert.match(result.log, /up -d --force-recreate --remove-orphans --wait --wait-timeout 60/);
});

test("deployment rejects malformed timeout before Docker invocation and does not print secrets", () => {
  const result = runDeploy("start", { env: { OPERANT_STARTUP_TIMEOUT_SECONDS: "1e3" } });
  assert.equal(result.status, 2);
  assert.equal(result.log, "");
  assert.doesNotMatch(`${result.stdout}\n${result.stderr}`, /p\/a\?s#s@w:ord%25/);
});

test("deployment rejects missing env file before Docker invocation", () => {
  const result = runDeploy("start", { missingEnvFile: true });
  assert.equal(result.status, 2);
  assert.equal(result.log, "");
  assert.match(result.stderr, /Missing production env file/);
});

test("deployment stop does not delete named volumes", () => {
  const result = runDeploy("stop");
  assert.equal(result.status, 0, `${result.stdout}\n${result.stderr}`);
  assert.match(result.log, / stop$/m);
  assert.doesNotMatch(result.log, / down/);
});

test("deployment unexpected action exits 2 before Docker invocation", () => {
  const result = runDeploy("explode");
  assert.equal(result.status, 2);
  assert.equal(result.log, "");
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

expectInvalid("BFF data network reachability", ({ compose }) => {
  compose.services["web-dashboard"].networks.push("data_private");
}, "web-dashboard has wrong networks");

expectInvalid("Core public edge membership", ({ compose }) => {
  compose.services["core-api"].networks.push("public_edge");
}, "core-api has wrong networks");

expectInvalid("PostgreSQL public edge membership", ({ compose }) => {
  compose.services.postgres.networks.push("public_edge");
}, "postgres has wrong networks");

expectInvalid("Redis public edge membership", ({ compose }) => {
  compose.services.redis.networks.push("public_edge");
}, "redis has wrong networks");

expectInvalid("Redis missing application network", ({ compose }) => {
  compose.services.redis.networks = ["data_private"];
}, "redis has wrong networks");

expectInvalid("Redis missing data network", ({ compose }) => {
  compose.services.redis.networks = ["application_private"];
}, "redis has wrong networks");

expectInvalid("ingress private network reachability", ({ compose }) => {
  compose.services.ingress.networks.push("application_private");
}, "ingress has wrong networks");

expectInvalid("credential-bearing BFF Redis URL", ({ compose }) => {
  compose.services["web-dashboard"].environment.ORDERPILOT_BFF_REDIS_URL = "redis://:secret@redis:6379";
}, "ORDERPILOT_BFF_REDIS_URL is not allowed");

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

expectInvalid("missing stateful resource controls", ({ compose }) => {
  delete compose.services.postgres.mem_limit;
}, "postgres missing mem_limit");

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