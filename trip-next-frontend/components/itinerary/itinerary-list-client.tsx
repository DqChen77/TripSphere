"use client";

import { useState, useRef, useEffect } from "react";
import Link from "next/link";
import { Loader2 } from "lucide-react";

import type { SavedItinerarySummary } from "@/actions/itinerary";

const BATCH_SIZE = 5;

function formatDate(dateStr: string): string {
  if (!dateStr.trim()) return "—";
  const [, m, d] = dateStr.split("-");
  const month = parseInt(m, 10);
  const day = parseInt(d, 10);
  if (Number.isNaN(month) || Number.isNaN(day)) return "—";
  return `${month}月${day}日`;
}

function formatRelative(dateStr: string): string {
  if (!dateStr.trim()) return "—";
  const date = new Date(dateStr);
  if (Number.isNaN(date.getTime())) return "—";
  const diffMin = Math.floor((Date.now() - date.getTime()) / 60_000);
  if (diffMin < 1) return "刚刚";
  if (diffMin < 60) return `${diffMin}分钟前`;
  const diffH = Math.floor(diffMin / 60);
  if (diffH < 24) return `${diffH}小时前`;
  const diffD = Math.floor(diffH / 24);
  if (diffD < 30) return `${diffD}天前`;
  return date.toLocaleDateString("zh-CN");
}

function tripDays(start: string, end: string): number {
  const s = new Date(start);
  const e = new Date(end);
  if (Number.isNaN(s.getTime()) || Number.isNaN(e.getTime())) return 0;
  return Math.max(0, Math.round((e.getTime() - s.getTime()) / 86_400_000) + 1);
}

interface CardProps {
  item: SavedItinerarySummary;
  onDelete: (formData: FormData) => Promise<void>;
}

function ItineraryCard({ item, onDelete }: CardProps) {
  const days = tripDays(item.start_date, item.end_date);

  return (
    <div className="flex flex-col overflow-hidden rounded-xl border border-gray-100 bg-white shadow-sm transition-all hover:border-blue-200 hover:shadow-md">
      <div className="flex items-center justify-between bg-gradient-to-r from-blue-600 to-blue-500 px-4 py-3 text-white">
        <div className="min-w-0">
          <h3 className="truncate text-sm leading-snug font-bold">
            {item.destination}
          </h3>
          <p className="mt-0.5 text-xs text-blue-100">
            {formatDate(item.start_date)} — {formatDate(item.end_date)}
          </p>
        </div>
        <div className="ml-3 flex shrink-0 flex-col items-center rounded-lg bg-white/20 px-2.5 py-1.5 text-center">
          <span className="text-lg leading-none font-bold">{days}</span>
          <span className="text-[10px] text-blue-100">天</span>
        </div>
      </div>

      <div className="flex items-center gap-2 px-4 py-2.5">
        <span className="flex-1 text-xs text-gray-400">
          🎯 {item.day_count} 个活动 · 🕐 {formatRelative(item.updated_at)}
        </span>
        <Link
          href={`/itinerary/planner?id=${item.id}`}
          className="shrink-0 rounded-lg bg-blue-600 px-3 py-1.5 text-xs font-medium text-white transition-colors hover:bg-blue-700"
        >
          查看 / 编辑
        </Link>
        <form action={onDelete}>
          <input type="hidden" name="id" value={item.id} />
          <button
            type="submit"
            className="rounded-lg border border-gray-200 px-3 py-1.5 text-xs text-gray-400 transition-colors hover:border-red-200 hover:bg-red-50 hover:text-red-500"
          >
            删除
          </button>
        </form>
      </div>
    </div>
  );
}

interface Props {
  items: SavedItinerarySummary[];
  onDelete: (formData: FormData) => Promise<void>;
}

export function ItineraryListClient({ items, onDelete }: Props) {
  const [visibleCount, setVisibleCount] = useState(BATCH_SIZE);
  const sentinelRef = useRef<HTMLDivElement>(null);
  const hasMore = visibleCount < items.length;

  useEffect(() => {
    if (!hasMore) return;
    const observer = new IntersectionObserver(
      ([entry]) => {
        if (entry.isIntersecting) {
          setVisibleCount((prev) => Math.min(prev + BATCH_SIZE, items.length));
        }
      },
      { threshold: 0.1 },
    );
    const sentinel = sentinelRef.current;
    if (sentinel) observer.observe(sentinel);
    return () => observer.disconnect();
  }, [hasMore, items.length]);

  return (
    <div className="flex flex-col gap-2.5">
      {items.slice(0, visibleCount).map((item) => (
        <ItineraryCard key={item.id} item={item} onDelete={onDelete} />
      ))}
      {hasMore && (
        <div
          ref={sentinelRef}
          aria-hidden
          className="flex items-center justify-center py-3 text-gray-300"
        >
          <Loader2 className="size-4 animate-spin" />
        </div>
      )}
    </div>
  );
}
