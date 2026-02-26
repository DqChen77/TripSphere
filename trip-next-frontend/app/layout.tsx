import type { Metadata } from "next";
import { CopilotKit } from "@copilotkit/react-core";
import { SidebarInset, SidebarProvider } from "@/components/ui/sidebar";
import { AppSidebar } from "@/components/app-sidebar";
import { SiteHeader } from "@/components/site-header";
import "@copilotkit/react-core/v2/styles.css";
import "./globals.css";

export const metadata: Metadata = {
  title: "TripSphere",
  description: "AI-Native Travel Platform",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="zh-CN" data-scroll-behavior="smooth">
      <body className="antialiased">
        <SidebarProvider
          style={
            {
              "--sidebar-width": "10rem",
              "--sidebar-width-mobile": "10rem",
            } as React.CSSProperties
          }
        >
          <AppSidebar />
          <SidebarInset>
            <SiteHeader />
            <CopilotKit runtimeUrl="/api/v1/copilotkit">
              <main className="mx-auto w-full max-w-screen-2xl px-[10rem] py-6">
                {children}
              </main>
            </CopilotKit>
          </SidebarInset>
        </SidebarProvider>
      </body>
    </html>
  );
}
