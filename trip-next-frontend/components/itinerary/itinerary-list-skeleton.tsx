function SkeletonCard() {
  return (
    <div className="animate-pulse overflow-hidden rounded-xl border border-gray-100">
      <div className="to-gray-150 h-[58px] bg-gradient-to-r from-gray-200" />
      <div className="flex items-center gap-2 px-4 py-2.5">
        <div className="h-3 flex-1 rounded-full bg-gray-100" />
        <div className="h-6 w-16 rounded-lg bg-gray-100" />
        <div className="h-6 w-10 rounded-lg bg-gray-100" />
      </div>
    </div>
  );
}

export function ItineraryListSkeleton() {
  return (
    <div className="flex flex-col gap-2.5">
      {Array.from({ length: 3 }, (_, i) => (
        <SkeletonCard key={i} />
      ))}
    </div>
  );
}
