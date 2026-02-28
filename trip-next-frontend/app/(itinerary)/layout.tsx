import { CopilotKit } from "@copilotkit/react-core";
import { SiteHeader } from "@/components/site-header";

export default function ItineraryLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <>
      <SiteHeader />
      <CopilotKit runtimeUrl="/api/v1/copilotkit">
        <main className="mx-auto w-full max-w-screen-2xl px-[10rem] py-6">
          {children}
        </main>
      </CopilotKit>
    </>
  );
}
