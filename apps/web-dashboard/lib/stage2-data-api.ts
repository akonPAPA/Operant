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

const baseUrl = process.env.CORE_API_BASE_URL ?? process.env.NEXT_PUBLIC_CORE_API_URL ?? DEFAULT_BASE_URL;
const tenantId = process.env.NEXT_PUBLIC_DEMO_TENANT_ID ?? "";

async function fetchTenantData<T>(path: string): Promise<DataResult<T>> {
  if (!tenantId) {
    return { data: [], message: "Demo tenant is not configured. Run the Stage 2 seed script and set NEXT_PUBLIC_DEMO_TENANT_ID." };
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
