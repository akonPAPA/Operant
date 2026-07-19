import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { isAbsolute, join, normalize, resolve } from "node:path";
import { pathToFileURL } from "node:url";
import * as yaml from "js-yaml";

const forbiddenAuthorityHeaders = [
  "X-Tenant-Id",
  "X-OrderPilot-Tenant-Id",
  "X-OrderPilot-Actor-Id",
  "X-OrderPilot-Permissions",
  "X-OrderPilot-Gateway-Timestamp",
  "X-OrderPilot-Gateway-Nonce",
  "X-OrderPilot-Signature-Version",
  "X-OrderPilot-Content-SHA256",
  "X-OrderPilot-Gateway-Signature",
  "X-OrderPilot-Actor-Signature",
  "X-OrderPilot-Actor-Timestamp",
  "X-OrderPilot-Staff-Grant",
  "X-OrderPilot-Gateway-Key",
  "X-OrderPilot-Control-Credential",
  "X-OrderPilot-Control-Audience",
  "X-OrderPilot-Control-Timestamp",
  "X-OrderPilot-Control-Nonce",
  "X-OrderPilot-Control-Signature-Version",
  "X-OrderPilot-Control-Content-SHA256",
  "X-OrderPilot-Control-Signature"
];

const operantOwned = ["core-api", "web-dashboard"];
const requiredServices = ["core-api", "ingress", "postgres", "redis", "web-dashboard"];
const requiredNetworks = ["application_private", "data_private", "public_edge"];
const expectedNetworkMembership = {
  ingress: ["public_edge"],
  "web-dashboard": ["application_private", "public_edge"],
  "core-api": ["application_private", "data_private"],
  postgres: ["data_private"],
  redis: ["application_private", "data_private"]
};
const placeholderSecretPattern = /change-me-local-dev-only|your-secret-here|placeholder|changeme|password123/i;
const credentialRedisUrlPattern = /ORDERPILOT_BFF_REDIS_URL|REDIS_URL|redis:\/\/:|@redis:|redis:\/\/[^\s"']*@/i;

function asArray(value) {
  if (value === undefined || value === null) return [];
  return Array.isArray(value) ? value : [value];
}

function asObject(value) {
  return value && typeof value === "object" && !Array.isArray(value) ? value : {};
}

function serviceNetworks(service) {
  const networks = service?.networks;
  if (Array.isArray(networks)) return networks;
  return Object.keys(asObject(networks));
}

function hasHostPorts(service) {
  return asArray(service?.ports).length > 0;
}

function serviceUsesHostNetwork(service) {
  return service?.network_mode === "host" || serviceNetworks(service).includes("host");
}

function volumeText(volume) {
  if (typeof volume === "string") return volume;
  return JSON.stringify(volume ?? {});
}

function serviceMountsDockerSocket(service) {
  return asArray(service?.volumes).some((volume) => volumeText(volume).includes("/var/run/docker.sock"));
}

function serviceMountsUnsafeSourceBind(service) {
  return asArray(service?.volumes).some((volume) => {
    if (typeof volume === "string") {
      const [source] = volume.split(":");
      if (!source || !/[/.\\]/.test(source)) return false;
      const normalized = normalize(source).replaceAll("\\", "/");
      return isAbsolute(source) || normalized.startsWith(".") || normalized.startsWith("..");
    }
    const type = volume?.type;
    const source = volume?.source ?? volume?.src;
    if (type === "bind") return true;
    if (typeof source !== "string") return false;
    const normalized = normalize(source).replaceAll("\\", "/");
    return isAbsolute(source) || normalized.startsWith(".") || normalized.startsWith("..");
  });
}

function serviceUsesDevelopmentCommand(service) {
  const command = JSON.stringify([service?.command, service?.entrypoint]);
  return /next dev|npm run dev|pnpm dev|yarn dev|hot reload/i.test(command);
}

function serviceBuildArgs(service) {
  const args = service?.build?.args;
  if (Array.isArray(args)) return args;
  return Object.entries(asObject(args)).map(([key, value]) => `${key}=${value}`);
}

function hasApplicationSecretBuildArg(service) {
  return serviceBuildArgs(service).some((arg) => /(SECRET|PASSWORD|TOKEN|KEY|CREDENTIAL)/i.test(String(arg)));
}

function assertResourceControls(name, service) {
  assert.ok(asArray(service?.security_opt).includes("no-new-privileges:true"), `${name} missing no-new-privileges`);
  assert.equal(typeof service?.pids_limit, "number", `${name} missing pids_limit`);
  assert.ok(service.pids_limit > 0 && service.pids_limit <= 1024, `${name} pids_limit is unbounded`);
  assert.ok(service?.mem_limit, `${name} missing mem_limit`);
  assert.ok(service?.cpus, `${name} missing cpus limit`);
  assert.ok(service?.healthcheck, `${name} missing healthcheck`);
  assert.ok(service?.logging?.options?.["max-size"], `${name} missing log max-size`);
  assert.ok(service?.logging?.options?.["max-file"], `${name} missing log max-file`);
}

function loadTopology(repoRoot) {
  const composePath = join(repoRoot, "infra", "docker", "docker-compose.production.yml");
  const nginxTemplatePath = join(repoRoot, "infra", "nginx", "templates", "operant.conf.template");
  const deployScriptPath = join(repoRoot, "scripts", "deploy", "operant-production.sh");
  const systemdPath = join(repoRoot, "infra", "systemd", "operant-production.service");
  return {
    compose: yaml.load(readFileSync(composePath, "utf8")),
    nginxTemplate: readFileSync(nginxTemplatePath, "utf8"),
    deployScript: readFileSync(deployScriptPath, "utf8"),
    systemdUnit: readFileSync(systemdPath, "utf8")
  };
}

export function validateP1dProductionTopologyDocument(compose, nginxTemplate, artifacts = {}) {
  const services = asObject(compose?.services);
  const failures = [];

  function check(name, fn) {
    try {
      fn();
    } catch (error) {
      failures.push(`${name}: ${error instanceof Error ? error.message : String(error)}`);
    }
  }

  check("service set", () => {
    assert.deepEqual(Object.keys(services).sort(), requiredServices);
  });

  check("only ingress publishes ports", () => {
    assert.ok(hasHostPorts(services.ingress), "ingress publishes no host ports");
    for (const name of ["core-api", "postgres", "redis", "web-dashboard"]) {
      assert.equal(hasHostPorts(services[name]), false, `${name} publishes host ports`);
    }
  });

  check("network segmentation", () => {
    const networks = asObject(compose?.networks);
    assert.deepEqual(Object.keys(networks).sort(), requiredNetworks);
    assert.equal(networks.application_private.internal, true);
    assert.equal(networks.data_private.internal, true);
    for (const [name, expected] of Object.entries(expectedNetworkMembership)) {
      assert.deepEqual(serviceNetworks(services[name]).sort(), expected.slice().sort(), `${name} has wrong networks`);
    }
    assert.equal(serviceNetworks(services["web-dashboard"]).includes("data_private"), false, "web-dashboard can reach PostgreSQL network");
  });

  check("owned container hardening", () => {
    for (const name of operantOwned) {
      const service = services[name];
      assert.match(String(service?.user), /^[1-9][0-9]*:[1-9][0-9]*$/, `${name} is not explicit non-root uid/gid`);
      assert.equal(service?.read_only, true, `${name} root filesystem is not read-only`);
      assert.deepEqual(service?.cap_drop, ["ALL"], `${name} does not drop all capabilities`);
      assert.ok(asArray(service?.tmpfs).some((entry) => String(entry).startsWith("/tmp:")), `${name} missing /tmp tmpfs`);
      assert.notEqual(service?.privileged, true, `${name} is privileged`);
      assert.equal(serviceUsesHostNetwork(service), false, `${name} uses host networking`);
      assert.equal(service?.pid, undefined, `${name} shares pid namespace`);
      assert.equal(service?.ipc, undefined, `${name} shares ipc namespace`);
      assertResourceControls(name, service);
    }
  });

  check("stateful container controls", () => {
    for (const name of ["postgres", "redis"]) {
      const service = services[name];
      assert.notEqual(service?.privileged, true, `${name} is privileged`);
      assert.equal(serviceUsesHostNetwork(service), false, `${name} uses host networking`);
      assert.equal(serviceMountsDockerSocket(service), false, `${name} mounts Docker socket`);
      assert.equal(serviceMountsUnsafeSourceBind(service), false, `${name} uses unsafe source bind mount`);
      assertResourceControls(name, service);
    }
  });

  check("no unsafe local development defaults", () => {
    const serialized = JSON.stringify(compose);
    assert.doesNotMatch(serialized, /ORDERPILOT_BFF_LOCAL_TEST_BOOTSTRAP|NEXT_PUBLIC_CORE_API_URL/);
    assert.doesNotMatch(serialized, placeholderSecretPattern);
    for (const [name, service] of Object.entries(services)) {
      assert.equal(service?.privileged === true, false, `${name} is privileged`);
      assert.equal(serviceMountsDockerSocket(service), false, `${name} mounts Docker socket`);
      assert.equal(serviceUsesHostNetwork(service), false, `${name} uses host networking`);
      assert.equal(serviceMountsUnsafeSourceBind(service), false, `${name} uses unsafe source bind mount`);
      assert.equal(serviceUsesDevelopmentCommand(service), false, `${name} uses development command`);
      assert.equal(hasApplicationSecretBuildArg(service), false, `${name} supplies application secret as build arg`);
    }
  });

  check("required secrets are fail-closed", () => {
    const serialized = JSON.stringify(compose);
    for (const name of [
      "OPERANT_PUBLIC_HOST",
      "OPERANT_TLS_CERT_PATH",
      "OPERANT_TLS_KEY_PATH",
      "ORDERPILOT_DB_PASSWORD",
      "ORDERPILOT_REDIS_PASSWORD",
      "ORDERPILOT_GATEWAY_SHARED_SECRET",
      "ORDERPILOT_ACTOR_SIGNING_SECRET",
      "ORDERPILOT_OIDC_CLIENT_SECRET",
      "ORDERPILOT_OIDC_IDENTITY_MAPPINGS_JSON"
    ]) {
      assert.match(serialized, new RegExp(`\\$\\{${name}:\\?set ${name}\\}`), `${name} is not required`);
    }
  });

  check("Redis uses structured authenticated configuration", () => {
    const webEnv = asObject(services["web-dashboard"]?.environment);
    assert.equal(webEnv.ORDERPILOT_BFF_REDIS_HOST, "redis");
    assert.equal(webEnv.ORDERPILOT_BFF_REDIS_PORT, 6379);
    assert.match(JSON.stringify(webEnv.ORDERPILOT_BFF_REDIS_PASSWORD), /ORDERPILOT_REDIS_PASSWORD/);
    assert.equal("ORDERPILOT_BFF_REDIS_URL" in webEnv, false, "ORDERPILOT_BFF_REDIS_URL is not allowed");
    assert.equal("REDIS_URL" in webEnv, false, "REDIS_URL is not allowed");
    assert.doesNotMatch(JSON.stringify(compose), credentialRedisUrlPattern);
    assert.match(JSON.stringify(services["core-api"]?.environment?.ORDERPILOT_GATEWAY_HEADER_AUTH_REDIS_PASSWORD), /ORDERPILOT_REDIS_PASSWORD/);
    assert.match(JSON.stringify(services.redis?.command), /--requirepass/);
    assert.match(JSON.stringify(services.redis?.healthcheck), /REDISCLI_AUTH/);
  });

  check("ingress routes only to BFF and strips authority", () => {
    assert.match(nginxTemplate, /proxy_pass http:\/\/web-dashboard:3000;/);
    assert.doesNotMatch(nginxTemplate, /proxy_pass http:\/\/core-api:8080/);
    assert.doesNotMatch(nginxTemplate, /proxy_pass\s+\$|proxy_pass\s+http:\/\/\$|upstream\s+\$|resolver\s+/);
    assert.match(nginxTemplate, /proxy_pass_request_headers off;/);
    for (const header of forbiddenAuthorityHeaders) {
      assert.match(nginxTemplate, new RegExp(`proxy_set_header ${header.replaceAll("-", "\\-")} "";`), `${header} is not stripped`);
    }
    assert.match(nginxTemplate, /location = \/api\/bff\/ready/);
    assert.match(nginxTemplate, /return 404;/);
    assert.doesNotMatch(nginxTemplate, /\$http_upgrade|Upgrade \$http_upgrade/i);
  });

  check("health checks use liveness only", () => {
    for (const name of requiredServices) {
      assert.ok(services[name]?.healthcheck, `${name} missing healthcheck`);
    }
    assert.match(JSON.stringify(services["web-dashboard"]?.healthcheck), /\/api\/bff\/health/);
    assert.doesNotMatch(JSON.stringify(services["web-dashboard"]?.healthcheck), /\/api\/bff\/ready/);
    assert.match(JSON.stringify(services["core-api"]?.healthcheck), /\/api\/v1\/health/);
    assert.match(JSON.stringify(services.ingress?.healthcheck), /\/healthz/);
  });

  check("durable volumes are explicit", () => {
    assert.deepEqual(Object.keys(asObject(compose?.volumes)).sort(), ["operant_postgres_data", "operant_redis_data"]);
    assert.deepEqual(services.postgres?.volumes, ["operant_postgres_data:/var/lib/postgresql/data"]);
    assert.deepEqual(services.redis?.volumes, ["operant_redis_data:/data"]);
  });

  if (artifacts.deployScript) {
    check("deployment lifecycle waits for health", () => {
      assert.match(artifacts.deployScript, /OPERANT_STARTUP_TIMEOUT_SECONDS/);
      assert.match(artifacts.deployScript, /STARTUP_TIMEOUT_MIN=30/);
      assert.match(artifacts.deployScript, /STARTUP_TIMEOUT_MAX=900/);
      assert.match(artifacts.deployScript, /compose up -d --remove-orphans --wait --wait-timeout "\$timeout"/);
      assert.match(artifacts.deployScript, /compose up -d --force-recreate --remove-orphans --wait --wait-timeout "\$timeout"/);
      assert.match(artifacts.deployScript, /docker compose up --help/);
      assert.doesNotMatch(artifacts.deployScript, /compose down/);
    });
  }

  if (artifacts.systemdUnit) {
    check("systemd invokes executable deploy script", () => {
      assert.match(artifacts.systemdUnit, /ExecStartPre=\/opt\/operant\/OrderPilot-Core\/scripts\/deploy\/operant-production\.sh validate/);
      assert.match(artifacts.systemdUnit, /ExecStart=\/opt\/operant\/OrderPilot-Core\/scripts\/deploy\/operant-production\.sh start/);
      assert.match(artifacts.systemdUnit, /ExecStop=\/opt\/operant\/OrderPilot-Core\/scripts\/deploy\/operant-production\.sh stop/);
      assert.match(artifacts.systemdUnit, /TimeoutStartSec=960/);
    });
  }

  return failures;
}

export function validateP1dProductionTopology(repoRoot) {
  const { compose, nginxTemplate, deployScript, systemdUnit } = loadTopology(repoRoot);
  return validateP1dProductionTopologyDocument(compose, nginxTemplate, { deployScript, systemdUnit });
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const repoRoot = resolve(import.meta.dirname, "..", "..", "..");
  const failures = validateP1dProductionTopology(repoRoot);
  if (failures.length > 0) {
    for (const failure of failures) {
      console.error(`P1-D topology validation failed: ${failure}`);
    }
    process.exit(1);
  }
  console.log("P1-D production topology validation passed");
}
