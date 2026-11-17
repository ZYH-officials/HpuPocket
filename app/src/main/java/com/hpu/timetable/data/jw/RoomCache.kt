package com.hpu.timetable.data.jw

import com.hpu.timetable.data.RoomDay
import java.time.LocalDate

/**
 * 空闲教室结果缓存。
 *
 * 一次查询要发 6 个请求（1 个楼列表 + 5 个大节各一次 POST，节间还 sleep 600ms），
 * 是全 App 最容易被教务判「请不要过快点击」的路径。
 * 同楼同日期 10 分钟内直接复用上次结果，最多旧 10 分钟；点「刷新」会强制绕过缓存。
 *
 * 只放内存：进程重启就没了，不会把过期数据带到下次打开。
 */
object RoomCache {

    const val TTL_MS = 10 * 60 * 1000L

    private data class Key(val buildingId: String, val date: LocalDate)
    private class Entry(val at: Long, val rooms: List<RoomDay>)

    private val map = LinkedHashMap<Key, Entry>()
    private val lock = Any()

    private var buildings: Pair<Long, List<com.hpu.timetable.data.Building>>? = null

    fun buildings(): List<com.hpu.timetable.data.Building>? = synchronized(lock) {
        buildings?.takeIf { System.currentTimeMillis() - it.first < TTL_MS }?.second
    }

    fun saveBuildings(list: List<com.hpu.timetable.data.Building>) = synchronized(lock) {
        buildings = System.currentTimeMillis() to list
    }

    fun clear() = synchronized(lock) {
        map.clear()
        buildings = null
    }

    /** 命中且未过期才返回；过期项顺手删掉 */
    fun get(buildingId: String, date: LocalDate): List<RoomDay>? = synchronized(lock) {
        val key = Key(buildingId, date)
        val entry = map[key] ?: return null
        if (System.currentTimeMillis() - entry.at > TTL_MS) {
            map.remove(key)
            return null
        }
        entry.rooms
    }

    /** 写入并顺手清理过期项，避免长时间使用后无限增长 */
    fun put(buildingId: String, date: LocalDate, rooms: List<RoomDay>) = synchronized(lock) {
        map[Key(buildingId, date)] = Entry(System.currentTimeMillis(), rooms)
        val now = System.currentTimeMillis()
        val it = map.entries.iterator()
        while (it.hasNext()) {
            if (now - it.next().value.at > TTL_MS) it.remove()
        }
    }
}
