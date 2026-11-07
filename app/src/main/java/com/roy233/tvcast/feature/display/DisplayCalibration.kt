package com.roy233.tvcast.feature.display

/** 视频有效画面的四个独立顶点，以全屏输出缓冲的宽高归一化。 */
data class CalibrationPoint(val x: Float, val y: Float)

data class DisplayCorners(
    val topLeft: CalibrationPoint = CalibrationPoint(0f, 0f),
    val topRight: CalibrationPoint = CalibrationPoint(1f, 0f),
    val bottomRight: CalibrationPoint = CalibrationPoint(1f, 1f),
    val bottomLeft: CalibrationPoint = CalibrationPoint(0f, 1f),
) {
    fun sanitized(): DisplayCorners =
        DisplayCorners(
            topLeft.safe(0f..MAX_INSET, 0f..MAX_INSET, CalibrationPoint(0f, 0f)),
            topRight.safe((1f - MAX_INSET)..1f, 0f..MAX_INSET, CalibrationPoint(1f, 0f)),
            bottomRight.safe((1f - MAX_INSET)..1f, (1f - MAX_INSET)..1f, CalibrationPoint(1f, 1f)),
            bottomLeft.safe(0f..MAX_INSET, (1f - MAX_INSET)..1f, CalibrationPoint(0f, 1f)),
        )

    fun point(corner: Int): CalibrationPoint =
        when (corner) {
            1 -> topLeft
            2 -> topRight
            3 -> bottomRight
            else -> bottomLeft
        }

    fun withPoint(corner: Int, point: CalibrationPoint): DisplayCorners =
        when (corner) {
            1 -> copy(topLeft = point)
            2 -> copy(topRight = point)
            3 -> copy(bottomRight = point)
            else -> copy(bottomLeft = point)
        }.sanitized()

    private fun CalibrationPoint.safe(
        xRange: ClosedFloatingPointRange<Float>,
        yRange: ClosedFloatingPointRange<Float>,
        fallback: CalibrationPoint,
    ): CalibrationPoint =
        CalibrationPoint(
            if (x.isFinite()) x.coerceIn(xRange) else fallback.x,
            if (y.isFinite()) y.coerceIn(yRange) else fallback.y,
        )

    companion object {
        /** 每个角最多向内移动屏幕宽高的 20%，防止图像翻折。 */
        const val MAX_INSET = 0.2f
    }
}

enum class CalibrationPhase {
    HIDDEN,
    INTRO,
    ADJUST,
    CONFIRM,
}

enum class CalibrationDirection {
    LEFT,
    UP,
    RIGHT,
    DOWN,
}

data class CalibrationState(
    val phase: CalibrationPhase = CalibrationPhase.HIDDEN,
    val corner: Int = 1,
    val draft: DisplayCorners = DisplayCorners(),
)

data class SavedCalibration(
    val prompted: Boolean,
    val corners: DisplayCorners,
    val completed: Boolean = false,
)

interface DisplayCalibrationStore {
    fun load(): SavedCalibration

    fun savePrompted()

    fun save(corners: DisplayCorners)
}

/** 独占四角编辑与持久化；取消操作不会覆盖已保存的补偿。 */
class DisplayCalibrationCoordinator(
    private val store: DisplayCalibrationStore,
    private val viewportSize: () -> Pair<Int, Int>,
    private val onState: (CalibrationState) -> Unit,
    private val onCalibration: (DisplayCorners, Boolean) -> Unit,
) {
    private var saved = store.load()
    var state: CalibrationState = CalibrationState()
        private set

    fun initialize() {
        onCalibration(saved.corners.sanitized(), saved.completed)
        if (!saved.prompted) publish(CalibrationState(phase = CalibrationPhase.INTRO))
    }

    fun start() {
        if (!saved.prompted) {
            store.savePrompted()
            saved = saved.copy(prompted = true)
        }
        publish(
            CalibrationState(phase = CalibrationPhase.ADJUST, draft = saved.corners.sanitized()),
        )
    }

    fun skipOrCancel() {
        if (state.phase == CalibrationPhase.HIDDEN) return
        if (!saved.prompted) {
            store.savePrompted()
            saved = saved.copy(prompted = true)
        }
        publish(CalibrationState())
    }

    fun adjust(direction: CalibrationDirection) {
        if (state.phase != CalibrationPhase.ADJUST) return
        val (width, height) = viewportSize()
        if (width <= 0 || height <= 0) return
        val stepX = STEP_PX / width
        val stepY = STEP_PX / height
        val current = state.draft.point(state.corner)
        val moved =
            when (direction) {
                CalibrationDirection.LEFT -> current.copy(x = current.x - stepX)
                CalibrationDirection.RIGHT -> current.copy(x = current.x + stepX)
                CalibrationDirection.UP -> current.copy(y = current.y - stepY)
                CalibrationDirection.DOWN -> current.copy(y = current.y + stepY)
            }
        val next = state.draft.withPoint(state.corner, moved)
        if (next != state.draft) publish(state.copy(draft = next))
    }

    fun nextCorner() {
        if (state.phase != CalibrationPhase.ADJUST) return
        publish(
            if (state.corner == 4) state.copy(phase = CalibrationPhase.CONFIRM)
            else state.copy(corner = state.corner + 1),
        )
    }

    fun review() {
        if (state.phase == CalibrationPhase.CONFIRM)
            publish(state.copy(phase = CalibrationPhase.ADJUST, corner = 1))
    }

    fun finish() {
        if (state.phase != CalibrationPhase.CONFIRM) return
        val corners = state.draft.sanitized()
        store.save(corners)
        saved = SavedCalibration(prompted = true, corners = corners, completed = true)
        onCalibration(corners, true)
        publish(CalibrationState())
    }

    private fun publish(next: CalibrationState) {
        state = next
        onState(next)
    }

    private companion object {
        const val STEP_PX = 2f
    }
}
