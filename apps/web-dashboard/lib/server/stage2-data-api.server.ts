import "server-only";

import type { Customer, InventorySnapshot, Product } from "../stage2-data-api.ts";
import { tenantServerGetJson } from "./tenant-get-json.server.ts";

export type { Customer, InventorySnapshot, Product } from "../stage2-data-api.ts";

type DataResult<T> = {
  data: T[];
  message?: string;
};

function mapResult<T>(result: { data: T[]; error?: string }): DataResult<T> {
  return {
    data: result.data,
    ...(result.error ? { message: result.error } : {})
  };
}

export async function listProducts() {
  return mapResult(await tenantServerGetJson<Product[]>("/api/v1/products"));
}

export async function listCustomers() {
  return mapResult(await tenantServerGetJson<Customer[]>("/api/v1/customers"));
}

export async function listInventory() {
  return mapResult(await tenantServerGetJson<InventorySnapshot[]>("/api/v1/inventory"));
}
