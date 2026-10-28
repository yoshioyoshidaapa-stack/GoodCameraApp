/** 日本時間（JST, UTC+9）の扱い */

const JST_OFFSET_MS = 9 * 60 * 60 * 1000;
const WEEKDAYS = ["日", "月", "火", "水", "木", "金", "土"];

/** Date を日本時間の「年・月・日・時・分・曜日」に分ける */
export function toJapanParts(date: Date) {
  const jst = new Date(date.getTime() + JST_OFFSET_MS);
  return {
    year: jst.getUTCFullYear(),
    month: jst.getUTCMonth() + 1,
    day: jst.getUTCDate(),
    hour: jst.getUTCHours(),
    minute: jst.getUTCMinutes(),
    weekday: WEEKDAYS[jst.getUTCDay()],
  };
}

/** 例: 2026年10月28日（水）9時05分 */
export function formatJapanDateTime(date: Date): string {
  const p = toJapanParts(date);
  return `${p.year}年${p.month}月${p.day}日（${p.weekday}）${p.hour}時${String(p.minute).padStart(2, "0")}分`;
}

/** 例: 6:03 */
export function formatJapanTime(date: Date | null): string {
  if (date === null) return "なし";
  // 秒は四捨五入して分にする
  const p = toJapanParts(new Date(date.getTime() + 30 * 1000));
  return `${p.hour}:${String(p.minute).padStart(2, "0")}`;
}

/**
 * "2026-10-28" のような日付を、その日の日本時間の正午として返す
 * 省略時は今日（日本時間）
 */
export function japanNoon(dateText: string | undefined, now: Date = new Date()): Date | null {
  let year: number, month: number, day: number;
  if (dateText === undefined) {
    ({ year, month, day } = toJapanParts(now));
  } else {
    const m = /^(\d{4})-(\d{1,2})-(\d{1,2})$/.exec(dateText.trim());
    if (!m) return null;
    [year, month, day] = [Number(m[1]), Number(m[2]), Number(m[3])];
    const check = new Date(Date.UTC(year, month - 1, day));
    if (check.getUTCMonth() !== month - 1 || check.getUTCDate() !== day) return null;
  }
  // 日本時間の12:00 = UTCの3:00
  return new Date(Date.UTC(year, month - 1, day, 3, 0, 0));
}
