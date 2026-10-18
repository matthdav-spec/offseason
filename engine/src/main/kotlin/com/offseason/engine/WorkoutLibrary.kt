package com.offseason.engine

import com.offseason.engine.StepType.COOLDOWN
import com.offseason.engine.StepType.FREE_RIDE
import com.offseason.engine.StepType.INTERVALS
import com.offseason.engine.StepType.STEADY
import com.offseason.engine.StepType.WARMUP
import kotlin.math.roundToInt

/**
 * Every workout in the programme. Original sessions built on standard power-training
 * concepts; nothing is copied from any book.
 *
 * The plan is a lookup: (hours bucket × block type × week in block × slot) → workout.
 */
object WorkoutLibrary {

    /** Recovery weeks keep the same slots but cut volume by about this much. */
    const val RECOVERY_VOLUME_FACTOR = 0.6

    private const val WARMUP_MIN = 8
    private const val COOLDOWN_MIN = 4
    private const val Z2_LOW = 62
    private const val Z2_HIGH = 70

    /** Nominal session length in minutes for each slot. */
    fun slotMinutes(hours: HoursBucket, role: SlotRole): Int? = when (hours) {
        HoursBucket.THREE -> mapOf(SlotRole.INTERVALS_A to 60, SlotRole.INTERVALS_B to 60, SlotRole.LONG to 60)
        HoursBucket.FOUR -> mapOf(SlotRole.INTERVALS_A to 60, SlotRole.INTERVALS_B to 60, SlotRole.LONG to 90, SlotRole.EXTRA to 30)
        HoursBucket.FIVE -> mapOf(SlotRole.INTERVALS_A to 70, SlotRole.INTERVALS_B to 70, SlotRole.LONG to 120, SlotRole.EXTRA to 45)
    }[role]

    fun rolesFor(hours: HoursBucket): List<SlotRole> = SlotRole.entries.filter { slotMinutes(hours, it) != null }

    /** The 4-hour plan's short spin is optional; everything else is part of the plan. */
    fun isOptional(hours: HoursBucket, role: SlotRole): Boolean = hours == HoursBucket.FOUR && role == SlotRole.EXTRA

    /**
     * The workout for one slot.
     * @param progressionWeek 1..3 for build weeks (how hard the main set is). Ignored in recovery weeks.
     */
    fun workout(
        hours: HoursBucket,
        block: BlockType,
        blockIndex: Int,
        progressionWeek: Int,
        isRecovery: Boolean,
        role: SlotRole,
    ): Workout {
        val minutes = requireNotNull(slotMinutes(hours, role)) { "$hours has no $role slot" }
        val code = "B${blockIndex + 1}" + (if (isRecovery) "R" else "W$progressionWeek") + "-" + role.short
        if (isRecovery) {
            return when (role) {
                SlotRole.INTERVALS_B -> ftpTest(code)
                SlotRole.LONG -> endurance(code, scaled(minutes), recovery = true)
                SlotRole.INTERVALS_A -> cadenceSpin(code, scaled(minutes), recovery = true)
                SlotRole.EXTRA -> easySpin(code, scaled(minutes))
            }
        }
        require(progressionWeek in 1..3) { "progressionWeek must be 1..3" }
        val w = progressionWeek - 1
        return when (role) {
            SlotRole.INTERVALS_A -> mainSession(code, block, w, minutes)
            SlotRole.INTERVALS_B -> secondSession(code, block, w, minutes)
            SlotRole.LONG -> longRide(code, block, w, minutes, hours)
            SlotRole.EXTRA -> if (hours == HoursBucket.FOUR) easySpin(code, minutes) else cadenceSpin(code, minutes, recovery = false)
        }
    }

    private fun scaled(minutes: Int) = (minutes * RECOVERY_VOLUME_FACTOR / 5.0).roundToInt() * 5

    // ---- Main (A) interval sessions: the headline progression for each block ----

    private fun mainSession(code: String, block: BlockType, w: Int, minutes: Int): Workout = when (block) {
        BlockType.SWEET_SPOT -> {
            val sets = listOf(Triple(3, 10, 5), Triple(3, 12, 4), Triple(2, 20, 5))[w]
            structured(
                code, "Sweet spot ${sets.first}×${sets.second}", SessionKind.INTERVALS, minutes,
                "Long efforts just below threshold. Sweet spot builds FTP faster than easy riding without the fatigue of all-out work.",
                listOf(intervals(sets.first, sets.second, 88, 94, sets.third)),
            )
        }
        BlockType.THRESHOLD -> {
            val sets = listOf(Triple(3, 12, 4), Triple(4, 10, 3), Triple(2, 20, 5))[w]
            structured(
                code, "Threshold ${sets.first}×${sets.second}", SessionKind.INTERVALS, minutes,
                "Efforts right at your FTP. They teach your body to hold threshold power for longer, which is what lifts it.",
                listOf(intervals(sets.first, sets.second, 95, 100, sets.third)),
            )
        }
        BlockType.VO2 -> {
            val sets = listOf(5 to 3, 6 to 3, 5 to 4)[w]
            structured(
                code, "VO2max ${sets.first}×${sets.second}", SessionKind.INTERVALS, minutes,
                "Short, hard efforts above threshold. They raise your aerobic ceiling, giving FTP more room to grow.",
                listOf(intervals(sets.first, sets.second, 110, 120, 3)),
            )
        }
        BlockType.RACE_SPECIFIC -> {
            val main = listOf(
                listOf(intervals(3, 10, 95, 100, 4)),
                listOf(intervals(3, 12, 95, 100, 4)),
                listOf(intervals(2, 15, 95, 100, 5), steady(10, 82, 87, cadence = 88, label = "Tempo at race cadence")),
            )[w]
            structured(
                code, "Threshold + tempo", SessionKind.INTERVALS, minutes,
                "Threshold work with tempo at race cadence. It turns your winter FTP gains into power you can hold on race day.",
                main,
            )
        }
    }

    // ---- Second (B) interval sessions: complement the main set ----

    private fun secondSession(code: String, block: BlockType, w: Int, minutes: Int): Workout = when (block) {
        BlockType.SWEET_SPOT -> {
            val sets = listOf(Triple(4, 6, 3), Triple(4, 8, 3), Triple(3, 10, 3))[w]
            structured(
                code, "Low-cadence force ${sets.first}×${sets.second}", SessionKind.INTERVALS, minutes,
                "Sweet spot at a low cadence (about 60 rpm), like riding a steep climb. It builds muscular strength on the bike. Keep your upper body relaxed.",
                listOf(intervals(sets.first, sets.second, 85, 90, sets.third, cadence = 60)),
            )
        }
        BlockType.THRESHOLD -> {
            val reps = listOf(15, 18, 20)[w]
            structured(
                code, "Sweet spot 2×$reps", SessionKind.INTERVALS, minutes,
                "Longer sweet spot efforts that build endurance at a high percentage of your FTP.",
                listOf(intervals(2, reps, 88, 94, 5)),
            )
        }
        BlockType.VO2 -> {
            val sets = listOf(Triple(2, 12, 5), Triple(3, 10, 4), Triple(2, 15, 5))[w]
            structured(
                code, "Threshold ${sets.first}×${sets.second}", SessionKind.INTERVALS, minutes,
                "Threshold efforts that hold on to the gains from the last block while the VO2 work does its job.",
                listOf(intervals(sets.first, sets.second, 95, 100, sets.third)),
            )
        }
        BlockType.RACE_SPECIFIC -> {
            val main = listOf(
                listOf(intervals(2, 15, 80, 87, 5, cadence = 88)),
                listOf(intervals(2, 20, 80, 87, 5, cadence = 88)),
                listOf(steady(40, 80, 87, cadence = 88, label = "Long tempo at race cadence")),
            )[w]
            structured(
                code, "Race-pace tempo", SessionKind.TEMPO, minutes,
                "Long, steady tempo at the cadence you'd race at. It's the effort of a middle- or long-distance bike leg.",
                main,
            )
        }
    }

    // ---- Long ride ----

    private fun longRide(code: String, block: BlockType, w: Int, minutes: Int, hours: HoursBucket): Workout {
        val sets = listOf(Triple(2, 12, 4), Triple(2, 15, 5), Triple(2, 18, 5))[w]
        val cadence = if (block == BlockType.RACE_SPECIFIC) 88 else null
        val lead = when (hours) {
            HoursBucket.THREE -> emptyList()
            HoursBucket.FOUR -> listOf(steady(20, Z2_LOW, Z2_HIGH))
            HoursBucket.FIVE -> listOf(steady(30, Z2_LOW, Z2_HIGH))
        }
        val main = lead + intervals(sets.first, sets.second, 76, 85, sets.third, offPct = 65, cadence = cadence)
        val name = if (hours == HoursBucket.THREE) "Steady tempo" else "Endurance + tempo"
        return structured(
            code, name, if (hours == HoursBucket.THREE) SessionKind.TEMPO else SessionKind.ENDURANCE, minutes,
            "Steady aerobic riding with some tempo blocks. It builds the endurance base everything else sits on, without needing much recovery.",
            main,
        )
    }

    private fun endurance(code: String, minutes: Int, recovery: Boolean): Workout = structured(
        code, "Endurance", SessionKind.ENDURANCE, minutes,
        if (recovery) "Easy endurance riding. Recovery week means less volume and no intensity, so your body absorbs the last few weeks."
        else "Easy endurance riding to build your aerobic base.",
        emptyList(),
    )

    private fun easySpin(code: String, minutes: Int): Workout {
        val warm = 5
        val cool = COOLDOWN_MIN
        return Workout(
            code, "Easy spin", "Optional. A short, easy spin to loosen your legs. Skip it if you're tired.", SessionKind.EASY,
            listOf(
                Step(WARMUP, warm * 60, 45, 60),
                Step(STEADY, (minutes - warm - cool) * 60, 55, 65),
                Step(COOLDOWN, cool * 60, 60, 45),
            ),
        )
    }

    private fun cadenceSpin(code: String, minutes: Int, recovery: Boolean): Workout {
        val drills = if (recovery) 4 else 6
        val drillBlock = intervals(drills, 1, 65, 70, 2, offPct = 55, cadence = 110)
        val fill = minutes - 6 - drillBlock.totalDurationSec / 60 - COOLDOWN_MIN
        val steps = buildList {
            add(Step(WARMUP, 6 * 60, 45, 65))
            add(drillBlock.copy(label = "High-cadence spin-ups (smooth, not hard)"))
            if (fill > 0) add(steady(fill, 60, 68, cadence = if (recovery) null else 95))
            add(cooldown(COOLDOWN_MIN))
        }
        return Workout(
            code,
            if (recovery) "Easy spin + cadence drills" else "Cadence & skills",
            if (recovery) "An easy ride with a few fast-pedalling drills to keep your legs sharp while you recover."
            else "Easy-power, high-cadence drills for a smoother, more efficient pedal stroke. That pays off when you're tired late in a race.",
            if (recovery) SessionKind.RECOVERY else SessionKind.CADENCE,
            steps,
        )
    }

    // ---- FTP test (20-minute protocol) ----

    fun ftpTest(code: String = "FTP-TEST"): Workout = Workout(
        code, "FTP test (20 min)",
        "A hard 20-minute effort to measure your FTP. Your new FTP is 95% of your 20-minute average power, and every target after this updates automatically.",
        SessionKind.FTP_TEST,
        listOf(
            Step(WARMUP, 20 * 60, 45, 70, label = "Easy, building warm-up"),
            Step(INTERVALS, 60, 85, repeat = 3, offDurationSec = 60, offPowerPct = 50, cadenceTarget = 110, label = "1 min high cadence, 1 min easy"),
            Step(FREE_RIDE, 5 * 60, 110, label = "5 min all-out: clears out your fresh-legs power"),
            Step(STEADY, 10 * 60, 50, label = "Easy recovery"),
            Step(FREE_RIDE, 20 * 60, 100, label = "20 min: the hardest effort you can hold for the full 20 minutes"),
            Step(COOLDOWN, 12 * 60, 60, 40, label = "Cool-down"),
        ),
    )

    // ---- Builders ----

    /** Warm-up, main set, Z2 filler to reach [minutes], cool-down. */
    private fun structured(
        code: String, name: String, kind: SessionKind, minutes: Int, purpose: String, main: List<Step>,
    ): Workout {
        val mainMin = main.sumOf { it.totalDurationSec } / 60
        val fill = minutes - WARMUP_MIN - mainMin - COOLDOWN_MIN
        val steps = buildList {
            add(Step(WARMUP, WARMUP_MIN * 60, 45, 75))
            addAll(main)
            if (fill >= 2) add(steady(fill, Z2_LOW, Z2_HIGH))
            add(cooldown(COOLDOWN_MIN))
        }
        return Workout(code, name, purpose, kind, steps)
    }

    private fun steady(min: Int, low: Int, high: Int? = null, cadence: Int? = null, label: String? = null) =
        Step(STEADY, min * 60, low, high, cadenceTarget = cadence, label = label)

    private fun cooldown(min: Int) = Step(COOLDOWN, min * 60, 65, 45)

    private fun intervals(
        reps: Int, onMin: Int, low: Int, high: Int, offMin: Int, offPct: Int = 55, cadence: Int? = null,
    ) = Step(
        INTERVALS, onMin * 60, low, high,
        repeat = reps, offDurationSec = offMin * 60, offPowerPct = offPct, cadenceTarget = cadence,
    )

    private val SlotRole.short: String
        get() = when (this) {
            SlotRole.INTERVALS_A -> "A"
            SlotRole.INTERVALS_B -> "B"
            SlotRole.LONG -> "L"
            SlotRole.EXTRA -> "X"
        }
}
