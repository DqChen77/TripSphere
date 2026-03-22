import { MapPin } from "lucide-react";

export function MapPlaceholder() {
  return (
    <div className="relative flex h-full items-center justify-center overflow-hidden bg-gradient-to-br from-sky-50 via-blue-50 to-indigo-100">
      <div
        aria-hidden
        className="pointer-events-none absolute inset-0"
        style={{
          backgroundImage:
            "linear-gradient(#bfdbfe 1px, transparent 1px), linear-gradient(to right, #bfdbfe 1px, transparent 1px)",
          backgroundSize: "48px 48px",
        }}
      />
      <div
        aria-hidden
        className="absolute -top-20 -left-20 size-72 rounded-full bg-blue-100/60 blur-3xl"
      />
      <div
        aria-hidden
        className="absolute -right-16 -bottom-16 size-64 rounded-full bg-indigo-100/50 blur-3xl"
      />
      <div className="relative z-10 flex flex-col items-center gap-3 text-center">
        <div className="rounded-2xl bg-white/70 p-5 shadow-sm backdrop-blur-sm">
          <MapPin className="mx-auto size-10 text-blue-400" strokeWidth={1.5} />
        </div>
        <p className="text-sm font-semibold text-blue-700">地图视图</p>
        <p className="max-w-[180px] text-xs leading-relaxed text-blue-400">
          高德地图 SDK 接入后将在此展示行程路线与景点
        </p>
      </div>
    </div>
  );
}
