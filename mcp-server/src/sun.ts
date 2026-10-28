/**
 * 太陽の高さから、日の出・日の入り・ゴールデンアワーなどの時刻を計算する
 *
 * 計算方法は SunCalc (https://github.com/mourner/suncalc, BSD-2-Clause) と同じ天文計算式。
 * 誤差は数分程度（山や建物の影、気象条件は考慮しない）。
 */

const PI = Math.PI;
const rad = PI / 180;
const dayMs = 1000 * 60 * 60 * 24;
const J1970 = 2440588;
const J2000 = 2451545;
const J0 = 0.0009;
/** 地球の自転軸の傾き */
const obliquity = rad * 23.4397;

const toJulian = (date: Date) => date.valueOf() / dayMs - 0.5 + J1970;
const fromJulian = (j: number) => new Date((j + 0.5 - J1970) * dayMs);
const toDays = (date: Date) => toJulian(date) - J2000;

const declination = (l: number) => Math.asin(Math.sin(obliquity) * Math.sin(l));
const solarMeanAnomaly = (d: number) => rad * (357.5291 + 0.98560028 * d);

function eclipticLongitude(M: number): number {
  const C = rad * (1.9148 * Math.sin(M) + 0.02 * Math.sin(2 * M) + 0.0003 * Math.sin(3 * M));
  const P = rad * 102.9372; // 近日点
  return M + C + P + PI;
}

const julianCycle = (d: number, lw: number) => Math.round(d - J0 - lw / (2 * PI));
const approxTransit = (Ht: number, lw: number, n: number) => J0 + (Ht + lw) / (2 * PI) + n;
const solarTransitJ = (ds: number, M: number, L: number) =>
  J2000 + ds + 0.0053 * Math.sin(M) - 0.0069 * Math.sin(2 * L);

/** 太陽がその高さに届かない日（白夜・極夜）は null */
function hourAngle(h: number, phi: number, dec: number): number | null {
  const x = (Math.sin(h) - Math.sin(phi) * Math.sin(dec)) / (Math.cos(phi) * Math.cos(dec));
  return x < -1 || x > 1 ? null : Math.acos(x);
}

export interface SunTimes {
  /** ブルーアワー開始（朝、太陽高度 -6°） */
  morningBlueStart: Date | null;
  /** ブルーアワー終了＝ゴールデンアワー寄りの明るさに変わる（朝、-4°） */
  morningBlueEnd: Date | null;
  /** 日の出（太陽の上端が地平線に出る、-0.833°） */
  sunrise: Date | null;
  /** 朝のゴールデンアワー終了（+6°） */
  morningGoldenEnd: Date | null;
  /** 南中（太陽が一番高い） */
  solarNoon: Date;
  /** 夕方のゴールデンアワー開始（+6°） */
  eveningGoldenStart: Date | null;
  /** 日の入り（-0.833°） */
  sunset: Date | null;
  /** 夕方のブルーアワー開始（-4°） */
  eveningBlueStart: Date | null;
  /** 夕方のブルーアワー終了（-6°） */
  eveningBlueEnd: Date | null;
}

/**
 * @param date 計算したい日（その日の正午ごろの時刻を渡す）
 * @param lat 緯度（北が＋）
 * @param lng 経度（東が＋）
 */
export function getSunTimes(date: Date, lat: number, lng: number): SunTimes {
  const lw = rad * -lng;
  const phi = rad * lat;
  const d = toDays(date);
  const n = julianCycle(d, lw);
  const ds = approxTransit(0, lw, n);
  const M = solarMeanAnomaly(ds);
  const L = eclipticLongitude(M);
  const dec = declination(L);
  const Jnoon = solarTransitJ(ds, M, L);

  // 太陽が高度 angle 度になる朝・夕の時刻
  const at = (angle: number): [Date | null, Date | null] => {
    const w = hourAngle(angle * rad, phi, dec);
    if (w === null) return [null, null];
    const Jset = solarTransitJ(approxTransit(w, lw, n), M, L);
    const Jrise = Jnoon - (Jset - Jnoon);
    return [fromJulian(Jrise), fromJulian(Jset)];
  };

  const [sunrise, sunset] = at(-0.833);
  const [goldenEnd, goldenStart] = at(6);
  const [blueStart, blueEndEvening] = at(-6);
  const [blueEnd, blueStartEvening] = at(-4);

  return {
    morningBlueStart: blueStart,
    morningBlueEnd: blueEnd,
    sunrise,
    morningGoldenEnd: goldenEnd,
    solarNoon: fromJulian(Jnoon),
    eveningGoldenStart: goldenStart,
    sunset,
    eveningBlueStart: blueStartEvening,
    eveningBlueEnd: blueEndEvening,
  };
}
