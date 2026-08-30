package com.notesapp.offline.data

/**
 * Evaluates the Math effect's configurable equation against whatever
 * numbers are currently on a note's screen.
 *
 * Each line of the note is one operand, referenced in the equation by its
 * 1-indexed ordinal spelled out with an English suffix: "1st", "2nd",
 * "3rd", "4th", "5th", ... "21st", "22nd", and so on. Combine those with
 * +, -, *, / and parentheses, e.g.:
 *
 *   (1st+2nd)-(3rd+4th)
 *
 * A blank equation means "add up every line" — the original behavior
 * before per-effect equations existed.
 */
object MathEquationEngine {

    /** Pulls the numeric value out of each line, in order, ignoring blank
     *  or non-numeric lines entirely (so "1st"/"2nd"/... always lines up
     *  with the Nth NUMBER on screen, not the Nth line of text). */
    fun lineValues(body: String): List<Long> =
        body.lines().mapNotNull { it.trim().toLongOrNull() }

    private fun ordinalToken(n: Int): String {
        val suffix = when {
            n % 100 in 11..13 -> "th"
            n % 10 == 1 -> "st"
            n % 10 == 2 -> "nd"
            n % 10 == 3 -> "rd"
            else -> "th"
        }
        return "$n$suffix"
    }

    /** Replaces every "1st"/"2nd"/... token in [equation] with the matching
     *  line's numeric value (0 if that line doesn't exist), then evaluates
     *  the resulting arithmetic expression. Returns null if the equation
     *  (after substitution) isn't valid arithmetic. Blank equation sums
     *  every value in [values]. */
    fun evaluate(equation: String, values: List<Long>): Long? {
        val trimmedEquation = equation.trim()
        if (trimmedEquation.isEmpty()) return values.sum()

        var substituted = trimmedEquation
        // Replace longest ordinals first (21st before 1st) so "21st" never
        // partially matches as "1st" with a stray "2" left behind.
        for (i in values.indices.sortedByDescending { it }) {
            substituted = substituted.replace(ordinalToken(i + 1), values[i].toString(), ignoreCase = true)
        }
        // Any remaining ordinal token (referencing a line that doesn't
        // exist) becomes 0 — matches "4th" being blank if there's no 4th
        // number on screen yet, rather than failing the whole equation.
        substituted = Regex("\\b\\d+(st|nd|rd|th)\\b", RegexOption.IGNORE_CASE).replace(substituted, "0")

        return runCatching { SimpleExpressionParser(substituted).parse() }.getOrNull()
    }

    /** Minimal recursive-descent parser/evaluator for +, -, *, /, unary
     *  minus, and parentheses over integers — just enough for the Math
     *  effect's equations, no external dependency needed. */
    private class SimpleExpressionParser(private val text: String) {
        private var pos = 0

        fun parse(): Long {
            val result = parseExpr()
            skipSpace()
            if (pos != text.length) error("Unexpected character at $pos")
            return result
        }

        private fun skipSpace() { while (pos < text.length && text[pos].isWhitespace()) pos++ }
        private fun peek(): Char? { skipSpace(); return text.getOrNull(pos) }

        private fun parseExpr(): Long {
            var value = parseTerm()
            while (true) {
                when (peek()) {
                    '+' -> { pos++; value += parseTerm() }
                    '-' -> { pos++; value -= parseTerm() }
                    else -> return value
                }
            }
        }

        private fun parseTerm(): Long {
            var value = parseFactor()
            while (true) {
                when (peek()) {
                    '*' -> { pos++; value *= parseFactor() }
                    '/' -> {
                        pos++
                        val divisor = parseFactor()
                        value = if (divisor == 0L) 0L else value / divisor
                    }
                    else -> return value
                }
            }
        }

        private fun parseFactor(): Long {
            when (peek()) {
                '-' -> { pos++; return -parseFactor() }
                '+' -> { pos++; return parseFactor() }
                '(' -> {
                    pos++
                    val value = parseExpr()
                    if (peek() != ')') error("Missing closing parenthesis")
                    pos++
                    return value
                }
                else -> {
                    skipSpace()
                    val start = pos
                    while (pos < text.length && (text[pos].isDigit())) pos++
                    if (pos == start) error("Expected a number at $pos")
                    return text.substring(start, pos).toLong()
                }
            }
        }
    }
}
