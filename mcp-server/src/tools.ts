import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { z } from "zod";
import { formatJapanDateTime, formatJapanTime, japanNoon } from "./japan.ts";
import { getSunTimes } from "./sun.ts";

/** よく使う場所（緯度・経度） */
export const PLACES = {
  東京: { lat: 35.6812, lng: 139.7671 },
  さいたま: { lat: 35.8617, lng: 139.6455 },
  横浜: { lat: 35.4437, lng: 139.638 },
  大阪: { lat: 34.6937, lng: 135.5023 },
  名古屋: { lat: 35.1815, lng: 136.9066 },
  札幌: { lat: 43.0618, lng: 141.3545 },
  福岡: { lat: 33.5904, lng: 130.4017 },
  那覇: { lat: 26.2124, lng: 127.6809 },
} as const;

type PlaceName = keyof typeof PLACES;
const placeNames = Object.keys(PLACES) as [PlaceName, ...PlaceName[]];

const text = (t: string) => ({ content: [{ type: "text" as const, text: t }] });

export function photoLightTimesText(
  dateText: string | undefined,
  place: PlaceName | undefined,
  latitude: number | undefined,
  longitude: number | undefined,
  now: Date = new Date(),
): string {
  const noon = japanNoon(dateText, now);
  if (noon === null) return `日付「${dateText}」が読み取れませんでした。2026-10-28 のように書いてください。`;

  let label: string;
  let lat: number;
  let lng: number;
  if (latitude !== undefined && longitude !== undefined) {
    label = `緯度${latitude}・経度${longitude}`;
    lat = latitude;
    lng = longitude;
  } else {
    const name = place ?? "東京";
    label = name;
    ({ lat, lng } = PLACES[name]);
  }

  const t = getSunTimes(noon, lat, lng);
  const f = formatJapanTime;
  const day = formatJapanDateTime(noon).replace(/\d+時\d+分$/, "");
  return [
    `${day}・${label}の光の時間帯（日本時間）`,
    "",
    `朝のブルーアワー: ${f(t.morningBlueStart)}〜${f(t.morningBlueEnd)}`,
    `日の出: ${f(t.sunrise)}`,
    `朝のゴールデンアワー: ${f(t.sunrise)}〜${f(t.morningGoldenEnd)}`,
    `南中（太陽が一番高い）: ${f(t.solarNoon)}`,
    `夕方のゴールデンアワー: ${f(t.eveningGoldenStart)}〜${f(t.sunset)}`,
    `日の入り: ${f(t.sunset)}`,
    `夕方のブルーアワー: ${f(t.eveningBlueStart)}〜${f(t.eveningBlueEnd)}`,
    "",
    "※ 天文計算による目安です（誤差は数分程度。山や建物、天気は考慮していません）。",
  ].join("\n");
}

/** MCPサーバーを作り、道具（ツール）を登録する */
export function createServer(): McpServer {
  const server = new McpServer({ name: "goodcamera-mcp", version: "1.0.0" });

  server.registerTool(
    "get_japan_time",
    {
      title: "日本の現在時刻",
      description: "日本時間（JST）の現在の日付・曜日・時刻を返します。",
      inputSchema: {},
      annotations: { readOnlyHint: true, openWorldHint: false },
    },
    async () => text(`現在の日本時間: ${formatJapanDateTime(new Date())}`),
  );

  server.registerTool(
    "get_photo_light_times",
    {
      title: "写真向けの光の時間帯",
      description:
        "指定した日・場所の日の出、日の入り、ゴールデンアワー（柔らかい金色の光）、" +
        "ブルーアワー（日の出前・日没後の青い光）の時刻を日本時間で返します。" +
        "写真撮影の計画に使います。",
      inputSchema: {
        date: z
          .string()
          .optional()
          .describe("日付（例: 2026-10-28）。省略すると今日（日本時間）"),
        place: z
          .enum(placeNames)
          .optional()
          .describe("場所の名前。省略すると東京。緯度・経度を指定した場合はそちらを優先"),
        latitude: z.number().min(-90).max(90).optional().describe("緯度（北緯が＋）"),
        longitude: z.number().min(-180).max(180).optional().describe("経度（東経が＋）"),
      },
      annotations: { readOnlyHint: true, openWorldHint: false },
    },
    async ({ date, place, latitude, longitude }) =>
      text(photoLightTimesText(date, place, latitude, longitude)),
  );

  return server;
}
