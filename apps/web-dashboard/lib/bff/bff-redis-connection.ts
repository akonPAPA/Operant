export type MinimalBffRedisClient = {
  isOpen: boolean;
  connect(): Promise<unknown>;
  on?(event: string, listener: (...args: unknown[]) => void): unknown;
};

export type BffRedisConnectionOptions = {
  socket: {
    host: string;
    port: number;
    reconnectStrategy: false;
    connectTimeout: 5000;
  };
  password: string;
};

const HOST_VALUE = /^[A-Za-z0-9._-]{1,253}$/;
const PORT_VALUE = /^(?:[1-9][0-9]{0,4})$/;
const MAX_PASSWORD_LENGTH = 1024;

export class BffRedisConfigurationError extends Error {
  constructor(message = "BFF Redis host, port, and password are required.") {
    super(message);
    this.name = "BffRedisConfigurationError";
  }
}

function rejectCredentialBearingUrl(): void {
  if (process.env.ORDERPILOT_BFF_REDIS_URL || process.env.REDIS_URL) {
    throw new BffRedisConfigurationError("BFF Redis URL configuration is not supported.");
  }
}

function readHost(): string {
  const host = process.env.ORDERPILOT_BFF_REDIS_HOST ?? "";
  if (!HOST_VALUE.test(host) || host.includes("..")) {
    throw new BffRedisConfigurationError();
  }
  return host;
}

function readPort(): number {
  const raw = process.env.ORDERPILOT_BFF_REDIS_PORT ?? "";
  if (!PORT_VALUE.test(raw)) {
    throw new BffRedisConfigurationError();
  }
  const port = Number(raw);
  if (!Number.isSafeInteger(port) || port < 1 || port > 65535) {
    throw new BffRedisConfigurationError();
  }
  return port;
}

function readPassword(): string {
  const password = process.env.ORDERPILOT_BFF_REDIS_PASSWORD ?? "";
  if (
    password.length === 0 ||
    password.length > MAX_PASSWORD_LENGTH ||
    /[\x00-\x1f\x7f]/.test(password)
  ) {
    throw new BffRedisConfigurationError();
  }
  return password;
}

export function bffRedisConnectionOptions(): BffRedisConnectionOptions {
  rejectCredentialBearingUrl();
  return {
    socket: {
      host: readHost(),
      port: readPort(),
      reconnectStrategy: false,
      connectTimeout: 5000
    },
    password: readPassword()
  };
}