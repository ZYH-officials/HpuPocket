package com.hpu.timetable.data

/** 空闲教室查询结果里的一间教室 */
data class FreeRoom(
    val name: String,
    val building: String,
    val campus: String,
    val type: String,
    val capacity: String,
)

/** 一栋教学楼（教务 classroom.building.id） */
data class Building(
    val id: String,
    val name: String,
)

/**
 * 某天某栋楼里一间教室的空闲情况。
 * free 长度 = 5，对应 5 个大节（1-2 / 3-4 / 6-7 / 8-9 / 10-11），true 表示这段时间空闲。
 */
data class RoomDay(
    val name: String,
    val capacity: String,
    val free: List<Boolean>,
)
