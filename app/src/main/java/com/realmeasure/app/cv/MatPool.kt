package com.realmeasure.app.cv

import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import com.realmeasure.app.model.Pt

/**
 * Deterministic Mat lifetime management.
 *
 * OpenCV Java allocates native memory that the GC does not track eagerly.
 * Every Mat created inside a [withMatPool] block is released exactly once
 * when the block exits - even when the analysis throws.
 */
internal class MatPool : AutoCloseable {

    private val trackedMats = mutableListOf<Mat>()

    /** Register [this] Mat for release when the pool closes. */
    fun <T : Mat> T.tracked(): T {
        trackedMats.add(this)
        return this
    }

    /** Crop [rect] out of [source] into an owned (non-view) Mat. */
    fun crop(source: Mat, rect: Rect): Mat {
        val view = source.submat(rect)
        val owned = view.clone()
        view.release()
        return owned.tracked()
    }

    override fun close() {
        trackedMats.forEach { mat ->
            runCatching { mat.release() }
        }
        trackedMats.clear()
    }
}

/**
 * Runs [block] with a [MatPool] as the receiver. Every `Mat().tracked()`
 * created inside is released deterministically when the block exits.
 */
internal inline fun <R> withMatPool(block: MatPool.() -> R): R {
    val pool = MatPool()
    try {
        return pool.block()
    } finally {
        runCatching { pool.close() }
    }
}

/** Convert an OpenCV [Point] to the pure [Pt] model type. */
internal fun Point.toPt(): Pt = Pt(x, y)

/** Convert the pure [Pt] model type to an OpenCV [Point]. */
internal fun Pt.toPoint(): Point = Point(x, y)
