package com.jeangbeat.feature.mixer

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Magnitude response of the JB Parametric EQ for drawing. Uses the same RBJ
 * biquad designs as the C++ engine (Effects.cpp), evaluated at 48 kHz.
 */
internal object EqResponse {
    private const val SR = 48_000.0
    private const val F_MIN = 20.0
    private const val F_MAX = 20_000.0

    fun xOf(freq: Float): Float = (log10(freq / F_MIN) / log10(F_MAX / F_MIN)).toFloat()

    private class Bq(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
        fun db(f: Double): Double {
            val w = 2 * PI * f / SR
            val c1 = cos(w); val s1 = sin(w); val c2 = cos(2 * w); val s2 = sin(2 * w)
            val nr = b0 + b1 * c1 + b2 * c2
            val ni = -(b1 * s1 + b2 * s2)
            val dr = 1 + a1 * c1 + a2 * c2
            val di = -(a1 * s1 + a2 * s2)
            val mag = sqrt((nr * nr + ni * ni) / (dr * dr + di * di))
            return 20 * log10(mag.coerceAtLeast(1e-9))
        }
    }

    private fun norm(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) = Bq(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)

    private fun lowShelf(f: Double, db: Double): Bq {
        val a = 10.0.pow(db / 40); val w = 2 * PI * f / SR; val c = cos(w); val s = sin(w)
        val al = s / 2 * sqrt(2.0); val sa = 2 * sqrt(a) * al
        return norm(a * ((a + 1) - (a - 1) * c + sa), 2 * a * ((a - 1) - (a + 1) * c), a * ((a + 1) - (a - 1) * c - sa),
            (a + 1) + (a - 1) * c + sa, -2 * ((a - 1) + (a + 1) * c), (a + 1) + (a - 1) * c - sa)
    }

    private fun highShelf(f: Double, db: Double): Bq {
        val a = 10.0.pow(db / 40); val w = 2 * PI * f / SR; val c = cos(w); val s = sin(w)
        val al = s / 2 * sqrt(2.0); val sa = 2 * sqrt(a) * al
        return norm(a * ((a + 1) + (a - 1) * c + sa), -2 * a * ((a - 1) + (a + 1) * c), a * ((a + 1) + (a - 1) * c - sa),
            (a + 1) - (a - 1) * c + sa, 2 * ((a - 1) - (a + 1) * c), (a + 1) - (a - 1) * c - sa)
    }

    private fun peak(f: Double, db: Double, q: Double): Bq {
        val a = 10.0.pow(db / 40); val w = 2 * PI * f / SR; val c = cos(w); val al = sin(w) / (2 * q)
        return norm(1 + al * a, -2 * c, 1 - al * a, 1 + al / a, -2 * c, 1 - al / a)
    }

    private fun highpass(f: Double): Bq {
        val w = 2 * PI * f / SR; val c = cos(w); val al = sin(w) / (2 * 0.707)
        return norm((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + al, -2 * c, 1 - al)
    }

    /** dB values at [points] log-spaced frequencies from 20 Hz to 20 kHz. */
    fun curve(params: List<Float>, points: Int): FloatArray {
        val p = params.map { it.toDouble() }
        val filters = buildList {
            if (p[7] > 21) add(highpass(p[7]))
            add(lowShelf(p[0], p[1]))
            add(peak(p[2], p[3], p[4].coerceAtLeast(0.1)))
            add(highShelf(p[5], p[6]))
        }
        return FloatArray(points) { i ->
            val f = F_MIN * (F_MAX / F_MIN).pow(i.toDouble() / (points - 1))
            filters.sumOf { it.db(f) }.toFloat().coerceIn(-30f, 30f)
        }
    }
}
