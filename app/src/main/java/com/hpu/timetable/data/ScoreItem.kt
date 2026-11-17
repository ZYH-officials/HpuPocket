package com.hpu.timetable.data

/** 一条成绩 */
data class ScoreItem(
    val course: String,
    val credit: String,
    val score: String,
)

/** 教务的一个学期：semester.id + 展示名 */
data class SemesterInfo(
    val id: String,
    val label: String,
    val isCurrent: Boolean = false,
)

/** 成绩查询结果。教务可能不给数据，要区分开，别都显示成「暂无成绩」 */
sealed interface ScoreResult {
    /** 拿到了成绩表（可能为空） */
    data class Ok(val items: List<ScoreItem>) : ScoreResult

    /** 学校要求先完成评教才给看 */
    data object EvalRequired : ScoreResult

    /** 其它失败（含「请不要过快点击」限流） */
    data class Fail(val message: String) : ScoreResult
}
