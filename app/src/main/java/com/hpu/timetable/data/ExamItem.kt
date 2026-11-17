package com.hpu.timetable.data

/** 一条考试安排 */
data class ExamItem(
    val course: String,
    val type: String,
    val date: String,
    val timeRange: String,
    val place: String,
    val status: String,
) {
    val isScheduled: Boolean
        get() = date.isNotBlank() && !date.contains("未安排")
}
