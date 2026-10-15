package com.neoprars.voice.app

/**
 * Dónde va el disco del botón flotante. Cuentas puras (píxeles de pantalla, sin clases de Android)
 * para poder probarlas: límites de pantalla, borde al que se pega y que nunca quede detrás del teclado.
 */
object ButtonPlacement {

    enum class Side { LEFT, RIGHT }

    /** Posición elegida por la persona: borde horizontal y borde superior del disco. */
    data class Position(val side: Side, val y: Int)

    /** X del borde izquierdo del disco cuando está pegado a [side]. */
    fun discX(side: Side, screenWidth: Int, disc: Int, margin: Int): Int =
        if (side == Side.LEFT) margin else screenWidth - disc - margin

    /** Mientras se arrastra: el disco puede ir a cualquier parte de la pantalla, pero no salirse. */
    fun clampX(x: Int, screenWidth: Int, disc: Int): Int = x.coerceAtMost(screenWidth - disc).coerceAtLeast(0)

    /**
     * Limita el borde superior del disco a la pantalla y, si hay teclado, lo deja por encima de él.
     * @param keyboardTop borde superior del teclado, o null si no hay.
     */
    fun clampY(y: Int, screenHeight: Int, disc: Int, margin: Int, keyboardTop: Int?): Int {
        val floor = minOf(keyboardTop ?: screenHeight, screenHeight)
        return y.coerceAtMost(floor - disc - margin).coerceAtLeast(margin)
    }

    /** Borde horizontal más cercano al centro del disco. */
    fun nearestSide(discX: Int, screenWidth: Int, disc: Int): Side =
        if (discX + disc / 2 < screenWidth / 2) Side.LEFT else Side.RIGHT

    /** Posición por defecto: a la derecha, justo arriba del teclado. */
    fun defaultPosition(screenHeight: Int, disc: Int, margin: Int, keyboardTop: Int?): Position =
        Position(Side.RIGHT, clampY(Int.MAX_VALUE, screenHeight, disc, margin, keyboardTop))

    fun encode(position: Position): String = (if (position.side == Side.LEFT) "L" else "R") + ":" + position.y

    fun decode(value: String?): Position? {
        if (value == null || value.length < 3 || value[1] != ':') return null
        val side = when (value[0]) {
            'L' -> Side.LEFT
            'R' -> Side.RIGHT
            else -> return null
        }
        val y = value.substring(2).toIntOrNull() ?: return null
        return Position(side, y)
    }
}
