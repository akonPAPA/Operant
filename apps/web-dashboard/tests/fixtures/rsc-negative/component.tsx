// Server component (NOT "use client") — the F14 guard must traverse INTO it.
import { loadViaHelper } from "./helper.ts";
export function NegativeComponent() {
  loadViaHelper();
  return null;
}
