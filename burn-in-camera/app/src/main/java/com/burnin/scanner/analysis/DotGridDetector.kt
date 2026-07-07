package com.burnin.scanner.analysis

import java.util.ArrayDeque

/**
 * dotgrid 패턴의 내부 기준점을 검출해 4모서리 호모그래피를 다점 호모그래피로 보강한다.
 * 렌즈의 비선형 왜곡은 residual로 남기고, 평균적인 투영/회전/스케일 오차는 여기서 흡수한다.
 */
object DotGridDetector {

    data class Result(
        val homography: Homography,
        val matchedPoints: Int,
        val rmsResidualPx: Float,
        val maxResidualPx: Float,
    )

    private data class Blob(
        val x: Float,
        val y: Float,
        val area: Int,
        val width: Int,
        val height: Int,
    )

    fun refineHomography(
        img: GrayImage,
        initial: Homography,
        screenW: Int,
        screenH: Int,
        cells: Int = 12,
    ): Result? {
        val blobs = detectBlobs(img)
        if (blobs.size < 16) return null

        val spacing = estimateProjectedSpacing(initial, screenW, screenH, cells)
        val tolerance = (spacing * 0.45f).coerceAtLeast(6f)
        val tolerance2 = tolerance * tolerance
        val used = BooleanArray(blobs.size)
        val src = ArrayList<Vec2>()
        val dst = ArrayList<Vec2>()
        val out = DoubleArray(2)

        for (row in 1 until cells) {
            for (col in 1 until cells) {
                val u = screenW * col / cells.toFloat()
                val v = screenH * row / cells.toFloat()
                initial.map(u.toDouble(), v.toDouble(), out)
                var best = -1
                var bestD2 = Float.MAX_VALUE
                for (i in blobs.indices) {
                    if (used[i]) continue
                    val dx = blobs[i].x - out[0].toFloat()
                    val dy = blobs[i].y - out[1].toFloat()
                    val d2 = dx * dx + dy * dy
                    if (d2 < bestD2) {
                        bestD2 = d2
                        best = i
                    }
                }
                if (best >= 0 && bestD2 <= tolerance2) {
                    used[best] = true
                    src += Vec2(u, v)
                    dst += Vec2(blobs[best].x, blobs[best].y)
                }
            }
        }

        if (src.size < 16) return null
        val refined = Homography.fromPointPairs(src, dst) ?: return null
        var sumSq = 0.0
        var maxErr = 0f
        for (i in src.indices) {
            refined.map(src[i].x.toDouble(), src[i].y.toDouble(), out)
            val dx = out[0].toFloat() - dst[i].x
            val dy = out[1].toFloat() - dst[i].y
            val err = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
            sumSq += (err * err).toDouble()
            maxErr = maxOf(maxErr, err)
        }
        return Result(
            homography = refined,
            matchedPoints = src.size,
            rmsResidualPx = Math.sqrt(sumSq / src.size).toFloat(),
            maxResidualPx = maxErr,
        )
    }

    private fun detectBlobs(img: GrayImage): List<Blob> {
        var maxV = 1e-6f
        for (v in img.data) if (v > maxV) maxV = v
        if (maxV < 0.01f) return emptyList()
        val threshold = maxV * 0.35f
        val visited = BooleanArray(img.data.size)
        val blobs = ArrayList<Blob>()
        val queue = ArrayDeque<Int>()
        val total = img.data.size
        val maxArea = (total * 0.02f).toInt().coerceAtLeast(16)

        for (start in img.data.indices) {
            if (visited[start] || img.data[start] <= threshold) continue
            visited[start] = true
            queue.clear()
            queue.add(start)
            var area = 0
            var sumX = 0.0
            var sumY = 0.0
            var minX = img.w
            var maxX = 0
            var minY = img.h
            var maxY = 0
            while (!queue.isEmpty()) {
                val p = queue.removeFirst()
                val x = p % img.w
                val y = p / img.w
                area++
                sumX += x.toDouble()
                sumY += y.toDouble()
                minX = minOf(minX, x)
                maxX = maxOf(maxX, x)
                minY = minOf(minY, y)
                maxY = maxOf(maxY, y)
                addNeighbor(img, visited, queue, x - 1, y, threshold)
                addNeighbor(img, visited, queue, x + 1, y, threshold)
                addNeighbor(img, visited, queue, x, y - 1, threshold)
                addNeighbor(img, visited, queue, x, y + 1, threshold)
            }
            if (area in 3..maxArea) {
                val width = maxX - minX + 1
                val height = maxY - minY + 1
                val aspect = width.toFloat() / height.coerceAtLeast(1)
                if (aspect in 0.35f..2.85f) {
                    blobs += Blob(
                        x = (sumX / area).toFloat(),
                        y = (sumY / area).toFloat(),
                        area = area,
                        width = width,
                        height = height,
                    )
                }
            }
        }
        return blobs
    }

    private fun addNeighbor(
        img: GrayImage,
        visited: BooleanArray,
        queue: ArrayDeque<Int>,
        x: Int,
        y: Int,
        threshold: Float,
    ) {
        if (x !in 0 until img.w || y !in 0 until img.h) return
        val i = y * img.w + x
        if (visited[i] || img.data[i] <= threshold) return
        visited[i] = true
        queue.add(i)
    }

    private fun estimateProjectedSpacing(h: Homography, screenW: Int, screenH: Int, cells: Int): Float {
        val c = DoubleArray(2)
        val x = DoubleArray(2)
        val y = DoubleArray(2)
        val u = screenW * 0.5
        val v = screenH * 0.5
        h.map(u, v, c)
        h.map(u + screenW / cells.toDouble(), v, x)
        h.map(u, v + screenH / cells.toDouble(), y)
        val dx = Math.hypot(x[0] - c[0], x[1] - c[1])
        val dy = Math.hypot(y[0] - c[0], y[1] - c[1])
        return minOf(dx, dy).toFloat().coerceAtLeast(1f)
    }
}
