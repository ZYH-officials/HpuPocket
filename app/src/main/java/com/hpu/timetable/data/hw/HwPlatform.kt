package com.hpu.timetable.data.hw

/**
 * 一个作业平台。目前只接了「数你最灵」，但结构按多平台预留：
 * 每个平台有自己的登录地址、学校标识和一套独立的账号密码（见 [HwStore]）。
 */
data class HwPlatform(
    val id: String,
    val name: String,
    val baseUrl: String,
    val schoolCode: String,
    /** Referer 里用的前端路由，不影响接口本身 */
    val refererPath: String = "/assignment",
)

object HwPlatforms {

    /** 全部已接入的平台；以后加新平台只往这里追加 */
    val all: List<HwPlatform> = listOf(
        HwPlatform(
            id = "smartestu",
            name = "数你最灵",
            baseUrl = "https://smartestu.cn",
            schoolCode = "hpu",
        ),
    )

    fun byId(id: String): HwPlatform? = all.firstOrNull { it.id == id }
}
