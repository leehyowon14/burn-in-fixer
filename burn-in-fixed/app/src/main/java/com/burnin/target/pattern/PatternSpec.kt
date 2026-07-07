package com.burnin.target.pattern

/**
 * 테스트 패턴 정의. 이름 규칙:
 *  - "black", "white70", "white85", "gray30"~"gray85", "red70", "green70", "blue70" : 단색
 *  - "marker"   : 좌표 정합용 마커 (모서리 사각형 + 방향 마커 + 중앙 십자)
 *  - "grid"     : 격자
 *  - "checker"  : 체커보드 (렌즈 왜곡 캘리브레이션용)
 *  - "dotgrid"  : 도트 그리드 (렌즈 왜곡 캘리브레이션용)
 */
data class PatternSpec(
    val name: String,
    val kind: Kind,
    val color: Int = COLOR_BLACK,
    val cells: Int = 8,
) {
    enum class Kind { SOLID, MARKER, GRID, CHECKER, DOTGRID }

    companion object {
        const val COLOR_BLACK = 0xFF000000.toInt()

        private fun rgb(r: Int, g: Int, b: Int): Int =
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b

        fun solid(name: String, color: Int) = PatternSpec(name, Kind.SOLID, color)

        val BLACK = solid("black", COLOR_BLACK)

        private val solidRegex = Regex("^(black|white|gray|grey|red|green|blue)(\\d{1,3})?$")

        /** 패턴 이름 문자열을 해석한다. 알 수 없는 이름이면 null. */
        fun parse(raw: String): PatternSpec? {
            val name = raw.trim().lowercase()
            solidRegex.matchEntire(name)?.let { m ->
                val base = m.groupValues[1]
                val pct = (m.groupValues[2].toIntOrNull() ?: 100).coerceIn(0, 100)
                val v = Math.round(255f * pct / 100f)
                val color = when (base) {
                    "black" -> COLOR_BLACK
                    "white", "gray", "grey" -> rgb(v, v, v)
                    "red" -> rgb(v, 0, 0)
                    "green" -> rgb(0, v, 0)
                    "blue" -> rgb(0, 0, v)
                    else -> return null
                }
                return solid(name, if (base == "black") COLOR_BLACK else color)
            }
            return when (name) {
                "marker" -> PatternSpec(name, Kind.MARKER)
                "grid" -> PatternSpec(name, Kind.GRID, cells = 12)
                "checker", "checkerboard" -> PatternSpec(name, Kind.CHECKER, cells = 10)
                "dotgrid", "dots" -> PatternSpec(name, Kind.DOTGRID, cells = 12)
                else -> null
            }
        }
    }
}
