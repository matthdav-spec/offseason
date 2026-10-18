package com.offseason.engine

import kotlin.math.roundToInt

/** Coggan-style power zones as % of FTP. Upper bounds are inclusive. */
enum class Zone(val label: String, val displayName: String, val lowPct: Int, val highPct: Int?) {
    Z1("Z1", "Active recovery", 0, 55),
    Z2("Z2", "Endurance", 56, 75),
    Z3("Z3", "Tempo", 76, 87),
    SS("SS", "Sweet spot", 88, 94),
    Z4("Z4", "Threshold", 95, 105),
    Z5("Z5", "VO2max", 106, 120),
    Z6("Z6", "Anaerobic", 121, 150),
    Z7("Z7", "Neuromuscular", 151, null);

    companion object {
        /**
         * The zone for a given % FTP. Sweet spot (88–94%) overlaps Tempo and Threshold
         * on paper, so we report it as its own zone, since that's what the plan prescribes.
         */
        fun of(pct: Double): Zone {
            val p = pct.roundToInt()
            return entries.first { it.highPct == null || p <= it.highPct }
        }
    }
}

object Ftp {
    /** 20-minute test: FTP = 95% of the 20-minute average power. */
    fun from20MinTest(avgPower20Min: Int): Int = (avgPower20Min * 0.95).roundToInt()

    /** Ramp test: FTP ≈ 75% of the best 1-minute power. */
    fun fromRampTest(best1MinPower: Int): Int = (best1MinPower * 0.75).roundToInt()

    fun watts(pct: Number, ftp: Int): Int = (pct.toDouble() * ftp / 100.0).roundToInt()

    /** Percentage change from [from] to [to], e.g. 200 → 212 = 6.0. */
    fun improvementPct(from: Int, to: Int): Double = (to - from) * 100.0 / from
}

/** Ranges shown during onboarding and at retests (section 5.9 of the brief). */
object Expectations {
    const val NEWER_RIDER = "Newer riders typically gain 8–15% FTP over 12–16 weeks on 3–5 hours a week."
    const val EXPERIENCED = "Experienced triathletes typically gain 3–6%, because there's less low-hanging fruit."
}
