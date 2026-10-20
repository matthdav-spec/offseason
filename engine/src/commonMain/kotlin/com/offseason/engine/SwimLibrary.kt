package com.offseason.engine

import com.offseason.engine.SwimStepType.COOLDOWN
import com.offseason.engine.SwimStepType.DRILLS
import com.offseason.engine.SwimStepType.KICK
import com.offseason.engine.SwimStepType.PULL
import com.offseason.engine.SwimStepType.REPEATS
import com.offseason.engine.SwimStepType.SWIM
import com.offseason.engine.SwimStepType.TEST
import com.offseason.engine.SwimStepType.WARMUP
import com.offseason.engine.SwimZone.CSS
import com.offseason.engine.SwimZone.EASY
import com.offseason.engine.SwimZone.FAST
import com.offseason.engine.SwimZone.STEADY
import com.offseason.engine.SwimZone.TEMPO
import kotlin.math.pow

/**
 * Every swim in the programme: original pool sessions built on standard CSS-based training,
 * aimed at an average age-group triathlete in the off-season (30–45 minutes, 2–3 swims a week).
 *
 * Winter goal: technique and aerobic swim fitness, with one CSS-based set a week from block 2.
 * Blocks run in step with the bike and run blocks:
 *   1 Technique & aerobic  →  2 Endurance  →  3 CSS (threshold)  →  4 Race-specific (continuous swimming)
 * Recovery weeks: shorter swims and the CSS test (or a "how far can I swim" check on the build-up route).
 */
object SwimLibrary {
    /** A typical age-group CSS (2:00/100 m), used to estimate session times before a test. */
    const val TYPICAL_CSS_SEC = 120

    val BLOCK_NAMES = listOf("Technique & aerobic", "Endurance", "CSS", "Race-specific")

    fun rolesFor(swimsPerWeek: Int): List<SwimRole> =
        listOf(SwimRole.MAIN, SwimRole.TECHNIQUE, SwimRole.EXTRA).take(swimsPerWeek)

    /**
     * The week's swims by role. [progressionWeek] is 1..3 in build weeks; ignored in recovery weeks.
     * [firstCssWeek]: the athlete has just moved up from the build-up route, so this week opens with the CSS test.
     */
    fun week(
        req: SwimPlanRequest, blockIndex: Int, progressionWeek: Int, isRecovery: Boolean, firstCssWeek: Boolean = false,
    ): Map<SwimRole, SwimWorkout> {
        val prefix = "S${blockIndex + 1}" + (if (isRecovery) "R" else "W$progressionWeek")
        return rolesFor(req.swimsPerWeek).associateWith { role ->
            val w = when {
                req.level == SwimLevel.BUILD_UP -> buildUp(prefix, role, blockIndex, progressionWeek, isRecovery, startStep(req.startDistanceM))
                firstCssWeek && role == SwimRole.MAIN -> cssTest("$prefix-M")
                isRecovery -> when (role) {
                    SwimRole.MAIN -> cssTest("$prefix-M")
                    SwimRole.TECHNIQUE -> technique("$prefix-T", 0, recovery = true)
                    SwimRole.EXTRA -> steady("$prefix-X", 0, recovery = true)
                }
                else -> when (role) {
                    SwimRole.MAIN -> main("$prefix-M", blockIndex, progressionWeek)
                    SwimRole.TECHNIQUE -> technique("$prefix-T", progressionWeek - 1, recovery = false)
                    SwimRole.EXTRA -> steady("$prefix-X", progressionWeek - 1, recovery = false)
                }
            }
            fitToPool(w, req.poolLengthM)
        }
    }

    // ---- CSS route: the main set each week ----

    private fun main(code: String, block: Int, week: Int): SwimWorkout {
        val i = week - 1
        return when (block) {
            0 -> session(
                code, "Aerobic 100s", SwimKind.ENDURANCE,
                "Steady 100s with short rests. They build swim fitness while keeping your stroke long and relaxed. Count your strokes per length and try to keep the number the same.",
                listOf(SwimStep(REPEATS, 100, STEADY, repeat = listOf(8, 10, 12)[i], restSec = 20)),
            )
            1 -> session(
                code, "Endurance 200s", SwimKind.ENDURANCE,
                "Longer repeats at a steady pace. Holding good form for 200 m at a time is the foundation of a strong 1.9 km race swim.",
                listOf(SwimStep(REPEATS, 200, STEADY, repeat = listOf(4, 5, 6)[i], restSec = 20)),
            )
            2 -> session(
                code, "CSS ${listOf("10 × 100", "12 × 100", "6 × 200")[i]}", SwimKind.CSS_SET,
                "Repeats at your CSS pace with short rest. This is the session that raises your threshold swim speed: steady, even and hard but controlled.",
                listOf(
                    if (i < 2) SwimStep(REPEATS, 100, CSS, repeat = listOf(10, 12)[i], restSec = 15)
                    else SwimStep(REPEATS, 200, CSS, repeat = 6, restSec = 20),
                ),
            )
            else -> session(
                code, "Race-pace swim", SwimKind.CSS_SET,
                "Longer continuous swimming at tempo, then a few CSS 100s. It practises settling into a rhythm you can hold for the whole race swim.",
                listOf(
                    SwimStep(SWIM, listOf(600, 800, 1000)[i], TEMPO, label = "Continuous. Settle into a rhythm and sight every 6–8 strokes"),
                    SwimStep(REPEATS, 100, CSS, repeat = 4, restSec = 15),
                ),
            )
        }
    }

    private fun technique(code: String, i: Int, recovery: Boolean): SwimWorkout = session(
        code, if (recovery) "Easy technique" else "Technique & drills", SwimKind.TECHNIQUE,
        "Drills that build an efficient stroke, then easy swimming to put them together. For most age-groupers, better technique is the biggest free speed in the swim.",
        buildList {
            add(SwimStep(DRILLS, 50, EASY, repeat = 4, restSec = 20, label = "Catch-up drill: one hand waits at the front until the other arrives"))
            add(SwimStep(DRILLS, 50, EASY, repeat = 4, restSec = 20, label = "Side kick: on your side, lower arm forward, roll to breathe"))
            if (!recovery) add(SwimStep(KICK, 50, STEADY, repeat = 4, restSec = 20, label = "Kick with a board (or arms forward). Small, fast kicks from the hip"))
            add(SwimStep(PULL, 100, STEADY, repeat = listOf(3, 4, 4)[i.coerceIn(0, 2)].let { if (recovery) 2 else it }, restSec = 20, label = "Pull buoy between your thighs. Focus on a long reach and full push back"))
            add(SwimStep(REPEATS, 50, FAST, repeat = if (recovery) 4 else 6, restSec = 30, label = "Fast but smooth. Hold your technique"))
        },
    )

    private fun steady(code: String, i: Int, recovery: Boolean): SwimWorkout = session(
        code, "Steady swim", SwimKind.ENDURANCE,
        "Longer, relaxed swimming to build endurance. Practise breathing to both sides and sighting forward once a length.",
        listOf(SwimStep(REPEATS, if (recovery) 200 else listOf(300, 400, 400)[i], STEADY, repeat = if (recovery) 3 else listOf(3, 3, 4)[i], restSec = 30)),
    )

    fun cssTest(code: String = "CSS-TEST"): SwimWorkout = SwimWorkout(
        code, "CSS test", "Two timed swims to find your CSS (Critical Swim Speed): the pace you can hold for about 1500 m. Every swim pace in your plan updates automatically.",
        SwimKind.CSS_TEST,
        listOf(
            SwimStep(WARMUP, 400, EASY, label = "Easy warm-up, mixing strokes if you like"),
            SwimStep(REPEATS, 50, TEMPO, repeat = 4, restSec = 20, label = "Build each 50 to a faster pace"),
            SwimStep(TEST, 400, CSS, label = "400 m timed: your best even-paced effort. Note the time"),
            SwimStep(SWIM, 200, EASY, label = "Easy swim to recover (5 minutes or so)"),
            SwimStep(TEST, 200, CSS, label = "200 m timed: your best effort. Note the time"),
            SwimStep(COOLDOWN, 200, EASY, label = "Easy cool-down"),
        ),
    )

    // ---- Build-up route: for swimmers who can't yet swim 400 m continuously ----

    /** Each rung: reps × distance, with rest as needed. The last rung is a continuous 400 m. */
    val BUILD_UP_LADDER = listOf(
        8 to 25, 10 to 25, 6 to 50, 8 to 50, 10 to 50, 6 to 100, 5 to 100,
        4 to 150, 3 to 200, 2 to 250, 2 to 300, 1 to 400,
    )

    /**
     * Where to start on the ladder for someone who can swim [distanceM] without stopping:
     * repeats of about half that distance, the usual way to build towards a longer continuous swim.
     */
    fun startStep(distanceM: Int): Int =
        if (distanceM <= 0) 0 else BUILD_UP_LADDER.indexOfFirst { it.second * 2 >= distanceM }.let { if (it < 0) BUILD_UP_LADDER.lastIndex else it }

    /**
     * Short repeats with plenty of rest, one rung longer each build week, up to a continuous 400 m.
     * Recovery weeks have a "distance check"; once that reaches 400 m the athlete moves up to the CSS plan.
     */
    private fun buildUp(prefix: String, role: SwimRole, block: Int, week: Int, isRecovery: Boolean, start: Int): SwimWorkout {
        val step = (start + block * 3 + (if (isRecovery) 2 else week - 1)).coerceAtMost(BUILD_UP_LADDER.lastIndex)
        if (role == SwimRole.TECHNIQUE) return session(
            "$prefix-T", "Technique basics", SwimKind.BUILD_UP,
            "Short drills to build a relaxed, efficient stroke, with plenty of rest. Use a kickboard or fins if they help. Good technique makes every other swim easier.",
            listOf(
                SwimStep(KICK, 25, EASY, repeat = 4, restSec = 40, label = "Kick with a board. Long legs, small kicks"),
                SwimStep(DRILLS, 25, EASY, repeat = 4, restSec = 40, label = "Side kick: on your side, lower arm forward, roll to breathe"),
                SwimStep(DRILLS, 25, EASY, repeat = 4, restSec = 40, label = "Catch-up: one hand waits at the front until the other arrives"),
                SwimStep(REPEATS, 25, EASY, repeat = 4 + step / 2, restSec = 40, label = "Easy front crawl. Breathe out steadily under water"),
            ),
        )
        if (isRecovery && role == SwimRole.MAIN) return session(
            "$prefix-M", "Distance check", SwimKind.BUILD_UP,
            "Swim as far as you comfortably can without stopping (any stroke, any pace), and note the distance. When you can do 400 m, you're ready to move up to the CSS plan.",
            listOf(SwimStep(SWIM, 400, EASY, label = "As far as you can, up to 400 m. Stop when you need to")),
        )
        val (reps, dist) = BUILD_UP_LADDER[step]
        val code = "$prefix-${if (role == SwimRole.MAIN) "M" else "X"}"
        if (reps == 1) return session(
            code, "Continuous ${dist} m", SwimKind.BUILD_UP,
            "Your goal swim: $dist m without stopping, at an easy pace. If you manage it, you're ready to move up to the CSS plan. If not, stop, rest, and finish the distance.",
            listOf(SwimStep(SWIM, dist, EASY, label = "Easy and relaxed. Long breath out under water")),
        )
        return session(
            code, "Build-up $reps × $dist m", SwimKind.BUILD_UP,
            "Short swims with as much rest as you need. Each week the swims get a little longer, building towards 400 m without stopping. Relax, breathe out under water, and don't race the clock.",
            listOf(SwimStep(REPEATS, dist, EASY, repeat = reps, restSec = 40, label = "Easy. Rest as long as you need between each")),
        )
    }

    /**
     * The same set swum at a steady aerobic pace instead of CSS: used when the week already has
     * three hard days, so swimming doesn't tip it into too much intensity.
     */
    fun softened(w: SwimWorkout): SwimWorkout = w.copy(
        name = w.name.replace("CSS ", "") + " (steady)",
        purpose = "This week already has three hard sessions, so this set is swum at a steady pace rather than CSS. Same distance, less strain.",
        kind = SwimKind.ENDURANCE,
        steps = w.steps.map { if (it.zone == SwimZone.CSS || it.zone == SwimZone.TEMPO) it.copy(zone = SwimZone.STEADY) else it },
    )

    // ---- Builders ----

    /** Warm-up, the main set, cool-down. */
    private fun session(code: String, name: String, kind: SwimKind, purpose: String, main: List<SwimStep>): SwimWorkout {
        val beginner = kind == SwimKind.BUILD_UP
        val steps = buildList {
            add(SwimStep(WARMUP, if (beginner) 100 else 300, EASY, label = "Easy, any stroke"))
            if (kind != SwimKind.TECHNIQUE && !beginner) add(SwimStep(DRILLS, 50, EASY, repeat = 4, restSec = 15, label = "Drill of your choice, e.g. catch-up"))
            addAll(main)
            add(SwimStep(COOLDOWN, if (beginner) 100 else 200, EASY, label = "Easy, any stroke"))
        }
        return SwimWorkout(code, name, purpose, kind, steps)
    }

    /** Rounds every distance up to whole lengths of the athlete's pool. */
    fun fitToPool(w: SwimWorkout, poolLengthM: Int): SwimWorkout =
        if (w.steps.all { it.distanceM % poolLengthM == 0 }) w
        else w.copy(steps = w.steps.map { s ->
            val d = ((s.distanceM + poolLengthM - 1) / poolLengthM) * poolLengthM
            s.copy(distanceM = d)
        })
}

/** Swim load, on the same scale as bike and run (1 hour at threshold = 100). */
object SwimLoad {
    /**
     * Effort from pace: CSS pace ÷ step pace, cubed (swimming effort rises steeply with speed).
     * Rest counts as zero effort.
     */
    fun sessionLoad(w: SwimWorkout, cssSec: Int? = null): Double {
        val css = cssSec ?: SwimLibrary.TYPICAL_CSS_SEC
        var load = 0.0
        for (s in w.steps) {
            val swimSec = s.estimatedSec(css) - s.restSec * s.repeat
            val intensity = css.toDouble() / s.zone.paceSec(css)
            load += swimSec / 3600.0 * intensity.pow(3) * 100
        }
        return load
    }
}
