package com.realmeasure.app.cv

import com.realmeasure.app.config.AppConfig
import com.realmeasure.app.model.Pt
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Guide-aware card detector used by both the live scanner and the calibration
 * pass.
 *
 * Instead of relying on a single closed contour (which falls apart on a white
 * ID card lying on a white desk), it looks for the four strongest edge lines
 * in narrow bands around the guide. The peaks give the card sides, which are
 * turned into an axis-aligned quadrilateral. It intentionally rejects cards
 * that are much smaller than the guide, have the wrong ID-1 aspect ratio or
 * present only one or two visible edges.
 *
 * The search crop is a grayscale [Mat] that already contains the card guide
 * plus margin. Guide coordinates passed to [detect] are relative to that crop.
 */
object CardEdgeDetector {

    data class Detection(
        /** Corners relative to the search crop, ordered TL -> TR -> BR -> BL. */
        val quad: List<Pt>,
        val score: Double,
        val widthPx: Double,
        val heightPx: Double,
        val minEdgeStrength: Double,
    )

    /**
     * @param search grayscale crop containing the guide plus margin.
     * @param guideLeft guide left in [search] pixels.
     * @param guideTop guide top in [search] pixels.
     * @param guideWidth guide width in pixels.
     * @param guideHeight guide height in pixels.
     */
    fun detect(
        search: Mat,
        guideLeft: Double,
        guideTop: Double,
        guideWidth: Double,
        guideHeight: Double,
    ): Detection? = withMatPool {
        val cfg = AppConfig.CardCv
        if (search.cols() < 60 || search.rows() < 40) return null

        val blurred = Mat().tracked()
        Imgproc.GaussianBlur(
            search,
            blurred,
            Size(cfg.GAUSSIAN_KERNEL.toDouble(), cfg.GAUSSIAN_KERNEL.toDouble()),
            0.0,
        )

        val enhanced = Mat().tracked()
        val clahe = Imgproc.createCLAHE(
            cfg.CLAHE_CLIP_LIMIT,
            Size(cfg.CLAHE_TILE_GRID, cfg.CLAHE_TILE_GRID),
        )
        clahe.apply(blurred, enhanced)
        clahe.collectGarbage()

        val edges = Mat().tracked()
        Imgproc.Canny(enhanced, edges, cfg.CANNY_LOW, cfg.CANNY_HIGH)

        val width = edges.cols()
        val height = edges.rows()
        val pixels = ByteArray(width * height)
        edges.get(0, 0, pixels)

        val bandX = guideWidth * 0.20
        val bandY = guideHeight * 0.20
        val innerX = guideWidth * 0.08
        val innerY = guideHeight * 0.08

        val top = rowPeak(
            pixels, width, height,
            x0 = guideLeft + innerX,
            x1 = guideLeft + guideWidth - innerX,
            y0 = guideTop - bandY,
            y1 = guideTop + bandY,
        )
        val bottom = rowPeak(
            pixels, width, height,
            x0 = guideLeft + innerX,
            x1 = guideLeft + guideWidth - innerX,
            y0 = guideTop + guideHeight - bandY,
            y1 = guideTop + guideHeight + bandY,
        )
        val left = columnPeak(
            pixels, width, height,
            x0 = guideLeft - bandX,
            x1 = guideLeft + bandX,
            y0 = guideTop + innerY,
            y1 = guideTop + guideHeight - innerY,
        )
        val right = columnPeak(
            pixels, width, height,
            x0 = guideLeft + guideWidth - bandX,
            x1 = guideLeft + guideWidth + bandX,
            y0 = guideTop + innerY,
            y1 = guideTop + guideHeight - innerY,
        )

        val cardWidth = (right.position - left.position).toDouble()
        val cardHeight = (bottom.position - top.position).toDouble()
        if (cardWidth < 24.0 || cardHeight < 16.0) return null

        val aspect = max(cardWidth, cardHeight) / min(cardWidth, cardHeight)
        val aspectError = abs(aspect - AppConfig.CARD_ASPECT) / AppConfig.CARD_ASPECT
        if (aspectError > cfg.MAX_ASPECT_ERROR * 0.82) return null

        val coverageX = cardWidth / guideWidth
        val coverageY = cardHeight / guideHeight
        if (coverageX !in 0.50..1.15 || coverageY !in 0.50..1.15) return null

        val minStrength = minOf(
            top.strength,
            bottom.strength,
            left.strength,
            right.strength,
        )
        if (minStrength < MIN_EDGE_STRENGTH) return null

        val strengthScore = ((minStrength - MIN_EDGE_STRENGTH) / 0.45).coerceIn(0.0, 1.0)
        val aspectScore = 1.0 - (aspectError / (cfg.MAX_ASPECT_ERROR * 0.82)).coerceIn(0.0, 1.0)
        val coverageError = abs(coverageX - 1.0) + abs(coverageY - 1.0)
        val coverageScore = 1.0 - (coverageError / 0.55).coerceIn(0.0, 1.0)
        val score = 0.45 * strengthScore + 0.35 * aspectScore + 0.20 * coverageScore

        Detection(
            quad = listOf(
                Pt(left.position.toDouble(), top.position.toDouble()),
                Pt(right.position.toDouble(), top.position.toDouble()),
                Pt(right.position.toDouble(), bottom.position.toDouble()),
                Pt(left.position.toDouble(), bottom.position.toDouble()),
            ),
            score = score.coerceIn(0.0, 1.0),
            widthPx = cardWidth,
            heightPx = cardHeight,
            minEdgeStrength = minStrength,
        )
    }

    private const val MIN_EDGE_STRENGTH = 0.22

    private data class Peak(val position: Int, val strength: Double)

    /**
     * Row with the most edge pixels inside [x0]..[x1]; [strength] is the
     * fraction of that span covered by edge pixels.
     */
    private fun rowPeak(
        pixels: ByteArray,
        width: Int,
        height: Int,
        x0: Double,
        x1: Double,
        y0: Double,
        y1: Double,
    ): Peak {
        val left = x0.toInt().coerceIn(0, width - 1)
        val right = x1.toInt().coerceIn(left + 1, width)
        val top = y0.toInt().coerceIn(0, height - 1)
        val bottom = y1.toInt().coerceIn(top + 1, height)
        val span = (right - left).coerceAtLeast(1)

        var bestRow = top
        var bestCount = 0
        for (y in top until bottom) {
            val rowOffset = y * width
            var count = 0
            for (x in left until right) {
                if (pixels[rowOffset + x].toInt() != 0) count++
            }
            if (count > bestCount) {
                bestCount = count
                bestRow = y
            }
        }
        return Peak(bestRow, bestCount.toDouble() / span)
    }

    /** Column analogue of [rowPeak]. */
    private fun columnPeak(
        pixels: ByteArray,
        width: Int,
        height: Int,
        x0: Double,
        x1: Double,
        y0: Double,
        y1: Double,
    ): Peak {
        val left = x0.toInt().coerceIn(0, width - 1)
        val right = x1.toInt().coerceIn(left + 1, width)
        val top = y0.toInt().coerceIn(0, height - 1)
        val bottom = y1.toInt().coerceIn(top + 1, height)
        val span = (bottom - top).coerceAtLeast(1)

        var bestColumn = left
        var bestCount = 0
        for (x in left until right) {
            var count = 0
            for (y in top until bottom) {
                if (pixels[y * width + x].toInt() != 0) count++
            }
            if (count > bestCount) {
                bestCount = count
                bestColumn = x
            }
        }
        return Peak(bestColumn, bestCount.toDouble() / span)
    }
}
