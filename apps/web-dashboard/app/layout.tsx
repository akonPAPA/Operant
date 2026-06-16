import type { Metadata } from "next";
import { productBrand } from "@/lib/brand";
import "./globals.css";

export const metadata: Metadata = {
  title: productBrand.name,
  description: productBrand.description
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
