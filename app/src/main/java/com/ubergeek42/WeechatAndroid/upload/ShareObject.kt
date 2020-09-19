package com.ubergeek42.WeechatAndroid.upload

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.net.Uri
import android.text.*
import android.text.style.ImageSpan
import android.widget.EditText
import androidx.annotation.MainThread
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.ubergeek42.WeechatAndroid.media.Config.THUMBNAIL_CORNER_RADIUS
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.concurrent.thread


data class ShareSpan(
        val context: Context,
        val suri: Suri,
        val bitmap: Bitmap
) : ImageSpan(context, bitmap) {
    // this is a workaround for the weird way android measures ImageSpans
    // see https://stackoverflow.com/a/63948243/1449683
    override fun getSize(paint: Paint, text: CharSequence?,
                         start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        val oldBottom = fm?.bottom
        val result = super.getSize(paint, text, start, end, fm)
        fm?.apply {
            top += oldBottom!!
            ascent = top
            bottom = oldBottom
        }
        return result
    }
}


////////////////////////////////////////////////////////////////////////////////////////////////////


enum class InsertAt {
    CURRENT_POSITION, END
}


interface ShareObject {
    fun insert(editText: EditText, insertAt: InsertAt)
}


data class TextShareObject(
    val text: CharSequence
) : ShareObject {
    @MainThread override fun insert(editText: EditText, insertAt: InsertAt) {
        editText.insertAddingSpacesAsNeeded(insertAt, text)
    }
}


// non-breaking space. regular spaces and characters can lead to some problems with some keyboards...
const val PLACEHOLDER_TEXT = "\u00a0"

@Suppress("ArrayInDataClass")
open class UrisShareObject(
    private val suris: List<Suri>
) : ShareObject {
    private val bitmaps: Array<Bitmap?> = arrayOfNulls(suris.size)

    override fun insert(editText: EditText, insertAt: InsertAt) {
        val context = editText.context
        getAllImagesAndThen(context) {
            for (i in suris.indices) {
                editText.insertAddingSpacesAsNeeded(insertAt, makeImageSpanned(context, i))
            }
        }
    }

    private fun getAllImagesAndThen(context: Context, then: () -> Unit) {
        suris.forEachIndexed { i, suri ->
            getThumbnailAndThen(context, suri.uri) { bitmap ->
                bitmaps[i] = bitmap
                if (bitmaps.all { it != null }) then()
            }
        }
    }

    private fun makeImageSpanned(context: Context, i: Int) : Spanned {
        val spanned = SpannableString(PLACEHOLDER_TEXT)
        val imageSpan = ShareSpan(context, suris[i], bitmaps[i]!!)
        spanned.setSpan(imageSpan, 0, PLACEHOLDER_TEXT.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return spanned
    }

    companion object {
        @JvmStatic @Throws(FileNotFoundException::class, IOException::class, SecurityException::class)
        fun fromUris(uris: List<Uri>): UrisShareObject {
            return UrisShareObject(uris.map { Suri.fromUri(it) })
        }
    }
}


////////////////////////////////////////////////////////////////////////////////////////////////////


val THUMBNAIL_MAX_WIDTH = 80.dp_to_px
val THUMBNAIL_MAX_HEIGHT = 80.dp_to_px

// this starts the upload in a worker thread and exits immediately.
// target callbacks will be called on the main thread
fun getThumbnailAndThen(context: Context, uri: Uri, then: (bitmap: Bitmap) -> Unit) {
    Glide.with(context)
            .asBitmap()
            .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
            .transform(RoundedCorners(THUMBNAIL_CORNER_RADIUS))
            .load(uri)
            .into(object : CustomTarget<Bitmap>(THUMBNAIL_MAX_WIDTH, THUMBNAIL_MAX_HEIGHT) {
                @MainThread override fun onResourceReady(bitmap: Bitmap, transition: Transition<in Bitmap>?) {
                    then(bitmap)
                }

                @MainThread override fun onLoadFailed(errorDrawable: Drawable?) {
                    then(makeThumbnailForUri(uri))
                }

                override fun onLoadCleared(placeholder: Drawable?) {
                    // this shouldn't happen
                }
            })
}


const val TEXT_COLOR = Color.BLACK
val TEXT_SIZE = 13.dp_to_px.toFloat()
val PADDING = 3.dp_to_px
val LAYOUT_MAX_WIDTH  = THUMBNAIL_MAX_WIDTH  - (PADDING * 2)
val LAYOUT_MAX_HEIGHT = THUMBNAIL_MAX_HEIGHT - (PADDING * 2)

fun makeThumbnailForUri(uri: Uri) : Bitmap {
    val text = uri.lastPathSegment ?: "file"

    val backgroundPaint = Paint()
    backgroundPaint.color = Color.WHITE
    backgroundPaint.style = Paint.Style.FILL

    val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    textPaint.textSize = TEXT_SIZE
    textPaint.color = TEXT_COLOR

    val layout = StaticLayout(text, textPaint, LAYOUT_MAX_WIDTH,
            Layout.Alignment.ALIGN_NORMAL, 1.0f, 0.0f, false)
    val left = if (layout.maxLineWidth > LAYOUT_MAX_WIDTH) PADDING else (THUMBNAIL_MAX_WIDTH - layout.maxLineWidth) / 2
    val top = if (layout.height > LAYOUT_MAX_HEIGHT) PADDING else (THUMBNAIL_MAX_HEIGHT - layout.height) / 2

    val image = Bitmap.createBitmap(THUMBNAIL_MAX_WIDTH, THUMBNAIL_MAX_HEIGHT, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(image)
    canvas.drawPaint(backgroundPaint)
    canvas.translate(left.toFloat(), top.toFloat())
    layout.draw(canvas)

    return image
}


////////////////////////////////////////////////////////////////////////////////////////////////////


@MainThread fun EditText.insertAddingSpacesAsNeeded(insertAt: InsertAt, word: CharSequence) {
    val pos = if (insertAt == InsertAt.CURRENT_POSITION) selectionEnd else text.length
    val shouldPrependSpace = pos > 0 && text[pos - 1] != ' '
    val shouldAppendSpace = pos < text.length && text[pos + 1] != ' '

    val wordWithSurroundingSpaces = SpannableStringBuilder()
    if (shouldPrependSpace) wordWithSurroundingSpaces.append(' ')
    wordWithSurroundingSpaces.append(word)
    if (shouldAppendSpace) wordWithSurroundingSpaces.append(' ')

    text.insert(pos, wordWithSurroundingSpaces)
}


val Layout.maxLineWidth : Int get() {
    return (0 until lineCount).maxOfOrNull { getLineWidth(it) }?.toInt() ?: width
}