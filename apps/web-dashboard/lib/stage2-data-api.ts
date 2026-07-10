import { dashboardCoreApiBaseUrl } from "./api-transport";
import { demoTenantId } from "./frontend-authority.mjs";

const DEFAULT_BASE_URL = "http://localhost:8080";

export type Product = {
  id: string;
  sku: string;
  name: string;
  category?: string;
  status: string;
  baseUom: string;
};

export type Customer = {
  id: string;
  accountCode: string;
  legalName: string;
  displayName: string;
  status: string;
  defaultCurrency: string;
};

export type InventorySnapshot = {
  id: string;
  productId: string;
  locationId: string;
  quantityOnHand: number | string;
  quantityAvailable: number | string;
  capturedAt: string;
};

type DataResult<T> = {
  data: T[];
  message?: string;
};

const baseUrl = dashboardCoreApiBaseUrl();
const tenantId = demoTenantId();

async function fetchTenantData<T>(path: string): Promise<DataResult<T>> {
  if (!tenantId) {
    return { data: [], message: "Authenticated dashboard access is unavailable." };
  }

  try {
    const response = await fetch(`${baseUrl}${path}`, {
      cache: "no-store",
      headers: { "X-Tenant-Id": tenantId }
    });
    if (!response.ok) {
      return { data: [], message: `Core API returned ${response.status}. Demo data may not be seeded yet.` };
    }
    return { data: (await response.json()) as T[] };
  } catch {
    return { data: [], message: "Core API is not reachable from the dashboard." };
  }
}

export function listProducts() {
  return fetchTenantData<Product>("/api/v1/products");
}

export function listCustomers() {
  return fetchTenantData<Customer>("/api/v1/customers");
}

export function listInventory() {
  return fetchTenantData<InventorySnapshot>("/api/v1/inventory");
}
