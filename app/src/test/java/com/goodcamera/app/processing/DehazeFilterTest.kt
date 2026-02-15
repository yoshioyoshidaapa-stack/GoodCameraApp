package com.goodcamera.app.processing

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class DehazeFilterTest {

    private lateinit var hazyBitmap: Bitmap
    private lateinit var clearBitmap: Bitmap

    @Before
    fun setUp() {
        // かすみのかかった画像をシミュレート（全体的に白っぽい＝ダークチャネルが高い）
        hazyBitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        for (y in 0 until 16) {
            for (x in 0 until 16) {
                // 霧っぽい色: RGB各チャネルが高く、ダークチャネル（最小値）も高い
                hazyBitmap.setPixel(x, y, Color.rgb(180 + x, 190 + y / 2, 200))
            }
        }

        // かすみのない画像（ダークチャネルが低い＝各パッチに暗い画素がある）
        clearBitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        for (y in 0 until 16) {
            for (x in 0 until 16) {
                if (x % 3 == 0) {
                    clearBitmap.setPixel(x, y, Color.rgb(200, 50, 10)) // R優位、Bが暗い
                } else if (x % 3 == 1) {
                    clearBitmap.setPixel(x, y, Color.rgb(10, 180, 20)) // G優位、Rが暗い
                } else {
                    clearBitmap.setPixel(x, y, Color.rgb(30, 20, 220)) // B優位、他が暗い
                }
            }
        }
    }

    @Test
    fun `かすみ画像のdehazeでコントラストが向上する`() {
        val result = DehazeFilter.dehaze(hazyBitmap, strength = 0.7f)

        // 結果が入力と同じサイズであること
        assertEquals(hazyBitmap.width, result.width)
        assertEquals(hazyBitmap.height, result.height)

        // かすみ除去後はダークチャネルの平均が下がる（コントラスト向上）
        val originalDark = avgDarkChannel(hazyBitmap)
        val resultDark = avgDarkChannel(result)
        assertTrue(
            "かすみ除去後のダークチャネル($resultDark)が元画像($originalDark)より低いこと",
            resultDark < originalDark
        )
    }

    @Test
    fun `かすみのない画像では変化が少ない`() {
        val result = DehazeFilter.dehaze(clearBitmap, strength = 0.5f)

        // クリアな画像ではダークチャネルがもともと低いので、大きな変化はない
        val originalDark = avgDarkChannel(clearBitmap)
        val resultDark = avgDarkChannel(result)

        // 変化があってもダークチャネルがさらに下がるだけ（上がることはない）
        assertTrue(
            "クリア画像のダークチャネル変化が妥当($originalDark -> $resultDark)",
            resultDark <= originalDark + 5 // 小さな誤差は許容
        )
    }

    @Test
    fun `estimateHazeLevelでかすみ画像が高いスコアを返す`() {
        val hazeLevel = DehazeFilter.estimateHazeLevel(hazyBitmap)
        assertTrue("かすみ画像のhazeLevel($hazeLevel)が0.5以上", hazeLevel >= 0.5f)
    }

    @Test
    fun `estimateHazeLevelでクリア画像が低いスコアを返す`() {
        val hazeLevel = DehazeFilter.estimateHazeLevel(clearBitmap)
        assertTrue("クリア画像のhazeLevel($hazeLevel)が0.3以下", hazeLevel <= 0.3f)
    }

    @Test
    fun `strength=0でほぼ変化なし`() {
        val result = DehazeFilter.dehaze(hazyBitmap, strength = 0f)

        // strength=0 → t(x)=1 → J(x)=I(x) 完全に元画像と同じはず
        val originalPixels = IntArray(16 * 16)
        hazyBitmap.getPixels(originalPixels, 0, 16, 0, 0, 16, 16)
        val resultPixels = IntArray(16 * 16)
        result.getPixels(resultPixels, 0, 16, 0, 0, 16, 16)

        var maxDiff = 0
        for (i in originalPixels.indices) {
            val diffR = kotlin.math.abs(((originalPixels[i] shr 16) and 0xFF) - ((resultPixels[i] shr 16) and 0xFF))
            val diffG = kotlin.math.abs(((originalPixels[i] shr 8) and 0xFF) - ((resultPixels[i] shr 8) and 0xFF))
            val diffB = kotlin.math.abs((originalPixels[i] and 0xFF) - (resultPixels[i] and 0xFF))
            maxDiff = maxOf(maxDiff, diffR, diffG, diffB)
        }
        assertTrue("strength=0で最大差分($maxDiff)が2以下", maxDiff <= 2)
    }

    @Test
    fun `出力ピクセルが有効範囲内`() {
        val result = DehazeFilter.dehaze(hazyBitmap, strength = 1.0f)
        val pixels = IntArray(result.width * result.height)
        result.getPixels(pixels, 0, result.width, 0, 0, result.width, result.height)

        for (i in pixels.indices) {
            val r = (pixels[i] shr 16) and 0xFF
            val g = (pixels[i] shr 8) and 0xFF
            val b = pixels[i] and 0xFF
            assertTrue("R=$r は0-255の範囲内", r in 0..255)
            assertTrue("G=$g は0-255の範囲内", g in 0..255)
            assertTrue("B=$b は0-255の範囲内", b in 0..255)
        }
    }

    private fun avgDarkChannel(bitmap: Bitmap): Float {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        var sum = 0L
        for (pixel in pixels) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            sum += minOf(r, g, b)
        }
        return sum.toFloat() / pixels.size
    }
}
