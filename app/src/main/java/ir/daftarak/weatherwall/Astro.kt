package ir.daftarak.weatherwall

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.tan

/** Sun and moon positions (port of the well known SunCalc formulas). Angles in radians. */
data class AstroState(
    val sunAlt: Double,
    val sunAz: Double,
    val moonAlt: Double,
    val moonAz: Double,
    val moonFraction: Double,
    val moonPhase: Double,
)

object Astro {
    private const val RAD = PI / 180.0
    private const val DAY_MS = 86400000.0
    private const val J1970 = 2440588.0
    private const val J2000 = 2451545.0
    private const val E = RAD * 23.4397

    private fun toDays(ms: Long) = ms / DAY_MS - 0.5 + J1970 - J2000

    private fun rightAscension(l: Double, b: Double) = atan2(sin(l) * cos(E) - tan(b) * sin(E), cos(l))
    private fun declination(l: Double, b: Double) = asin(sin(b) * cos(E) + cos(b) * sin(E) * sin(l))
    private fun azimuth(h: Double, phi: Double, dec: Double) = atan2(sin(h), cos(h) * sin(phi) - tan(dec) * cos(phi))
    private fun altitude(h: Double, phi: Double, dec: Double) = asin(sin(phi) * sin(dec) + cos(phi) * cos(dec) * cos(h))
    private fun siderealTime(d: Double, lw: Double) = RAD * (280.16 + 360.9856235 * d) - lw

    private class Coords(val ra: Double, val dec: Double, val dist: Double = 0.0)

    private fun sunCoords(d: Double): Coords {
        val m = RAD * (357.5291 + 0.98560028 * d)
        val c = RAD * (1.9148 * sin(m) + 0.02 * sin(2 * m) + 0.0003 * sin(3 * m))
        val l = m + c + RAD * 102.9372 + PI
        return Coords(rightAscension(l, 0.0), declination(l, 0.0))
    }

    private fun moonCoords(d: Double): Coords {
        val l0 = RAD * (218.316 + 13.176396 * d)
        val m = RAD * (134.963 + 13.064993 * d)
        val f = RAD * (93.272 + 13.229350 * d)
        val l = l0 + RAD * 6.289 * sin(m)
        val b = RAD * 5.128 * sin(f)
        val dt = 385001 - 20905 * cos(m)
        return Coords(rightAscension(l, b), declination(l, b), dt)
    }

    fun compute(ms: Long, lat: Double, lon: Double): AstroState {
        val lw = RAD * -lon
        val phi = RAD * lat
        val d = toDays(ms)
        val st = siderealTime(d, lw)

        val s = sunCoords(d)
        val hs = st - s.ra
        val m = moonCoords(d)
        val hm = st - m.ra

        val sdist = 149598000.0
        val ph = acos((sin(s.dec) * sin(m.dec) + cos(s.dec) * cos(m.dec) * cos(s.ra - m.ra)).coerceIn(-1.0, 1.0))
        val inc = atan2(sdist * sin(ph), m.dist - sdist * cos(ph))
        val angle = atan2(
            cos(s.dec) * sin(s.ra - m.ra),
            sin(s.dec) * cos(m.dec) - cos(s.dec) * sin(m.dec) * cos(s.ra - m.ra)
        )
        val fraction = (1 + cos(inc)) / 2
        val phase = 0.5 + 0.5 * inc * sign(angle) / PI

        return AstroState(
            sunAlt = altitude(hs, phi, s.dec),
            sunAz = azimuth(hs, phi, s.dec),
            moonAlt = altitude(hm, phi, m.dec),
            moonAz = azimuth(hm, phi, m.dec),
            moonFraction = fraction,
            moonPhase = phase,
        )
    }
}
