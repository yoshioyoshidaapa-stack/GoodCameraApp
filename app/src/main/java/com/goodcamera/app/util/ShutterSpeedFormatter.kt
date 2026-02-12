package com.goodcamera.app.util

/**
 * シャッタースピード(ナノ秒)を人間が読みやすい形式に変換するユーティリティ
 */
object ShutterSpeedFormatter {

    /** 選択可能なシャッタースピード値(ナノ秒) */
    val standardSpeeds: List<Long> = listOf(
        // 高速
        500_000L,        // 1/2000
        1_000_000L,      // 1/1000
        2_000_000L,      // 1/500
        4_000_000L,      // 1/250
        8_000_000L,      // 1/125
        16_666_667L,     // 1/60
        33_333_333L,     // 1/30
        66_666_667L,     // 1/15
        125_000_000L,    // 1/8
        250_000_000L,    // 1/4
        500_000_000L,    // 1/2
        1_000_000_000L,  // 1s
    )

    fun format(ns: Long): String {
        val seconds = ns.toDouble() / 1_000_000_000.0
        return if (seconds >= 1.0) {
            "${seconds.toInt()}s"
        } else {
            val denom = (1.0 / seconds).toInt()
            "1/${denom}"
        }
    }

    /** 指定範囲内のシャッタースピードのみを返す */
    fun availableSpeeds(rangeNs: LongRange): List<Long> {
        return standardSpeeds.filter { it in rangeNs }
    }
}
