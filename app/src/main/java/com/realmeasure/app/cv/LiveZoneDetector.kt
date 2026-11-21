package com.realmeasure.app.cv

import com.realmeasure.app.config.AppConfig
import com.realmeasure.app.model.Pt
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Real-time two-zone inspection for hands-free scanning.
 *
 * Runs on a downscaled copy of each CameraX frame (~640 px wide) and performs
 * only cheap operations: one grey conversion, a card contour search in the
 * left guide and a single-object contour search in the right guide.
 * The expensive perspective warp + measurement runs only once, after the
 * stability timer locks on.
 */
object LiveZoneDetector {

    /** Left-zone result. */
    data class CardCheck(
        val detected: Boolean,
        val confidence: Double,
        /** Card width in detected pixels / 85.6 mm; 0 when unknown. */
        val pixelsPerMm: Double,
    )

    /**
     * Right-zone result. [touchesBorder] is true when a contour exists but
     * crosses the cyan guide (or the search crop), which the UI turns into
     * "Keep the hardware fully inside the right box".
     */
    data class PartCheck(
        val detected: Boolean,
        val touchesBorder: Boolean,
        val confidence: Double,
    )

    data class FrameInspection(
        val card: CardCheck,
        val part: PartCheck,
        val meanBrightness: Double,
    )

    private data class GuideRoi(val mat: Mat, val rect: Rect)

    private data class PartCandidate(
        val score: Double,
        val touchesBorder: Boolean,
    )

    /** Inspect one upright, stage-cropped frame. */
    fun inspect(frame: Bitmap): FrameInspection = withMatPool {
        val targetWidth = AppConfig.Live.DETECT_WIDTH_PX
        val scaled = if (frame.width > targetWidth) {
            val height = (frame.height.toDouble() * targetWidth / frame.width)
                .toInt()
                .coerceAtLeast(1)
            Bitmap.createScaledBitmap(frame, targetWidth, height, true)
        } else {
            frame
        }

        try {
            val rgba = Mat().tracked()
            Utils.bitmapToMat(scaled, rgba)
            val gray = Mat().tracked()
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)

            val brightness = Core.mean(gray).`val`[0]
            val card = detectCard(gray)
            FrameInspection(
                card = card,
                part = detectPart(
                    gray,
                    pixelsPerMm = if (card.detected) card.pixelsPerMm else null,
                ),
                meanBrightness = brightness,
            )
        } finally {
            if (scaled !== frame) scaled.recycle()
        }
    }

    // ------------------------------------------------------------------
    // Left zone - ISO/IEC 7810 ID-1 card
    // ------------------------------------------------------------------

    private fun MatPool.detectCard(gray: Mat): CardCheck {
        val guides = AppConfig.Guides
        val roiData = cropGuide(
            gray,
            guides.CARD_X,
            guides.CARD_Y,
            guides.CARD_W,
            guides.CARD_H,
            guides.CARD_SEARCH_PADDING,
        )
        val roi = roiData.mat
        if (roi.cols() < 60 || roi.rows() < 40) return CardCheck(false, 0.0, 0.0)

        val guideLeft = guides.CARD_X * gray.cols() - roiData.rect.x
        val guideTop = guides.CARD_Y * gray.rows() - roiData.rect.y
        val guideWidth = guides.CARD_W * gray.cols()
        val guideHeight = guides.CARD_H * gray.rows()

        val detection = CardEdgeDetector.detect(
            search = roi,
            guideLeft = guideLeft,
            guideTop = guideTop,
            guideWidth = guideWidth,
            guideHeight = guideHeight,
        )
        return CardCheck(
            detected = detection != null,
            confidence = detection?.score ?: 0.0,
            pixelsPerMm = detection?.let {
                it.widthPx / AppConfig.CARD_WIDTH_MM
            } ?: 0.0,
        )
    }

    // ------------------------------------------------------------------
    // Right zone - fastener
    // ------------------------------------------------------------------

    private fun MatPool.detectPart(gray: Mat, pixelsPerMm: Double?): PartCheck {
        val guides = AppConfig.Guides
        val cfg = AppConfig.Live
        val roiData = cropGuide(
            gray,
            guides.TARGET_X,
            guides.TARGET_Y,
            guides.TARGET_W,
            guides.TARGET_H,
            guides.TARGET_SEARCH_PADDING,
        )
        val roi = roiData.mat
        if (roi.cols() < 40 || roi.rows() < 40) return PartCheck(false, false, 0.0)

        // Guide rectangle expressed inside the padded search crop.
        val guideLeft = guides.TARGET_X * gray.cols() - roiData.rect.x
        val guideTop = guides.TARGET_Y * gray.rows() - roiData.rect.y
        val guideRight = (guides.TARGET_X + guides.TARGET_W) * gray.cols() - roiData.rect.x
        val guideBottom = (guides.TARGET_Y + guides.TARGET_H) * gray.rows() - roiData.rect.y
        val margin = guides.TARGET_W * gray.cols() * cfg.PART_EDGE_MARGIN_RATIO

        val blurred = Mat().tracked()
        Imgproc.GaussianBlur(roi, blurred, Size(5.0, 5.0), 0.0)
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0)).tracked()

        val adaptive = Mat().tracked()
        Imgproc.adaptiveThreshold(
            blurred, adaptive, 255.0,
            Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY_INV,
            AppConfig.FastenerCv.ADAPTIVE_BLOCK, AppConfig.FastenerCv.ADAPTIVE_C,
        )
        val adaptiveClosed = Mat().tracked()
        Imgproc.morphologyEx(adaptive, adaptiveClosed, Imgproc.MORPH_CLOSE, kernel)

        val edges = Mat().tracked()
        Imgproc.Canny(blurred, edges, AppConfig.FastenerCv.CANNY_LOW, AppConfig.FastenerCv.CANNY_HIGH)
        val edgesClosed = Mat().tracked()
        Imgproc.morphologyEx(edges, edgesClosed, Imgproc.MORPH_CLOSE, kernel)

        val checks = listOf(
            scorePartMask(adaptiveClosed, guideLeft, guideTop, guideRight, guideBottom, margin, pixelsPerMm),
            scorePartMask(edgesClosed, guideLeft, guideTop, guideRight, guideBottom, margin, pixelsPerMm),
        )
        val best = checks.maxByOrNull { it.score } ?: PartCandidate(0.0, false)
        val detected = best.score >= 0.45 && !best.touchesBorder
        return PartCheck(detected, best.touchesBorder, best.score)
    }

    private fun MatPool.scorePartMask(
        mask: Mat,
        guideLeft: Double,
        guideTop: Double,
        guideRight: Double,
        guideBottom: Double,
        margin: Double,
        pixelsPerMm: Double?,
    ): PartCandidate {
        val cfg = AppConfig.Live
        val width = mask.cols()
        val height = mask.rows()
        val roiArea = width.toDouble() * height
        val center = Pt(width / 2.0, height / 2.0)
        val halfDiagonal = hypot(width.toDouble(), height.toDouble()) / 2.0

        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat().tracked()
        var best = PartCandidate(0.0, false)

        try {
            Imgproc.findContours(
                mask, contours, hierarchy,
                Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE,
            )

            for (contour in contours) {
                val area = Imgproc.contourArea(contour)
                if (area / roiArea < cfg.PART_MIN_AREA_RATIO) continue

                val rect = Imgproc.boundingRect(contour)
                if (rect.width < 6 || rect.height < 6) continue

                // Fasteners are elongated; a round blob or a short highlight is
                // not worth auto-capturing (it would only fail measurement).
                val contour2f = MatOfPoint2f(*contour.toArray())
                val (longSide, shortSide) = try {
                    val rotated = Imgproc.minAreaRect(contour2f)
                    max(rotated.size.width, rotated.size.height) to
                        min(rotated.size.width, rotated.size.height)
                } finally {
                    contour2f.release()
                }
                if (shortSide < AppConfig.FastenerCv.MIN_SHORT_SIDE_PX) continue
                if (longSide / shortSide < AppConfig.FastenerCv.MIN_ELONGATION) continue

                // Reject paper edges, hands and table borders: they are either
                // far too thick relative to the guide or absurdly elongated.
                val guideWidth = (guideRight - guideLeft).coerceAtLeast(1.0)
                if (shortSide > guideWidth * 0.65) continue
                if (longSide / shortSide > 20.0) continue
                if (area / roiArea > 0.35) continue

                // Metric sanity check against the card scale: a screw, bolt or
                // hex key is never thinner than ~1.5 mm on the long axis.
                if (pixelsPerMm != null && pixelsPerMm > 0.25) {
                    val shortMm = shortSide / pixelsPerMm
                    val longMm = longSide / pixelsPerMm
                    if (shortMm < cfg.MIN_FASTENER_DIAMETER_MM) continue
                    if (shortMm > AppConfig.FastenerCv.MAX_DIAMETER_MM) continue
                    if (longMm < AppConfig.FastenerCv.MIN_LENGTH_MM) continue
                    if (longMm > AppConfig.FastenerCv.MAX_LENGTH_MM) continue
                }

                val fill = (area / (rect.width.toDouble() * rect.height)).coerceIn(0.0, 1.0)
                if (fill < cfg.PART_MIN_FILL_RATIO) continue

                val clipped = rect.x <= 1 || rect.y <= 1 ||
                    rect.x + rect.width >= width - 1 || rect.y + rect.height >= height - 1
                val outsideGuide =
                    rect.x < guideLeft - margin ||
                        rect.y < guideTop - margin ||
                        rect.x + rect.width > guideRight + margin ||
                        rect.y + rect.height > guideBottom + margin
                val touchesBorder = clipped || outsideGuide

                val centerPoint = Pt(
                    rect.x + rect.width / 2.0,
                    rect.y + rect.height / 2.0,
                )
                val centerDistance =
                    Geometry.distance(centerPoint, center) / halfDiagonal
                // Keep auto-capture centred on the guide; printed text and
                // paper edges along the frame border are not hardware.
                if (centerDistance > 0.65) continue
                val areaScore = (area / roiArea / 0.25).coerceIn(0.0, 1.0)
                val fillScore = fill
                val centerScore = 1.0 - centerDistance.coerceIn(0.0, 1.0)
                val score = 0.40 * areaScore + 0.40 * fillScore + 0.20 * centerScore

                if (score > best.score) {
                    best = PartCandidate(score, touchesBorder)
                }
            }
        } finally {
            contours.forEach { mat -> runCatching { mat.release() } }
        }
        return best
    }

    // ------------------------------------------------------------------
    // Cropping helper
    // ------------------------------------------------------------------

    private fun MatPool.cropGuide(
        source: Mat,
        x: Double,
        y: Double,
        w: Double,
        h: Double,
        padding: Double,
    ): GuideRoi {
        val padX = w * padding
        val padY = h * padding
        val left = floor(max(0.0, (x - padX) * source.cols())).toInt()
        val top = floor(max(0.0, (y - padY) * source.rows())).toInt()
        val right = ceil(min(1.0, x + w + padX) * source.cols()).toInt()
        val bottom = ceil(min(1.0, y + h + padY) * source.rows()).toInt()
        val rect = Rect(left, top, max(8, right - left), max(8, bottom - top))
        return GuideRoi(crop(source, rect), rect)
    }
}
