package com.realmeasure.app.cv

import android.graphics.Bitmap
import com.realmeasure.app.config.AppConfig
import com.realmeasure.app.model.HoleGeometry
import com.realmeasure.app.model.HoleSpacing
import com.realmeasure.app.model.ObjectGeometry
import com.realmeasure.app.model.Pt
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Solid-silhouette irregular object extraction on the flattened canvas.
 *
 * Why this is not a raw Canny contour measurement:
 * a thick edge ring has an outer and an inner boundary. Measuring the ring
 * directly roughly doubles the perimeter and can under/over-count the area.
 * Instead, every candidate outer boundary is filled into a solid mask, the
 * clean outer boundary is re-extracted from that mask, and area/perimeter are
 * measured from the filled silhouette.
 *
 * Object seeding combines:
 *  - Lab color distance from the mat (handles light, dark and coloured objects),
 *  - luminance edges and colour-gradient edges (low-contrast white-on-white),
 *  - L-channel adaptive threshold fallback.
 *
 * Holes are accepted only when they are inside the solid object, look like the
 * background (true pass-through), are >= 2.5 mm² and have circularity >= 0.45.
 */
object IrregularShapeAnalyzer {

    class AnalysisException(
        val code: String,
        message: String,
    ) : Exception(message)

    private data class BackgroundLab(val l: Double, val a: Double, val b: Double)

    private class SceneContext(
        val l: Mat,
        val a: Mat,
        val b: Mat,
        val colorDiff: Mat,
        val backgroundLike: Mat,
        val kernel: Mat,
        val referenceRect: PixelRect,
        val calibration: CalibrationResult,
        val width: Int,
        val height: Int,
    ) {
        val s: Double get() = calibration.pixelsPerMm
    }

    // ------------------------------------------------------------------
    // Public entry points
    // ------------------------------------------------------------------

    fun analyze(warped: Bitmap, calibration: CalibrationResult): List<ObjectGeometry> =
        withMatPool {
            val scene = prepareScene(warped, calibration)
            val candidates = ArrayList<ObjectGeometry>()
            val colorMask = Mat()
            val edgeL = Mat()
            val edgeC = Mat()
            val edges = Mat()
            val colorEdge = Mat()

            try {
                Imgproc.threshold(
                    scene.colorDiff,
                    colorMask,
                    AppConfig.Analysis.BG_COLOR_TOLERANCE,
                    255.0,
                    Imgproc.THRESH_BINARY,
                )
                Imgproc.Canny(
                    scene.l,
                    edgeL,
                    AppConfig.Analysis.CANNY_LOW,
                    AppConfig.Analysis.CANNY_HIGH,
                )
                Imgproc.Canny(scene.colorDiff, edgeC, 20.0, 60.0)
                Core.bitwise_or(edgeL, edgeC, edges)
                Imgproc.morphologyEx(edges, edges, Imgproc.MORPH_CLOSE, scene.kernel)
                Core.bitwise_or(colorMask, edges, colorEdge)

                candidates += extractCandidates(colorEdge, scene)
                candidates += extractCandidates(edges, scene)
            } finally {
                colorMask.release()
                edgeL.release()
                edgeC.release()
                edges.release()
                colorEdge.release()
            }

            // Adaptive L fallback for the hardest low-contrast silhouettes.
            if (candidates.isEmpty()) {
                val adaptiveInv = Mat()
                val adaptiveNorm = Mat()
                try {
                    Imgproc.adaptiveThreshold(
                        scene.l,
                        adaptiveInv,
                        255.0,
                        Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                        Imgproc.THRESH_BINARY_INV,
                        AppConfig.Analysis.BINARY_BLOCK,
                        AppConfig.Analysis.BINARY_C,
                    )
                    Imgproc.morphologyEx(adaptiveInv, adaptiveInv, Imgproc.MORPH_CLOSE, scene.kernel)
                    candidates += extractCandidates(adaptiveInv, scene)

                    Imgproc.adaptiveThreshold(
                        scene.l,
                        adaptiveNorm,
                        255.0,
                        Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                        Imgproc.THRESH_BINARY,
                        AppConfig.Analysis.BINARY_BLOCK,
                        AppConfig.Analysis.BINARY_C,
                    )
                    Imgproc.morphologyEx(adaptiveNorm, adaptiveNorm, Imgproc.MORPH_CLOSE, scene.kernel)
                    candidates += extractCandidates(adaptiveNorm, scene)
                } finally {
                    adaptiveInv.release()
                    adaptiveNorm.release()
                }
            }

            val deduped = deduplicate(candidates)
            if (deduped.isEmpty()) {
                throw AnalysisException(
                    "NO_OBJECT",
                    "No object was found beside the reference. Use a contrasting matte surface, " +
                        "keep the object fully visible and avoid glare.",
                )
            }

            deduped
                .sortedByDescending { it.areaNetMm2 }
                .mapIndexed { index, geometry -> geometry.copy(index = index) }
        }

    /**
     * User-drawn box refinement. GrabCut snaps the silhouette to the object
     * inside [rect] and re-runs the solid-fill geometry and hole filters.
     */
    fun refineWithGrabCut(
        warped: Bitmap,
        calibration: CalibrationResult,
        rect: PixelRect,
    ): ObjectGeometry = withMatPool {
        if (rect.width < 10 || rect.height < 10) {
            throw AnalysisException(
                "GRABCUT_BOX",
                "Draw a larger box around the object.",
            )
        }
        val w = warped.width
        val h = warped.height
        val safeRect = Rect(
            rect.left.coerceIn(0, w - 2),
            rect.top.coerceIn(0, h - 2),
            rect.width.coerceAtMost(w - rect.left.coerceIn(0, w - 2) - 1).coerceAtLeast(2),
            rect.height.coerceAtMost(h - rect.top.coerceIn(0, h - 2) - 1).coerceAtLeast(2),
        )

        val scene = prepareScene(warped, calibration)
        val rgba = Mat()
        val rgb = Mat()
        val mask = Mat(w, h, CvType.CV_8UC1, Scalar(Imgproc.GC_BGD.toDouble()))
        val bgdModel = Mat()
        val fgdModel = Mat()
        val foreground = Mat()

        try {
            Utils.bitmapToMat(warped, rgba)
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
            try {
                Imgproc.grabCut(
                    rgb,
                    mask,
                    safeRect,
                    bgdModel,
                    fgdModel,
                    AppConfig.Analysis.GRABCUT_ITERATIONS,
                    Imgproc.GC_INIT_WITH_RECT,
                )
            } catch (exception: Exception) {
                throw AnalysisException(
                    "GRABCUT_FAILED",
                    "GrabCut could not separate the object. Draw a tighter box around it.",
                )
            }

            val fg1 = Mat()
            val fg3 = Mat()
            try {
                Core.compare(mask, Scalar(Imgproc.GC_FGD.toDouble()), fg1, Core.CMP_EQ)
                Core.compare(mask, Scalar(Imgproc.GC_PR_FGD.toDouble()), fg3, Core.CMP_EQ)
                Core.bitwise_or(fg1, fg3, foreground)
            } finally {
                fg1.release()
                fg3.release()
            }
            Imgproc.morphologyEx(foreground, foreground, Imgproc.MORPH_CLOSE, scene.kernel)
            eraseReference(foreground, scene.referenceRect)

            val contours = ArrayList<MatOfPoint>()
            val hierarchy = Mat()
            try {
                Imgproc.findContours(
                    foreground,
                    contours,
                    hierarchy,
                    Imgproc.RETR_EXTERNAL,
                    Imgproc.CHAIN_APPROX_SIMPLE,
                )
                val outer = contours.maxByOrNull { Imgproc.contourArea(it) }
                    ?: throw AnalysisException(
                        "GRABCUT_EMPTY",
                        "GrabCut did not find an object in the box. Draw a tighter box around it.",
                    )
                val solid = Mat.zeros(
                    Size(w.toDouble(), h.toDouble()),
                    CvType.CV_8UC1,
                )
                try {
                    Imgproc.drawContours(solid, listOf(outer), 0, Scalar(255.0), -1)
                    val clean = extractCleanContour(solid)
                        ?: throw AnalysisException(
                            "GRABCUT_EMPTY",
                            "GrabCut did not produce a solid silhouette. Try a tighter box.",
                        )
                    try {
                        buildObject(solid, clean, scene)
                            ?: throw AnalysisException(
                                "GRABCUT_EMPTY",
                                "GrabCut produced a silhouette that was too small. Try a tighter box.",
                            )
                    } finally {
                        clean.release()
                    }
                } finally {
                    solid.release()
                }
            } finally {
                contours.forEach { runCatching { it.release() } }
                hierarchy.release()
            }
        } finally {
            rgba.release()
            rgb.release()
            mask.release()
            bgdModel.release()
            fgdModel.release()
            foreground.release()
        }
    }

    // ------------------------------------------------------------------
    // Scene preparation (Lab, background colour, background-like mask)
    // ------------------------------------------------------------------

    private fun MatPool.prepareScene(
        warped: Bitmap,
        calibration: CalibrationResult,
    ): SceneContext {
        val s = calibration.pixelsPerMm
        if (!s.isFinite() || s <= 0.0) {
            throw AnalysisException("BAD_SCALE", "The calibrated scale was invalid.")
        }
        val width = warped.width
        val height = warped.height

        val rgba = Mat()
        val rgb = Mat()
        val lab = Mat()
        val channels = ArrayList<Mat>()
        try {
            Utils.bitmapToMat(warped, rgba)
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
            Imgproc.cvtColor(rgb, lab, Imgproc.COLOR_RGB2Lab)
            Core.split(lab, channels)
        } finally {
            rgba.release()
            rgb.release()
            lab.release()
        }
        val l = channels.getOrNull(0) ?: throw AnalysisException("LAB_FAILED", "Could not read luminance.")
        val a = channels.getOrNull(1) ?: throw AnalysisException("LAB_FAILED", "Could not read chroma a.")
        val b = channels.getOrNull(2) ?: throw AnalysisException("LAB_FAILED", "Could not read chroma b.")
        channels.forEach { it.tracked() }

        val referenceRect = inflateReferenceRect(
            calibration.referenceRectPx,
            (AppConfig.Calibration.REFERENCE_MASK_MARGIN_MM * s).roundToInt(),
            width,
            height,
        )
        val background = estimateBackground(l, a, b, width, height, referenceRect)

        val diffL = Mat()
        val diffA = Mat()
        val diffB = Mat()
        val tmp = Mat()
        val colorDiff = Mat()
        val colorClose = Mat()
        val lumaDiff = Mat()
        val lumaClose = Mat()
        val backgroundLike = Mat()
        try {
            Core.absdiff(l, Scalar(background.l), diffL)
            Core.absdiff(a, Scalar(background.a), diffA)
            Core.absdiff(b, Scalar(background.b), diffB)
            Core.max(diffL, diffA, tmp)
            Core.max(tmp, diffB, colorDiff)
            Imgproc.threshold(
                colorDiff,
                colorClose,
                AppConfig.Analysis.BG_COLOR_TOLERANCE,
                255.0,
                Imgproc.THRESH_BINARY_INV,
            )
            Core.absdiff(l, Scalar(background.l), lumaDiff)
            Imgproc.threshold(
                lumaDiff,
                lumaClose,
                AppConfig.Analysis.BG_LUMA_TOLERANCE,
                255.0,
                Imgproc.THRESH_BINARY_INV,
            )
            Core.bitwise_and(colorClose, lumaClose, backgroundLike)
        } finally {
            diffL.release()
            diffA.release()
            diffB.release()
            tmp.release()
            colorClose.release()
            lumaDiff.release()
            lumaClose.release()
        }

        val kernel = Imgproc.getStructuringElement(
            Imgproc.MORPH_RECT,
            Size(
                AppConfig.Analysis.CLOSE_KERNEL.toDouble(),
                AppConfig.Analysis.CLOSE_KERNEL.toDouble(),
            ),
        )
        val colorDiffTracked = colorDiff.tracked()
        val backgroundLikeTracked = backgroundLike.tracked()
        val kernelTracked = kernel.tracked()

        return SceneContext(
            l = l,
            a = a,
            b = b,
            colorDiff = colorDiffTracked,
            backgroundLike = backgroundLikeTracked,
            kernel = kernelTracked,
            referenceRect = referenceRect,
            calibration = calibration,
            width = width,
            height = height,
        )
    }

    /**
     * Median Lab value of the mat around the warped image border. The reference
     * rectangle is excluded so a card/sheet at the edge cannot bias the
     * background estimate.
     */
    private fun estimateBackground(
        l: Mat,
        a: Mat,
        b: Mat,
        width: Int,
        height: Int,
        reference: PixelRect,
    ): BackgroundLab {
        val histL = IntArray(256)
        val histA = IntArray(256)
        val histB = IntArray(256)
        var total = 0

        val lData = ByteArray(width * height)
        val aData = ByteArray(width * height)
        val bData = ByteArray(width * height)
        l.get(0, 0, lData)
        a.get(0, 0, aData)
        b.get(0, 0, bData)

        val border = max(4, min(width, height) / 40)
        val step = 3
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val isBorder = x < border || x >= width - border ||
                    y < border || y >= height - border
                val insideReference = x >= reference.left && x <= reference.right &&
                    y >= reference.top && y <= reference.bottom
                if (isBorder && !insideReference) {
                    val index = y * width + x
                    histL[lData[index].toInt() and 0xFF]++
                    histA[aData[index].toInt() and 0xFF]++
                    histB[bData[index].toInt() and 0xFF]++
                    total++
                }
                x += step
            }
            y += step
        }

        if (total < 32) {
            // The reference occupies most of the border: retry with the
            // reference included. The mat is normally still the majority.
            histL.fill(0)
            histA.fill(0)
            histB.fill(0)
            total = 0
            y = 0
            while (y < height) {
                var x = 0
                while (x < width) {
                    val isBorder = x < border || x >= width - border ||
                        y < border || y >= height - border
                    if (isBorder) {
                        val index = y * width + x
                        histL[lData[index].toInt() and 0xFF]++
                        histA[aData[index].toInt() and 0xFF]++
                        histB[bData[index].toInt() and 0xFF]++
                        total++
                    }
                    x += step
                }
                y += step
            }
        }

        if (total < 32) {
            // Fallback: image means. Rare, and still better than a hardcoded gray.
            val meanL = Core.mean(l).`val`[0]
            val meanA = Core.mean(a).`val`[0]
            val meanB = Core.mean(b).`val`[0]
            return BackgroundLab(meanL, meanA, meanB)
        }
        return BackgroundLab(
            l = medianFromHistogram(histL, total).toDouble(),
            a = medianFromHistogram(histA, total).toDouble(),
            b = medianFromHistogram(histB, total).toDouble(),
        )
    }

    private fun medianFromHistogram(histogram: IntArray, total: Int): Int {
        val target = total / 2
        var cumulative = 0
        for (value in histogram.indices) {
            cumulative += histogram[value]
            if (cumulative >= target) return value
        }
        return 128
    }

    // ------------------------------------------------------------------
    // Candidate extraction + solid silhouette
    // ------------------------------------------------------------------

    private fun extractCandidates(
        seed: Mat,
        scene: SceneContext,
    ): List<ObjectGeometry> {
        val cfg = AppConfig.Analysis
        val s = scene.s
        val frameArea = scene.width.toDouble() * scene.height
        val minAreaPx = cfg.MIN_OBJECT_AREA_MM2 * s * s
        val maxAreaPx = cfg.MAX_OBJECT_AREA_MM2 * s * s

        Imgproc.morphologyEx(seed, seed, Imgproc.MORPH_CLOSE, scene.kernel)
        eraseReference(seed, scene.referenceRect)

        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        val results = ArrayList<ObjectGeometry>()
        try {
            Imgproc.findContours(
                seed,
                contours,
                hierarchy,
                Imgproc.RETR_EXTERNAL,
                Imgproc.CHAIN_APPROX_SIMPLE,
            )
            for (contour in contours) {
                val areaPx = Imgproc.contourArea(contour)
                if (areaPx < minAreaPx || areaPx > maxAreaPx) continue
                if (areaPx / frameArea > 0.88) continue

                val bounding = Imgproc.boundingRect(contour)
                if (touchesBorder(bounding, scene.width, scene.height)) continue
                if (overlapsReference(bounding, scene.referenceRect)) continue

                val solid = Mat.zeros(
                    Size(scene.width.toDouble(), scene.height.toDouble()),
                    CvType.CV_8UC1,
                )
                try {
                    Imgproc.drawContours(solid, listOf(contour), 0, Scalar(255.0), -1)
                    val clean = extractCleanContour(solid) ?: continue
                    try {
                        if (touchesBorder(
                                Imgproc.boundingRect(clean),
                                scene.width,
                                scene.height,
                            )
                        ) {
                            continue
                        }
                        val geometry = buildObject(solid, clean, scene) ?: continue
                        results += geometry
                    } finally {
                        clean.release()
                    }
                } finally {
                    solid.release()
                }
            }
        } finally {
            contours.forEach { runCatching { it.release() } }
            hierarchy.release()
        }
        return results
    }

    private fun extractCleanContour(solidMask: Mat): MatOfPoint? {
        val cleanMask = solidMask.clone()
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        return try {
            Imgproc.findContours(
                cleanMask,
                contours,
                hierarchy,
                Imgproc.RETR_EXTERNAL,
                Imgproc.CHAIN_APPROX_SIMPLE,
            )
            val best = contours.maxByOrNull { Imgproc.contourArea(it) } ?: return null
            MatOfPoint(*best.toArray())
        } finally {
            contours.forEach { runCatching { it.release() } }
            hierarchy.release()
            cleanMask.release()
        }
    }

    private fun buildObject(
        solid: Mat,
        clean: MatOfPoint,
        scene: SceneContext,
    ): ObjectGeometry? {
        val cfg = AppConfig.Analysis
        val s = scene.s
        val clean2f = MatOfPoint2f(*clean.toArray())
        try {
            val grossAreaPx = Imgproc.contourArea(clean)
            if (grossAreaPx <= 0.0) return null
            val perimeterPx = Imgproc.arcLength(clean2f, true)
            if (perimeterPx < 12.0) return null

            val rotated = Imgproc.minAreaRect(clean2f)
            val longPx = max(rotated.size.width, rotated.size.height)
            val shortPx = min(rotated.size.width, rotated.size.height)
            if (longPx < 4.0 || shortPx < 2.0) return null

            val holes = detectHoles(solid, grossAreaPx, scene)
            val holeAreaPx = holes.sumOf { it.areaMm2 * s * s }
            val netAreaPx = grossAreaPx - holeAreaPx
            if (netAreaPx <= 0.0) return null

            val lengthMm = longPx / s
            val widthMm = shortPx / s
            val perimeterMm = perimeterPx / s
            val netAreaMm2 = netAreaPx / (s * s)
            if (netAreaMm2 < cfg.MIN_OBJECT_AREA_MM2) return null
            val netAreaCm2 = netAreaMm2 / 100.0

            val deltaL = ErrorEstimator.linearError(
                lengthMm,
                scene.calibration.nativePixelsPerMm,
                scene.calibration.mode.widthMm,
                scene.calibration.epsilonCal,
            )
            val deltaW = ErrorEstimator.linearError(
                widthMm,
                scene.calibration.nativePixelsPerMm,
                scene.calibration.mode.widthMm,
                scene.calibration.epsilonCal,
            )
            val deltaP = ErrorEstimator.linearError(
                perimeterMm,
                scene.calibration.nativePixelsPerMm,
                scene.calibration.mode.widthMm,
                scene.calibration.epsilonCal,
            )
            val deltaAvg = (deltaL + deltaW) / 2.0
            val deltaA = ErrorEstimator.areaErrorCm2(
                perimeterMm,
                deltaAvg,
                netAreaMm2,
                scene.calibration.epsilonCal,
            )
            val quality = ErrorEstimator.quality(deltaL, scene.calibration.mode)
            val recommendation = ErrorEstimator.recommendation(
                quality,
                scene.calibration.mode,
                lengthMm,
            )

            val simplifiedPx = simplify(clean2f, cfg.SVG_EPSILON_MM * s)
            val outerMm = simplifiedPx.map { Pt(it.x / s, it.y / s) }
            val fullPtsMm = clean.toArray().map { Pt(it.x / s, it.y / s) }
            val (minPt, maxPt) = Geometry.bounds(fullPtsMm)
            val moments = Imgproc.moments(clean)
            val centroid = if (moments.m00 > 1e-6) {
                Pt(moments.m10 / (moments.m00 * s), moments.m01 / (moments.m00 * s))
            } else {
                Geometry.polygonCentroid(outerMm)
            }

            return ObjectGeometry(
                index = 0,
                lengthMm = lengthMm,
                widthMm = widthMm,
                lengthErrorMm = deltaL,
                widthErrorMm = deltaW,
                perimeterMm = perimeterMm,
                perimeterErrorMm = deltaP,
                areaNetMm2 = netAreaMm2,
                areaNetCm2 = netAreaCm2,
                areaErrorCm2 = deltaA,
                holes = holes,
                holeSpacings = holeSpacings(holes, scene.calibration),
                centroid = centroid,
                boundsMin = minPt,
                boundsMax = maxPt,
                outerContour = outerMm,
                quality = quality,
                qualityRecommendation = recommendation,
            )
        } finally {
            clean2f.release()
        }
    }

    // ------------------------------------------------------------------
    // Strict hole filtering
    // ------------------------------------------------------------------

    private fun detectHoles(
        solid: Mat,
        grossAreaPx: Double,
        scene: SceneContext,
    ): List<HoleGeometry> {
        val cfg = AppConfig.Analysis
        val s = scene.s
        val objectMeanContrast = Core.mean(scene.colorDiff, solid).`val`[0]
        if (objectMeanContrast < cfg.MIN_OBJECT_CONTRAST) {
            // The object is nearly the same colour as the mat. Background-like
            // regions inside it cannot be distinguished from printed white or
            // low-contrast surface areas, so reporting holes would be unsafe.
            return emptyList()
        }

        val eroded = Mat()
        val holesSeed = Mat()
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        val holes = ArrayList<HoleGeometry>()
        try {
            Imgproc.erode(solid, eroded, scene.kernel)
            Core.bitwise_and(scene.backgroundLike, eroded, holesSeed)
            Imgproc.findContours(
                holesSeed,
                contours,
                hierarchy,
                Imgproc.RETR_EXTERNAL,
                Imgproc.CHAIN_APPROX_SIMPLE,
            )

            var holeNumber = 0
            for (contour in contours) {
                val areaPx = Imgproc.contourArea(contour)
                if (areaPx < cfg.HOLE_MIN_AREA_MM2 * s * s) continue
                if (areaPx > grossAreaPx * cfg.HOLE_MAX_OBJECT_FRACTION) continue

                val contour2f = MatOfPoint2f(*contour.toArray())
                try {
                    val perimeterPx = Imgproc.arcLength(contour2f, true)
                    if (perimeterPx < 1e-6) continue
                    val circularity = 4.0 * Math.PI * areaPx / (perimeterPx * perimeterPx)
                    if (circularity < cfg.HOLE_MIN_CIRCULARITY) continue

                    val moments = Imgproc.moments(contour)
                    if (moments.m00 < 1e-6) continue
                    val cx = moments.m10 / (moments.m00 * s)
                    val cy = moments.m01 / (moments.m00 * s)
                    val areaMm2 = areaPx / (s * s)
                    val diameter = 2.0 * sqrt(areaMm2 / Math.PI)
                    val simplified = simplify(contour2f, cfg.SVG_EPSILON_MM * s)
                        .map { Pt(it.x / s, it.y / s) }
                    if (simplified.size < 3) continue

                    holes += HoleGeometry(
                        index = holeNumber++,
                        centroid = Pt(cx, cy),
                        diameterMm = diameter,
                        areaMm2 = areaMm2,
                        diameterErrorMm = ErrorEstimator.diameterError(
                            diameter,
                            scene.calibration.nativePixelsPerMm,
                            scene.calibration.mode.widthMm,
                            scene.calibration.epsilonCal,
                        ),
                        contour = simplified,
                    )
                } finally {
                    contour2f.release()
                }
            }
        } finally {
            eroded.release()
            holesSeed.release()
            contours.forEach { runCatching { it.release() } }
            hierarchy.release()
        }
        return holes
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun holeSpacings(
        holes: List<HoleGeometry>,
        calibration: CalibrationResult,
    ): List<HoleSpacing> {
        val spacings = ArrayList<HoleSpacing>()
        for (first in holes.indices) {
            for (second in first + 1 until holes.size) {
                val distance = Geometry.distance(
                    holes[first].centroid,
                    holes[second].centroid,
                )
                spacings += HoleSpacing(
                    firstIndex = holes[first].index,
                    secondIndex = holes[second].index,
                    distanceMm = distance,
                    errorMm = ErrorEstimator.distanceError(
                        distance,
                        calibration.nativePixelsPerMm,
                        calibration.mode.widthMm,
                        calibration.epsilonCal,
                    ),
                )
            }
        }
        return spacings
    }

    private fun simplify(contour2f: MatOfPoint2f, epsilonPx: Double): List<Pt> {
        val approx = MatOfPoint2f()
        return try {
            Imgproc.approxPolyDP(contour2f, approx, epsilonPx, true)
            val points = approx.toArray().map { Pt(it.x, it.y) }
            if (points.size >= 3) points else contour2f.toArray().map { Pt(it.x, it.y) }
        } finally {
            approx.release()
        }
    }

    private fun inflateReferenceRect(
        rect: PixelRect,
        marginPx: Int,
        width: Int,
        height: Int,
    ): PixelRect = PixelRect(
        left = (rect.left - marginPx).coerceIn(0, width - 1),
        top = (rect.top - marginPx).coerceIn(0, height - 1),
        right = (rect.right + marginPx).coerceIn(1, width),
        bottom = (rect.bottom + marginPx).coerceIn(1, height),
    )

    private fun eraseReference(mask: Mat, rect: PixelRect) {
        if (rect.isEmpty) return
        Imgproc.rectangle(
            mask,
            Point(rect.left.toDouble(), rect.top.toDouble()),
            Point(rect.right.toDouble(), rect.bottom.toDouble()),
            Scalar(0.0),
            -1,
        )
    }

    private fun touchesBorder(rect: Rect, width: Int, height: Int): Boolean {
        val margin = AppConfig.Analysis.BORDER_MARGIN_PX
        return rect.x <= margin ||
            rect.y <= margin ||
            rect.x + rect.width >= width - margin ||
            rect.y + rect.height >= height - margin
    }

    private fun overlapsReference(rect: Rect, reference: PixelRect): Boolean {
        val centerX = rect.x + rect.width / 2.0
        val centerY = rect.y + rect.height / 2.0
        return centerX in reference.left.toDouble()..reference.right.toDouble() &&
            centerY in reference.top.toDouble()..reference.bottom.toDouble()
    }

    private fun deduplicate(candidates: List<ObjectGeometry>): List<ObjectGeometry> {
        // Preserve insertion order: the colour-mask candidates are generated
        // before the edge-ring candidates, so a duplicate on the same object
        // keeps the more directly measured colour silhouette.
        val kept = ArrayList<ObjectGeometry>()
        for (candidate in candidates) {
            val duplicate = kept.any { existing ->
                val centroidGap = Geometry.distance(existing.centroid, candidate.centroid)
                val areaRatio = abs(existing.areaNetMm2 - candidate.areaNetMm2) /
                    max(existing.areaNetMm2, candidate.areaNetMm2)
                centroidGap < 2.0 && areaRatio < 0.25
            }
            if (!duplicate) kept += candidate
        }
        return kept
    }
}
