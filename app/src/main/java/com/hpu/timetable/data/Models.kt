package com.hpu.timetable.data

/** 课表里的一节课 */
data class CourseInstance(
    val week: Int,
    val day: Int,
    val big: Int,
    val course: String,
    val teacher: String,
    val room: String,
    val exp: String,
    /** 跨几个大节（渲染高度用） */
    val span: Int = 1,
    /** 起始小节（1-based），和小节结束后绘制用，精确对齐 */
    val slotFrom: Int = 0,
    /** 结束小节（1-based） */
    val slotTo: Int = 0,
)
