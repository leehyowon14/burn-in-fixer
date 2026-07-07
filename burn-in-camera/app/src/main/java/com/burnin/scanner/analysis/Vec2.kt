package com.burnin.scanner.analysis

/**
 * android.graphics.PointF 대신 쓰는 순수 2D 점.
 * 분석 모듈을 JVM 단위 테스트 가능하게 유지하기 위해 Android 클래스를 쓰지 않는다.
 */
class Vec2(var x: Float = 0f, var y: Float = 0f) {
    fun set(nx: Float, ny: Float) {
        x = nx
        y = ny
    }

    override fun toString() = "(${x.toInt()},${y.toInt()})"
}
