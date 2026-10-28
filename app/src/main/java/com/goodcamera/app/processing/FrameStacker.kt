package com.goodcamera.app.processing

import android.graphics.Bitmap

/**
 * 複数フレームの平均化によるノイズ低減
 *
 * N枚のフレームをピクセル単位で平均化する。
 * ランダムノイズが 1/sqrt(N) に低減され、暗所撮影の画質が向上する。
 *
 * メモリ節約のため、フレームは1枚ずつ [Accumulator.add] で足し込み、
 * 足し終わったフレームはすぐ解放できるようにしている。
 * (全フレームを同時にメモリに持つと 12MP×5枚 で数百MBになり OOM の原因になる)
 */
object FrameStacker {

    /** 累積値を ShortArray (最大 65535) で持つため、255 × 257 を超えない枚数に制限する */
    const val MAX_FRAMES = 257

    /**
     * フレームを1枚ずつ足し込んで平均化する。
     * 最初に追加したフレームのサイズが基準になり、サイズが異なるフレーム
     * (撮影中に端末を回して縦横が入れ替わった等) は無視する。
     */
    class Accumulator {
        var width = 0
            private set
        var height = 0
            private set
        var frameCount = 0
            private set

        private var sumR: ShortArray? = null
        private var sumG: ShortArray? = null
        private var sumB: ShortArray? = null
        private var row = IntArray(0)

        /**
         * フレームを追加する。
         * @return 追加できた場合 true。サイズ不一致または上限超過なら false
         */
        fun add(frame: Bitmap): Boolean {
            if (frameCount == 0) {
                width = frame.width
                height = frame.height
                val pixelCount = width * height
                sumR = ShortArray(pixelCount)
                sumG = ShortArray(pixelCount)
                sumB = ShortArray(pixelCount)
                row = IntArray(width)
            } else if (frame.width != width || frame.height != height) {
                return false
            }
            if (frameCount >= MAX_FRAMES) return false

            val r = sumR!!
            val g = sumG!!
            val b = sumB!!
            for (y in 0 until height) {
                frame.getPixels(row, 0, width, 0, y, width, 1)
                val base = y * width
                for (x in 0 until width) {
                    val p = row[x]
                    val i = base + x
                    r[i] = ((r[i].toInt() and 0xFFFF) + ((p shr 16) and 0xFF)).toShort()
                    g[i] = ((g[i].toInt() and 0xFFFF) + ((p shr 8) and 0xFF)).toShort()
                    b[i] = ((b[i].toInt() and 0xFFFF) + (p and 0xFF)).toShort()
                }
            }
            frameCount++
            return true
        }

        /** 平均化した結果を返す。フレームが1枚も無い場合は null */
        fun result(): Bitmap? {
            if (frameCount == 0) return null
            val r = sumR!!
            val g = sumG!!
            val b = sumB!!
            val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            for (y in 0 until height) {
                val base = y * width
                for (x in 0 until width) {
                    val i = base + x
                    val rr = ((r[i].toInt() and 0xFFFF) / frameCount).coerceIn(0, 255)
                    val gg = ((g[i].toInt() and 0xFFFF) / frameCount).coerceIn(0, 255)
                    val bb = ((b[i].toInt() and 0xFFFF) / frameCount).coerceIn(0, 255)
                    row[x] = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
                }
                output.setPixels(row, 0, width, 0, y, width, 1)
            }
            return output
        }
    }

    /**
     * フレームをピクセル単位で平均化してスタック
     */
    fun stack(frames: List<Bitmap>): Bitmap {
        if (frames.isEmpty()) return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        if (frames.size == 1) return frames[0].copy(Bitmap.Config.ARGB_8888, false)

        val accumulator = Accumulator()
        frames.forEach { accumulator.add(it) }
        return accumulator.result()!!
    }
}
