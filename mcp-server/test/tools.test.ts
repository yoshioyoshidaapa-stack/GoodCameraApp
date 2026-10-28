import { test } from "node:test";
import assert from "node:assert/strict";
import { formatJapanDateTime, japanNoon } from "../src/japan.ts";
import { getSunTimes } from "../src/sun.ts";
import { formatJapanTime } from "../src/japan.ts";
import { photoLightTimesText } from "../src/tools.ts";

test("UTCの日付が変わる前でも日本時間の日付・曜日になる", () => {
  // UTC 2026-10-27 20:05 = 日本時間 2026-10-28 5:05（水）
  assert.equal(formatJapanDateTime(new Date(Date.UTC(2026, 9, 27, 20, 5))), "2026年10月28日（水）5時05分");
});

test("日付の読み取り", () => {
  assert.equal(japanNoon("2026-10-28")?.toISOString(), "2026-10-28T03:00:00.000Z");
  assert.equal(japanNoon("2026-02-30"), null);
  assert.equal(japanNoon("あした"), null);
});

test("東京 2026-10-28 の日の出・日の入りが別ライブラリ(astral)の値と2分以内", () => {
  const t = getSunTimes(new Date(Date.UTC(2026, 9, 28, 3)), 35.6812, 139.7671);
  const minutes = (d: Date | null) => {
    const [h, m] = formatJapanTime(d).split(":").map(Number);
    return h * 60 + m;
  };
  assert.ok(Math.abs(minutes(t.sunrise) - (5 * 60 + 58)) <= 2, formatJapanTime(t.sunrise));
  assert.ok(Math.abs(minutes(t.sunset) - (16 * 60 + 50)) <= 2, formatJapanTime(t.sunset));
});

test("場所を省略すると東京、緯度経度を指定するとそちらを使う", () => {
  assert.match(photoLightTimesText("2026-10-28", undefined, undefined, undefined), /東京/);
  assert.match(photoLightTimesText("2026-10-28", "さいたま", undefined, undefined), /さいたま/);
  assert.match(photoLightTimesText("2026-10-28", undefined, 43, 141), /緯度43・経度141/);
  assert.match(photoLightTimesText("2026/10/28", undefined, undefined, undefined), /読み取れませんでした/);
});
