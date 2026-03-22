/**
 * Format a Date to Chinese locale string like "3月14日(周六)"
 */
export function formatDate(date: Date): string {
  const month = date.getMonth() + 1;
  const day = date.getDate();
  const weekdays = ["周日", "周一", "周二", "周三", "周四", "周五", "周六"];
  const weekday = weekdays[date.getDay()];
  return `${month}月${day}日(${weekday})`;
}

/**
 * Format a "YYYY-MM-DD" string to compact Chinese form like "3月14日".
 * Returns "—" for empty or invalid input.
 */
export function formatDateCompact(dateStr: string): string {
  if (!dateStr?.trim()) return "—";
  const [, m, d] = dateStr.split("-");
  const month = parseInt(m, 10);
  const day = parseInt(d, 10);
  if (Number.isNaN(month) || Number.isNaN(day)) return "—";
  return `${month}月${day}日`;
}

/**
 * Format an ISO datetime string as relative time in Chinese (e.g. "5分钟前").
 * Returns "—" for empty or invalid input.
 */
export function formatRelative(dateStr: string): string {
  if (!dateStr?.trim()) return "—";
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

/**
 * Calculate number of trip days between two "YYYY-MM-DD" strings (inclusive).
 * Returns 0 for invalid input.
 */
export function tripDayCount(startStr: string, endStr: string): number {
  const s = new Date(startStr);
  const e = new Date(endStr);
  if (Number.isNaN(s.getTime()) || Number.isNaN(e.getTime())) return 0;
  return Math.max(0, Math.round((e.getTime() - s.getTime()) / 86_400_000) + 1);
}

/**
 * Convert a Money object (currency + units + nanos) to a numeric amount
 */
export function formatMoney(
  money: { currency: string; units: number; nanos: number } | undefined,
): number {
  if (!money) return 0;
  return money.units + money.nanos / 1_000_000_000;
}

/**
 * Convert a DateMessage { year, month, day } to "YYYY-MM-DD" display string
 */
export function formatDateMessage(
  date: { year: number; month: number; day: number } | undefined,
): string {
  if (!date) return "";
  const y = String(date.year);
  const m = String(date.month).padStart(2, "0");
  const d = String(date.day).padStart(2, "0");
  return `${y}-${m}-${d}`;
}

const ORDER_STATUS_LABELS: Record<number, string> = {
  0: "全部",
  1: "待支付",
  2: "已付款",
  3: "已完成",
  4: "已取消",
};

export function formatOrderStatus(status: number): string {
  return ORDER_STATUS_LABELS[status] ?? "未知";
}

const ORDER_TYPE_LABELS: Record<number, string> = {
  0: "全部类型",
  1: "景点门票",
  2: "酒店住宿",
  3: "机票",
  4: "火车票",
};

export function formatOrderType(type: number): string {
  return ORDER_TYPE_LABELS[type] ?? "未知";
}
