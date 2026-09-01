package com.deskforge.app.engine

/** Converts committed Android text into the bounded X11 keysyms accepted by RFB KeyEvent. */
internal object RfbTextEncoder {
    const val MAX_COMMITTED_CODE_POINTS = 4_096

    private const val XK_TAB = 0xff09
    private const val XK_RETURN = 0xff0d

    fun encode(text: CharSequence): IntArray? {
        val keysyms = ArrayList<Int>(text.length.coerceAtMost(MAX_COMMITTED_CODE_POINTS))
        var index = 0
        while (index < text.length) {
            if (keysyms.size >= MAX_COMMITTED_CODE_POINTS) return null
            val first = text[index]
            val codePoint = when {
                first == '\r' -> {
                    if (index + 1 < text.length && text[index + 1] == '\n') index += 1
                    '\n'.code
                }
                Character.isHighSurrogate(first) -> {
                    if (index + 1 >= text.length || !Character.isLowSurrogate(text[index + 1])) return null
                    index += 1
                    Character.toCodePoint(first, text[index])
                }
                Character.isLowSurrogate(first) -> return null
                else -> first.code
            }
            val keysym = when (codePoint) {
                '\t'.code -> XK_TAB
                '\n'.code -> XK_RETURN
                in 0x20..0x7e, in 0xa0..0xff -> codePoint
                in 0x100..0x10ffff -> 0x01000000 or codePoint
                else -> return null
            }
            keysyms += keysym
            index += 1
        }
        return keysyms.toIntArray()
    }
}
