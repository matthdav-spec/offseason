package com.offseason.engine

/**
 * Builds the whole plan: stacked blocks → weeks → sessions placed on days.
 *
 * - 4/8/12/16 weeks = 1/2/3/4 blocks, in the order Sweet spot → Threshold → VO2max → Race-specific.
 * - Each block has [RecoveryCycle.buildWeeks] build weeks and then one recovery week that ends with an FTP retest.
 * - With the 2:1 cycle each block is 3 weeks long, so the plan is shorter (e.g. 12 → 9 weeks). The build weeks use
 *   the first two (gentler) steps of each block's progression.
 * - Each week starts on the weekday of [PlanRequest.startDate].
 */
object PlanGenerator {

    private val BLOCK_ORDER = listOf(BlockType.SWEET_SPOT, BlockType.THRESHOLD, BlockType.VO2, BlockType.RACE_SPECIFIC)

    fun generate(request: PlanRequest): Plan {
        require(request.schedule.bikeDays.isNotEmpty()) { "Choose at least one bike day" }
        val weekStartDay = request.startDate.dayOfWeek
        val weeks = mutableListOf<PlanWeek>()
        var weekIndex = 0
        for (blockIndex in 0 until request.length.blocks) {
            val block = BLOCK_ORDER[blockIndex]
            for (weekInBlock in 1..request.recoveryCycle.weeksPerBlock) {
                val isRecovery = weekInBlock == request.recoveryCycle.weeksPerBlock
                val weekStart = request.startDate.plusWeeks(weekIndex)
                val workouts = WorkoutLibrary.rolesFor(request.hours).associateWith { role ->
                    WorkoutLibrary.workout(request.hours, block, blockIndex, weekInBlock, isRecovery, role)
                }
                // In recovery weeks the retest is the most important session, so it is placed first.
                val priority = if (isRecovery) workouts.keys.sortedByDescending { workouts.getValue(it).kind == SessionKind.FTP_TEST }
                else workouts.keys.toList()
                val slots = priority.map { role ->
                    val w = workouts.getValue(role)
                    Scheduler.Slot(role, w.isHard, w.kind == SessionKind.FTP_TEST)
                }
                val placement = Scheduler.place(slots, request.schedule, weekStartDay)
                val sessions = placement.map { (role, day) ->
                    PlannedSession(
                        id = "w" + weekIndex.toString().padStart(2, '0') + "-" + role.name,
                        date = weekStart.plusDays(Scheduler.offset(weekStartDay, day)),
                        weekIndex = weekIndex,
                        role = role,
                        workout = workouts.getValue(role),
                        optional = WorkoutLibrary.isOptional(request.hours, role),
                    )
                }.sortedBy { it.date }
                weeks += PlanWeek(weekIndex, weekStart, blockIndex, block, weekInBlock, isRecovery, sessions)
                weekIndex++
            }
        }
        return Plan(request, weeks)
    }

    /** Total weeks a request will produce (differs from the nominal length on the 2:1 cycle). */
    fun totalWeeks(length: PlanLength, cycle: RecoveryCycle): Int = length.blocks * cycle.weeksPerBlock
}
