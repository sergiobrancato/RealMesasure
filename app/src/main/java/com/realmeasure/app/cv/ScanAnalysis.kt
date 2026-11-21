package com.realmeasure.app.cv

import com.realmeasure.app.model.ObjectGeometry
import com.realmeasure.app.model.ReferenceMode

/**
 * Everything the result screen needs: the metric canvas, all detected objects
 * and the auto-selected (largest) object index.
 */
data class ScanAnalysis(
    val mode: ReferenceMode,
    val calibration: CalibrationResult,
    val objects: List<ObjectGeometry>,
    val selectedIndex: Int = 0,
)
