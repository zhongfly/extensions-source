package eu.kanade.tachiyomi.extension.zh.copymangas

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import eu.kanade.tachiyomi.extension.zh.copymangas.CopyMangas.Companion.COMMENTS_FLAG
import keiyoushi.utils.parseAs
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer

fun parseChapterComments(response: Response, count: Int): List<String> {
    val comments = response.parseAs<ResultDto<ListDto<CommentDto>>>().results.list.map { it.comment }.take(count)
    return comments.ifEmpty { listOf("暂无吐槽") }
}

object CommentsInterceptor : Interceptor {
    private const val MAX_HEIGHT = 1920
    private const val WIDTH = 1080
    private const val UNIT = 32
    private const val UNIT_F = UNIT.toFloat()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (request.tag(String::class) != COMMENTS_FLAG) {
            return response
        }
        val comments = parseChapterComments(response, MAX_HEIGHT / (UNIT * 2) - 1).toMutableList()
        comments.add(0, "章末吐槽：")

        val paint = TextPaint().apply {
            color = Color.BLACK
            textSize = UNIT_F
            isAntiAlias = true
        }

        var height = UNIT
        val layouts = comments.map {
            @Suppress("DEPRECATION")
            StaticLayout(it, paint, WIDTH - 2 * UNIT, Layout.Alignment.ALIGN_NORMAL, 1f, 0f, false)
        }.takeWhile {
            val lineHeight = it.height + UNIT
            if (height + lineHeight <= MAX_HEIGHT) {
                height += lineHeight
                true
            } else {
                false
            }
        }

        val bitmap = Bitmap.createBitmap(WIDTH, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        val canvas = Canvas(bitmap)

        var y = UNIT
        for (layout in layouts) {
            canvas.save()
            canvas.translate(UNIT_F, y.toFloat())
            layout.draw(canvas)
            canvas.restore()
            y += layout.height + UNIT
        }

        val responseBody = Buffer().run {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream())
            asResponseBody("image/jpeg".toMediaType())
        }
        return response.newBuilder().body(responseBody).build()
    }
}
