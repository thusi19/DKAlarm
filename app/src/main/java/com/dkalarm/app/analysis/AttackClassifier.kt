package com.dkalarm.app.analysis

import com.dkalarm.app.model.AttackColor
import com.dkalarm.app.model.AttackKind
import com.dkalarm.app.model.CrownState

class AttackClassifier {
    fun classify(crown: CrownState, color: AttackColor): AttackKind = when (crown) {
        CrownState.YES -> AttackKind.NOBLE
        CrownState.MAYBE -> AttackKind.POSSIBLE_NOBLE
        CrownState.NO -> when (color) {
            AttackColor.RED -> AttackKind.RED_ATTACK
            AttackColor.BROWN -> AttackKind.BROWN_ATTACK
            else -> AttackKind.OTHER
        }
    }
}
