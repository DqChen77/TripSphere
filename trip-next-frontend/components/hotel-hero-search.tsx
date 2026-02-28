"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import {
  MapPin,
  CalendarDays,
  Users,
  Search,
  X,
  ChevronDown,
} from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Separator } from "@/components/ui/separator";
import { Badge } from "@/components/ui/badge";
import { Calendar } from "@/components/ui/calendar";
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from "@/components/ui/popover";
import { type DateRange } from "react-day-picker";
import { zhCN } from "date-fns/locale";

function formatDate(date: Date): string {
  const month = date.getMonth() + 1;
  const day = date.getDate();
  const weekdays = ["周日", "周一", "周二", "周三", "周四", "周五", "周六"];
  const weekday = weekdays[date.getDay()];
  return `${month}月${day}日(${weekday})`;
}

function calcNights(checkIn: Date, checkOut: Date): number {
  const diff = checkOut.getTime() - checkIn.getTime();
  return Math.max(1, Math.round(diff / (1000 * 60 * 60 * 24)));
}

export function HotelHeroSearch() {
  const router = useRouter();

  const today = new Date();
  const tomorrow = new Date(today);
  tomorrow.setDate(tomorrow.getDate() + 1);

  const [location, setLocation] = useState("上海");
  const [dateRange, setDateRange] = useState<DateRange | undefined>({
    from: today,
    to: tomorrow,
  });
  const [rooms] = useState(1);
  const [adults] = useState(1);
  const [children] = useState(0);
  const [keyword, setKeyword] = useState("");

  const checkIn = dateRange?.from ?? today;
  const checkOut = dateRange?.to ?? tomorrow;
  const nights = calcNights(checkIn, checkOut);

  const handleSearch = () => {
    const params = new URLSearchParams();
    if (location) params.set("city", location);
    if (keyword) params.set("keyword", keyword);
    params.set("checkIn", checkIn.toISOString().split("T")[0]);
    params.set("checkOut", checkOut.toISOString().split("T")[0]);
    params.set("rooms", String(rooms));
    params.set("adults", String(adults));
    params.set("children", String(children));
    router.push(`/hotel?${params.toString()}`);
  };

  return (
    <div className="relative overflow-hidden rounded-2xl">
      {/* Background Image */}
      <div
        className="absolute inset-0 bg-cover bg-center"
        style={{
          backgroundImage:
            "url('https://images.unsplash.com/photo-1564501049412-61c2a3083791?w=1920&q=80')",
        }}
      />
      {/* Overlay gradient for better text readability */}
      <div className="absolute inset-0 bg-gradient-to-b from-blue-600/40 via-blue-500/20 to-blue-400/30" />

      {/* Content */}
      <div className="relative z-10 flex flex-col gap-8 px-10 pt-10 pb-14">
        {/* Title */}
        <h1 className="text-4xl font-bold text-white drop-shadow-md">
          酒店
          <span className="ml-1 inline-block size-2.5 rounded-full bg-amber-400" />
        </h1>

        {/* Search Card */}
        <div className="flex items-center gap-0 rounded-xl bg-white p-2 shadow-lg">
          {/* Location */}
          <SearchField>
            <MapPin className="size-4 shrink-0 text-blue-500" />
            <span className="text-sm font-medium text-gray-800">
              {location || "目的地"}
            </span>
            {location && (
              <button
                onClick={() => setLocation("")}
                className="ml-auto rounded-full p-0.5 text-gray-400 transition-colors hover:bg-gray-100 hover:text-gray-600"
              >
                <X className="size-3.5" />
              </button>
            )}
          </SearchField>

          <Separator
            orientation="vertical"
            className="mx-1 data-[orientation=vertical]:h-8"
          />

          {/* Date Range */}
          <Popover>
            <PopoverTrigger asChild>
              <button className="flex min-w-[220px] cursor-pointer items-center gap-2 rounded-lg px-3 py-2 transition-colors hover:bg-gray-50">
                <CalendarDays className="size-4 shrink-0 text-blue-500" />
                <span className="text-sm font-medium text-gray-800">
                  {formatDate(checkIn)}
                </span>
                <span className="mx-3 text-xs text-gray-400">-</span>
                <span className="text-sm font-medium text-gray-800">
                  {formatDate(checkOut)}
                </span>
                <Badge
                  variant="secondary"
                  className="ml-1 rounded-md bg-blue-50 px-1.5 py-0.5 text-xs font-medium text-blue-600"
                >
                  {nights}晚
                </Badge>
              </button>
            </PopoverTrigger>
            <PopoverContent className="w-auto p-0" align="start">
              <Calendar
                mode="range"
                defaultMonth={checkIn}
                selected={dateRange}
                onSelect={setDateRange}
                numberOfMonths={2}
                disabled={{ before: today }}
                locale={zhCN}
              />
              <p className="text-muted-foreground px-3 pb-3 text-xs">
                所有日期均为当地时间
              </p>
            </PopoverContent>
          </Popover>

          <Separator
            orientation="vertical"
            className="mx-1 data-[orientation=vertical]:h-8"
          />

          {/* Rooms & Guests */}
          <SearchField>
            <Users className="size-4 shrink-0 text-blue-500" />
            <span className="text-sm font-medium text-gray-800">
              {rooms}间, {adults}成人, {children}儿童
            </span>
            <ChevronDown className="ml-auto size-3.5 shrink-0 text-gray-400" />
          </SearchField>

          <Separator
            orientation="vertical"
            className="mx-1 data-[orientation=vertical]:h-8"
          />

          {/* Keyword */}
          <div className="flex flex-1 items-center px-3">
            <Input
              value={keyword}
              onChange={(e) => setKeyword(e.target.value)}
              placeholder="位置/品牌/酒店（选填）"
              className="h-8 border-none bg-transparent text-sm shadow-none placeholder:text-gray-400 focus-visible:ring-0"
              onKeyDown={(e) => {
                if (e.key === "Enter") handleSearch();
              }}
            />
          </div>

          {/* Search Button */}
          <Button
            onClick={handleSearch}
            className="ml-2 h-10 gap-1.5 rounded-lg bg-blue-600 px-6 text-white hover:bg-blue-700"
          >
            <Search className="size-4" />
            搜索
          </Button>
        </div>
      </div>
    </div>
  );
}

function SearchField({
  children,
  className,
}: {
  children: React.ReactNode;
  className?: string;
}) {
  return (
    <div
      className={`flex cursor-pointer items-center gap-2 rounded-lg px-3 py-2 transition-colors hover:bg-gray-50 ${className ?? ""}`}
    >
      {children}
    </div>
  );
}
