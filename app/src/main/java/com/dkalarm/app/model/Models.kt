package com.dkalarm.app.model

import android.graphics.Rect
import java.time.Instant

enum class AttackColor { GREEN, RED, BROWN, BLUE, GRAY, UNKNOWN }
enum class CrownState { YES, MAYBE, NO }
enum class AttackKind { NOBLE, POSSIBLE_NOBLE, RED_ATTACK, BROWN_ATTACK, OTHER }
enum class ValidationState { OK, CHECK_TIME, INVALID }

data class OcrField(
    val text: String = "",
    val confidence: Float = 0f,
    val bounds: Rect? = null
)

data class AttackCandidate(
    val id: String,
    val rowBounds: Rect,
    val targetVillage: OcrField,
    val absoluteArrivalText: OcrField,
    val relativeArrivalText: OcrField,
    val arrivalInstant: Instant?,
    val color: AttackColor,
    val colorConfidence: Float,
    val crown: CrownState,
    val crownScore: Float,
    val kind: AttackKind,
    val validation: ValidationState,
    val warnings: List<String> = emptyList(),
    val sourceScreenshotHash: String
) {
    val needsReview: Boolean
        get() = crown == CrownState.MAYBE || validation != ValidationState.OK ||
            targetVillage.confidence < 0.65f || absoluteArrivalText.confidence < 0.65f
}

data class EditableAttack(
    val id: String,
    val targetVillage: String,
    val arrivalText: String,
    val arrivalInstant: Instant?,
    val color: AttackColor,
    val crown: CrownState,
    val validation: ValidationState,
    val warnings: List<String>,
    val sourceScreenshotHash: String
)
