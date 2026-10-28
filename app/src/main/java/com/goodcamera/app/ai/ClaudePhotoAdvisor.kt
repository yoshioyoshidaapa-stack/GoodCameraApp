package com.goodcamera.app.ai

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.AnthropicException
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.errors.RateLimitException
import com.anthropic.models.beta.messages.BetaBase64ImageSource
import com.anthropic.models.beta.messages.BetaContentBlockParam
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.MessageCreateParams
import java.util.Base64

/**
 * 撮影した写真をClaude APIに送り、写真の説明と撮影アドバイスを日本語でもらう
 *
 * ネットワーク通信をするので、メインスレッド以外（Dispatchers.IO）から呼ぶこと
 */
object ClaudePhotoAdvisor {

    private const val MODEL = "claude-opus-5-5"

    private const val SYSTEM_PROMPT =
        "あなたは写真に詳しいアドバイザーです。送られた写真について、" +
            "カメラや写真の専門知識がない人にも分かる日本語で答えてください。" +
            "次の2つを、見出し付きで簡潔に書いてください。\n" +
            "【写っているもの】何が写っているかを2〜3文で。\n" +
            "【もっと良くするコツ】構図・明るさ・ピントなど、次に撮るときに役立つ具体的なコツを3つまで。"

    /**
     * @param apiKey Anthropic の API キー（sk-ant- で始まる文字列）
     * @param jpegBytes 送る写真（JPEG）
     * @return Claudeの回答テキスト。失敗したときはエラー内容を説明する文章
     */
    fun askAboutPhoto(apiKey: String, jpegBytes: ByteArray): String {
        if (apiKey.isBlank()) {
            return "APIキーが設定されていません。設定画面の「Claude APIキー」に入力してください。"
        }

        val client = AnthropicOkHttpClient.builder()
            .apiKey(apiKey.trim())
            .build()

        val imageBase64 = Base64.getEncoder().encodeToString(jpegBytes)

        val params = MessageCreateParams.builder()
            .model(MODEL)
            .maxTokens(16000L)
            .system(SYSTEM_PROMPT)
            .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.LOW).build())
            // Claudeが安全上の理由で回答を断った場合、別モデルで自動的に再試行する
            .addBeta("server-side-fallback-2026-07-01")
            .fallbacksDefault()
            .addUserMessageOfContentBlockParams(
                listOf(
                    BetaContentBlockParam.ofImage(
                        BetaBase64ImageSource.builder()
                            .mediaType(BetaBase64ImageSource.MediaType.IMAGE_JPEG)
                            .data(imageBase64)
                            .build(),
                    ),
                    BetaContentBlockParam.ofText("この写真について教えてください。"),
                ),
            )
            .build()

        return try {
            val response = client.beta().messages().create(params)
            if (response.stopReason().orElse(null) == BetaStopReason.REFUSAL) {
                return "この写真にはお答えできませんでした。別の写真で試してください。"
            }
            response.content()
                .mapNotNull { block -> block.text().orElse(null)?.text() }
                .joinToString("\n")
                .ifBlank { "回答が空でした。もう一度試してください。" }
        } catch (e: UnauthorizedException) {
            "APIキーが正しくないようです。設定画面で入力し直してください。"
        } catch (e: RateLimitException) {
            "短時間に使いすぎたため、一時的に制限されています。少し待ってから試してください。"
        } catch (e: AnthropicServiceException) {
            "Claudeからエラーが返されました（${e.statusCode()}）：${e.message}"
        } catch (e: AnthropicIoException) {
            "通信に失敗しました。インターネット接続を確認してください。（${e.message}）"
        } catch (e: AnthropicException) {
            "予期しないエラーが起きました：${e.message}"
        } finally {
            client.close()
        }
    }
}
