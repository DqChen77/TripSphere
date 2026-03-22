import { revalidatePath } from "next/cache";

import {
  deleteItinerary,
  type SavedItinerarySummary,
} from "@/actions/itinerary";
import { ItineraryListClient } from "@/components/itinerary/itinerary-list-client";

// ── Server Action ──────────────────────────────────────────────────────────

async function deleteItineraryAction(formData: FormData) {
  "use server";
  const id = formData.get("id") as string;
  await deleteItinerary(id);
  revalidatePath("/itinerary");
}

// ── Component ──────────────────────────────────────────────────────────────

interface Props {
  dataPromise: Promise<SavedItinerarySummary[]>;
}

export async function ItineraryList({ dataPromise }: Props) {
  let items: SavedItinerarySummary[] = [];
  let error: string | null = null;

  try {
    items = await dataPromise;
  } catch (err) {
    error = err instanceof Error ? err.message : "加载失败";
  }

  if (error) {
    return (
      <div className="rounded-xl border border-red-100 bg-red-50 p-4 text-sm text-red-500">
        {error}
      </div>
    );
  }

  if (items.length === 0) {
    return (
      <div className="flex flex-col items-center rounded-xl border border-dashed border-gray-200 py-10 text-center">
        <span className="text-4xl">🗺️</span>
        <p className="mt-3 text-sm font-medium text-gray-500">
          还没有保存的行程
        </p>
        <p className="mt-1 text-xs text-gray-400">
          用 AI 规划你的第一次旅行吧！
        </p>
      </div>
    );
  }

  return <ItineraryListClient items={items} onDelete={deleteItineraryAction} />;
}
