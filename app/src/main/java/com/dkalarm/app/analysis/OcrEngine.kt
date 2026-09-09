package com.dkalarm.app.analysis

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class OcrToken(val text: String, val bounds: Rect)
data class OcrResult(val fullText: String, val tokens: List<OcrToken>)

interface OcrEngine {
    suspend fun recognize(bitmap: Bitmap): OcrResult
}

class MlKitOcrEngine : OcrEngine {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override suspend fun recognize(bitmap: Bitmap): OcrResult = suspendCancellableCoroutine { cont ->
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { text ->
                val tokens = text.textBlocks.flatMap { block ->
                    block.lines.flatMap { line ->
                        line.elements.mapNotNull { el -> el.boundingBox?.let { OcrToken(el.text, it) } }
                    }
                }
                if (cont.isActive) cont.resume(OcrResult(text.text, tokens))
            }
            .addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
    }
}
