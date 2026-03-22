import { Suspense } from "react";

import { listMyItineraries } from "@/actions/itinerary";
import { ItineraryPlanForm } from "@/components/itinerary/itinerary-plan-form";
import { ItineraryList } from "@/components/itinerary/itinerary-list";
import { ItineraryListSkeleton } from "@/components/itinerary/itinerary-list-skeleton";
import { MapPlaceholder } from "@/components/itinerary/map-placeholder";
import { Sparkles } from "lucide-react";

export default function ItineraryPage() {
  const today = new Date().toISOString().split("T")[0];

  // Kick off fetch early so it runs in parallel with static shell (streaming).
  const itinerariesPromise = listMyItineraries();

  return (
    <div className="flex h-[calc(100vh-4rem)] w-full items-start">
      <aside className="flex w-14/30 min-w-0 flex-col gap-2 px-6">
        <h2 className="text-2xl font-bold text-gray-700 drop-shadow-md pt-4">
          <Sparkles className="inline-block size-7 text-amber-300" />
          AI行程助手
        </h2>

        <ItineraryPlanForm today={today} />

        <section className="flex flex-col gap-3">
          <h2 className="text-xl font-semibold text-gray-700">我的线路</h2>
          <Suspense fallback={<ItineraryListSkeleton />}>
            <ItineraryList dataPromise={itinerariesPromise} />
          </Suspense>
        </section>
      </aside>

      <div className="sticky top-0 h-full w-16/30 min-w-0 overflow-hidden rounded-lg">
        <MapPlaceholder />
      </div>
    </div>
  );
}
