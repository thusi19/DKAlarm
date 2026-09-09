package com.dkalarm.app.analysis

import com.dkalarm.app.model.AttackColor
import com.dkalarm.app.model.AttackKind
import com.dkalarm.app.model.CrownState
import org.junit.Assert.assertEquals
import org.junit.Test

class AttackClassifierTest {
    private val classifier = AttackClassifier()

    @Test fun crownAlwaysWinsOverColor() {
        AttackColor.entries.forEach { color ->
            assertEquals(AttackKind.NOBLE, classifier.classify(CrownState.YES, color))
        }
    }

    @Test fun maybeCrownAlwaysRequiresReview() {
        AttackColor.entries.forEach { color ->
            assertEquals(AttackKind.POSSIBLE_NOBLE, classifier.classify(CrownState.MAYBE, color))
        }
    }

    @Test fun redWithoutCrownIsRedAttack() {
        assertEquals(AttackKind.RED_ATTACK, classifier.classify(CrownState.NO, AttackColor.RED))
    }
}
