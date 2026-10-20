package com.offseason.engine

/**
 * Builds the whole plan: stacked blocks → weeks → sessions placed on days.
 *
 * - 4/8/12/16 weeks = 1/2/3/4 blocks. Bike: Sweet spot → Threshold → VO2max → Race-specific.
 *   Run (optional): Aerobic base → Tempo → Threshold → Race-specific, in step with the bike blocks.
 * - Each block has [RecoveryCycle.buildWeeks] build weeks and then one recovery week that ends with
 *   an FTP retest (bike) and an LTHR test (run).
 * - With the 2:1 cycle each block is 3 weeks long, so the plan is shorter (e.g. 12 → 9 weeks). The build weeks use
 *   the first two (gentler) steps of each block's progression.
 * - Each week starts on the weekday of [PlanRequest.startDate].
 *   Swim (optional): Technique & aerobic → Endurance → CSS → Race-specific, with a CSS test in recovery weeks.
 * - Bike only: [Scheduler] (unchanged from the original pilot). Bike + run: [JointScheduler].
 * - Swims are placed last, on swim days, around the bike and run (see [JointScheduler.placeSwims]).
 */
object PlanGenerator {

    private val BLOCK_ORDER = listOf(BlockType.SWEET_SPOT, BlockType.THRESHOLD, BlockType.VO2, BlockType.RACE_SPECIFIC)

    fun generate(request: PlanRequest): Plan {
        require(request.hasBike || request.hasRun || request.hasSwim) { "Choose at least one sport to plan" }
        val run = request.run
        if (run != null) require(run.runDays.isNotEmpty()) { "Choose at least one run day" }
        val weekStartDay = request.startDate.dayOfWeek
        val weeks = mutableListOf<PlanWeek>()
        var weekIndex = 0
        for (blockIndex in 0 until request.length.blocks) {
            val block = BLOCK_ORDER[blockIndex]
            for (weekInBlock in 1..request.recoveryCycle.weeksPerBlock) {
                val isRecovery = weekInBlock == request.recoveryCycle.weeksPerBlock
                val weekStart = request.startDate.plusWeeks(weekIndex)
                val tag = "w" + weekIndex.toString().padStart(2, '0')

                val workouts = if (request.hasBike) WorkoutLibrary.rolesFor(request.hours).associateWith { role ->
                    WorkoutLibrary.workout(request.hours, block, blockIndex, weekInBlock, isRecovery, role)
                } else emptyMap()
                // In recovery weeks the retest is the most important session, so it is placed first.
                val bikePriority = if (isRecovery) workouts.keys.sortedByDescending { workouts.getValue(it).kind == SessionKind.FTP_TEST }
                else workouts.keys.toList()

                val runWorkouts = run?.let { RunLibrary.week(it.hours, blockIndex, weekInBlock.coerceAtMost(3), isRecovery) } ?: emptyMap()
                val runPriority = if (isRecovery) runWorkouts.keys.sortedByDescending { runWorkouts.getValue(it).kind == RunKind.LTHR_TEST }
                else runWorkouts.keys.toList()

                val bikePlacement: Map<SlotRole, kotlinx.datetime.DayOfWeek>
                val runPlacement: Map<RunRole, kotlinx.datetime.DayOfWeek>
                if (run == null) {
                    val slots = bikePriority.map { role ->
                        val w = workouts.getValue(role)
                        Scheduler.Slot(role, w.isHard, w.kind == SessionKind.FTP_TEST)
                    }
                    bikePlacement = Scheduler.place(slots, request.schedule, weekStartDay)
                    runPlacement = emptyMap()
                } else {
                    val p = JointScheduler.place(
                        bikePriority.map { r -> workouts.getValue(r).let { JointScheduler.BikeSlot(r, it.isHard, it.kind == SessionKind.FTP_TEST) } },
                        runPriority.map { r -> runWorkouts.getValue(r).let { JointScheduler.RunSlot(r, it.isHard, it.kind == RunKind.LTHR_TEST) } },
                        request.schedule, run.runDays, run.preferredLongRunDay, weekStartDay,
                    )
                    bikePlacement = p.bike
                    runPlacement = p.run
                }

                val sessions = bikePlacement.map { (role, day) ->
                    PlannedSession(
                        id = "$tag-${role.name}",
                        date = weekStart.plusDays(Scheduler.offset(weekStartDay, day)),
                        weekIndex = weekIndex,
                        role = role,
                        workout = workouts.getValue(role),
                        optional = WorkoutLibrary.isOptional(request.hours, role),
                    )
                }.sortedBy { it.date }
                val runs = runPlacement.map { (role, day) ->
                    PlannedRun(
                        id = "$tag-RUN_${role.name}",
                        date = weekStart.plusDays(Scheduler.offset(weekStartDay, day)),
                        weekIndex = weekIndex,
                        role = role,
                        workout = runWorkouts.getValue(role),
                    )
                }.sortedBy { it.date }
                val swims = request.swim?.takeIf { !it.coached }?.let { swimReq ->
                    val workouts2 = SwimLibrary.week(swimReq, blockIndex, weekInBlock.coerceAtMost(3), isRecovery)
                    val priority2 = if (isRecovery) workouts2.keys.sortedByDescending { workouts2.getValue(it).kind == SwimKind.CSS_TEST } else workouts2.keys.toList()
                    val hardDays = (sessions.filter { it.workout.isHard }.map { it.date.dayOfWeek } + runs.filter { it.workout.isHard }.map { it.date.dayOfWeek }).toSet()
                    val longDays = (sessions.filter { it.role == SlotRole.LONG }.map { it.date.dayOfWeek } + runs.filter { it.role == RunRole.LONG }.map { it.date.dayOfWeek }).toSet()
                    val placement = JointScheduler.placeSwims(
                        priority2.map { r -> workouts2.getValue(r).let { JointScheduler.SwimSlot(r, it.isHard, it.kind == SwimKind.CSS_TEST) } },
                        request.schedule.swimDays, hardDays, longDays, weekStartDay,
                    )
                    placement.map { (role, day) ->
                        // If the swim days only allow a hard swim by breaking the swim rules, make it aerobic instead.
                        // (Tests are kept: they're short, and recovery weeks have few hard sessions anyway.)
                        var w = workouts2.getValue(role)
                        if (w.kind == SwimKind.CSS_SET && JointScheduler.hardSwimBreaksRules(day, hardDays, longDays)) w = SwimLibrary.softened(w)
                        PlannedSwim("$tag-SWIM_${role.name}", weekStart.plusDays(Scheduler.offset(weekStartDay, day)), weekIndex, role, w)
                    }.sortedBy { it.date }
                } ?: emptyList()
                weeks += PlanWeek(
                    weekIndex, weekStart, blockIndex, block, weekInBlock, isRecovery, sessions,
                    runs = runs, runBlock = if (run != null) RunLibrary.BLOCK_ORDER[blockIndex] else null,
                    swims = swims,
                )
                weekIndex++
            }
        }
        return Plan(request, weeks)
    }

    /** Total weeks a request will produce (differs from the nominal length on the 2:1 cycle). */
    fun totalWeeks(length: PlanLength, cycle: RecoveryCycle): Int = length.blocks * cycle.weeksPerBlock
}
