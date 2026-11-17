package com.camcalc

import java.math.BigDecimal
import java.math.RoundingMode

object Calc {

    /** يحوّل نص OCR أو ما يكتبه المستخدم إلى تعبير حسابي نظيف مثل 12+5*3 */
    fun normalize(raw: String): String {
        var s = raw
        // 2x3 أو 2 X 3 -> 2*3
        val xRegex = Regex("(\\d)\\s*[xX]\\s*(\\d)")
        s = xRegex.replace(s, "$1*$2")
        s = xRegex.replace(s, "$1*$2")
        val sb = StringBuilder()
        for (c in s) {
            when {
                c.isDigit() -> sb.append(c.digitToInt())
                c == '×' || c == '*' || c == '·' || c == '✕' || c == '✖' -> sb.append('*')
                c == '÷' || c == '/' || c == ':' -> sb.append('/')
                c == '−' || c == '–' || c == '—' || c == '-' -> sb.append('-')
                c == '+' -> sb.append('+')
                c == '.' || c == ',' || c == '٫' -> sb.append('.')
                c == '=' -> sb.append('=')
            }
        }
        return sb.toString().substringBefore("=")
    }

    /** يختار سطر المسألة من نص التعرّف على الصورة */
    fun extractExpression(text: String): String {
        val lineRegex = Regex("^\\d*\\.?\\d+(?:[+\\-*/]\\d*\\.?\\d+)+$")
        val lines = text.lines().map { normalize(it) }.filter { it.isNotEmpty() }
        lines.firstOrNull { lineRegex.matches(it) }?.let { return it }
        return normalize(text.replace("\n", ""))
    }

    fun eval(expr: String): Double? {
        if (expr.isEmpty()) return null
        return try {
            val p = Parser(expr)
            val v = p.parseExpr()
            if (p.pos != expr.length) null else v
        } catch (e: Exception) {
            null
        }
    }

    fun format(v: Double): String {
        return if (v == Math.floor(v) && Math.abs(v) < 1e15) {
            v.toLong().toString()
        } else {
            BigDecimal(v).setScale(8, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        }
    }

    fun toAsciiDigits(s: String): String {
        val sb = StringBuilder()
        for (c in s) {
            when {
                c.isDigit() -> sb.append(c.digitToInt())
                c == '٫' -> sb.append('.')
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    /** كل الأرقام الموجودة في نص الصفحة */
    fun numbers(text: String): List<Double> =
        Regex("\\d+(?:\\.\\d+)?").findAll(toAsciiDigits(text))
            .mapNotNull { it.value.toDoubleOrNull() }.toList()

    /** يحل كل سطر فيه مسألة، ويرجع قائمة "المسألة = النتيجة" */
    fun solveLines(text: String): List<String> {
        val out = mutableListOf<String>()
        for (line in text.lines()) {
            val expr = normalize(line)
            if (expr.none { it in "+-*/" } || expr.none { it.isDigit() }) continue
            val v = eval(expr)
            out.add(if (v == null) "$expr = ؟" else "$expr = ${format(v)}")
        }
        if (out.isEmpty()) {
            val joined = normalize(text.replace("\n", ""))
            val v = eval(joined)
            if (v != null && joined.any { it in "+-*/" }) out.add("$joined = ${format(v)}")
        }
        return out
    }

    /** 1 جمع، 2 طرح (الأول ناقص الباقي)، 3 ضرب، 4 متوسط */
    fun aggregate(mode: Int, nums: List<Double>): String {
        if (nums.isEmpty()) return "لا توجد أرقام في النص"
        return when (mode) {
            1 -> nums.joinToString(" + ") { format(it) } + " = " + format(nums.sum())
            2 -> nums.joinToString(" - ") { format(it) } + " = " +
                format(nums.drop(1).fold(nums[0]) { a, b -> a - b })
            3 -> nums.joinToString(" × ") { format(it) } + " = " +
                format(nums.fold(1.0) { a, b -> a * b })
            4 -> "المتوسط = " + format(nums.average()) + "  (" + nums.size + ")"
            else -> ""
        }
    }

    private class Parser(val s: String) {
        var pos = 0

        fun parseExpr(): Double {
            var v = parseTerm()
            while (pos < s.length && (s[pos] == '+' || s[pos] == '-')) {
                val op = s[pos++]
                val r = parseTerm()
                v = if (op == '+') v + r else v - r
            }
            return v
        }

        fun parseTerm(): Double {
            var v = parseFactor()
            while (pos < s.length && (s[pos] == '*' || s[pos] == '/')) {
                val op = s[pos++]
                val r = parseFactor()
                if (op == '*') {
                    v *= r
                } else {
                    if (r == 0.0) throw ArithmeticException("div0")
                    v /= r
                }
            }
            return v
        }

        fun parseFactor(): Double {
            if (pos < s.length && s[pos] == '-') { pos++; return -parseFactor() }
            if (pos < s.length && s[pos] == '+') { pos++; return parseFactor() }
            val start = pos
            while (pos < s.length && (s[pos].isDigit() || s[pos] == '.')) pos++
            if (start == pos) throw IllegalArgumentException("num")
            return s.substring(start, pos).toDouble()
        }
    }
}
