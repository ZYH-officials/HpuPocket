package com.hpu.timetable.data.jw

import com.hpu.timetable.data.catching
import com.hpu.timetable.data.Building
import com.hpu.timetable.data.CourseInstance
import com.hpu.timetable.data.ExamItem
import com.hpu.timetable.data.FreeRoom
import com.hpu.timetable.data.RoomDay
import com.hpu.timetable.data.ScoreResult
import com.hpu.timetable.data.SemesterInfo
import com.hpu.timetable.data.auth.PersistCookieJar
import com.hpu.timetable.data.auth.SessionGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 教务管理系统（eams）课表抓取。
 * 流程（Python 已验证）：
 *   1. GET  /eams/courseTableForStd.action  —— 走 CAS 自动登录，页面里含学生 ids
 *   2. POST /eams/courseTableForStd!courseTable.action —— 返回内嵌 JS 的课表 HTML
 *   3. 解析 TaskActivity + index 行
 */
class JwClient(private val cookieJar: PersistCookieJar) {

    private val client: OkHttpClient = com.hpu.timetable.data.HttpClients.base.newBuilder()
        .cookieJar(cookieJar)
        // 教务对连续请求很敏感（会回「请不要过快点击」）。
        // 在这里统一限速：同一时刻只允许一个请求在飞，且两次之间至少 450ms。
        .addInterceptor { chain ->
            pace()
            chain.proceed(chain.request())
        }
        .followRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    private val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    private companion object {
        /** 成绩页（只是壳，数据在同目录的 person!search.action） */
        const val SCORE_PAGE = "http://zhjw.hpu.edu.cn/eams/teach/grade/course/person.action"

        /**
         * 空闲教室查询页。用 `free.action` 而不是 `std-activity.action`：
         * 后者是空壳，教学楼下拉由它再发 XHR 注入，服务端原始 HTML 里没有。
         * 另外必须走 https，否则 302 会把 POST 降级成 GET。
         */
        const val ROOM_PAGE = "https://zhjw.hpu.edu.cn/eams/classroom/apply/free.action"

        /** 5 个大节的时间段：1-2 / 3-4 / 6-7 / 8-9 / 10-11 */
        val BIG_SLOTS = listOf(
            "08:00" to "09:40",
            "10:00" to "11:40",
            "14:30" to "16:10",
            "16:30" to "18:10",
            "19:00" to "20:40",
        )

        val DOT = setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    }

    /** 进程内所有 JwClient 共用一条节流时间线 */
    private object Pacer {
        const val MIN_GAP_MS = 450L
        private val lock = Any()
        private var lastAt = 0L

        fun pace() {
            synchronized(lock) {
                val now = System.currentTimeMillis()
                val wait = lastAt + MIN_GAP_MS - now
                if (wait > 0) {
                    catching { Thread.sleep(wait) }
                }
                lastAt = System.currentTimeMillis()
            }
        }
    }

    private fun pace() = Pacer.pace()

    /** 教务的限流页固定写着这句，优先识别它——它跟登录失效长得完全不像 */
    private fun ensureNotRateLimited(html: String) {
        if (html.contains("请不要过快点击")) {
            throw com.hpu.timetable.data.auth.RateLimitedException()
        }
    }

    suspend fun fetchCourses(): Result<List<CourseInstance>> = withContext(Dispatchers.IO) {
        catching {
            // 先走 eams 登录入口，让 CAS 完成一次票据交换（直接访问课表页会被反复挡回）
            catching {
                client.newCall(
                    Request.Builder()
                        .url("http://zhjw.hpu.edu.cn/eams/login.action")
                        .header("User-Agent", ua)
                        .build()
                ).execute().use { it.body?.string() }
            }
            val entry = "http://zhjw.hpu.edu.cn/eams/courseTableForStd.action"
            val page = client.newCall(
                Request.Builder().url(entry).header("User-Agent", ua).build()
            ).execute().use { resp ->
                if (!resp.isSuccessful) error("教务入口 HTTP " + resp.code)
                resp.body?.string().orEmpty()
            }
            ensureNotRateLimited(page)
            SessionGate.ensureLoggedIn(page)
            if (page.contains("login.action") && !page.contains("TaskActivity")) {
                throw com.hpu.timetable.data.auth.SessionExpiredException()
            }
            com.hpu.timetable.AppLog.d("HPU_JW", "page len=" + page.length + " hasLogin=" + page.contains("login.action") + " hasAct=" + page.contains("TaskActivity"))
            val ids = Regex("""addInput\(form,\s*"ids"\s*,\s*"(\d+)""")
                .find(page)?.groupValues?.get(1)
                ?: error("找不到学生 ids（页面 len=" + page.length + "）")

            val form = FormBody.Builder()
                .add("ignoreHead", "1")
                .add("setting.kind", "std")
                .add("startWeek", "")
                .add("semester.id", "")
                .add("ids", ids)
                .build()
            val html = client.newCall(
                Request.Builder()
                    .url("http://zhjw.hpu.edu.cn/eams/courseTableForStd!courseTable.action")
                    .header("User-Agent", ua)
                    .header("Referer", entry)
                    .header("X-Requested-With", "XMLHttpRequest")
                    .post(form)
                    .build()
            ).execute().use { resp ->
                if (!resp.isSuccessful) error("课表 HTTP " + resp.code)
                resp.body?.string().orEmpty()
            }
            catching {
                val semUrl = "http://zhjw.hpu.edu.cn/eams/dataQuery.action?tagId=semesterBar"
                val sem = client.newCall(
                    Request.Builder().url(semUrl).header("User-Agent", ua)
                        .header("X-Requested-With", "XMLHttpRequest").build()
                ).execute().use { it.body?.string().orEmpty() }
                com.hpu.timetable.AppLog.d("HPU_JW", "sem len=" + sem.length + " body=" + sem.take(700))
            }
            com.hpu.timetable.AppLog.d("HPU_JW", "html len=" + html.length + " acts=" + Regex("new TaskActivity").findAll(html).count())
            val list = parse(html)
            com.hpu.timetable.AppLog.d("HPU_JW", "parsed=" + list.size)
            list
        }
    }

    private fun parse(html: String): List<CourseInstance> {
        val lines = html.split("\n")
        val out = ArrayList<CourseInstance>()
        var cur: Activity? = null
        var lastTeachers = ""

        for (raw in lines) {
            val s = raw.trim()

            // 每个 activity 前面会有一行 var teachers = [{id:..,name:".."}]
            if (s.startsWith("var teachers = [")) {
                lastTeachers = Regex("""name:"([^"]*)"""")
                    .findAll(s).joinToString(",") { it.groupValues[1] }
                continue
            }

            if (s.startsWith("activity = new TaskActivity(") && s.endsWith(");")) {
                val inner = s.substring("activity = new TaskActivity(".length, s.length - 2)
                val parts = splitTopLevel(inner)
                cur = Activity(
                    course = unquote(parts.getOrNull(3)).substringBefore('(').trim(),
                    room = tidyRoom(unquote(parts.getOrNull(5))),
                    weeks = unquote(parts.getOrNull(6)),
                    teacher = lastTeachers,
                )
                continue
            }

            val m = Regex("""index\s*=\s*(\d+)\*unitCount\+(\d+);""").find(s)
            if (m != null && cur != null) {
                val day = m.groupValues[1].toInt() + 1
                val slot = m.groupValues[2].toInt() + 1
                val big = jcToBig(slot)
                cur.weeks.forEachIndexed { idx, c ->
                    if (c == '1') {
                        // 教务位串的索引比校历多 1（教务第 11 周 = 校历第 10 周），整体前移一位
                        out += CourseInstance(
                            week = idx,
                            day = day.coerceIn(1, 7),
                            big = big,
                            course = pretty(cur!!.course),
                            teacher = cur!!.teacher,
                            room = cur!!.room,
                            exp = "",
                            slotFrom = slot,
                            slotTo = slot,
                        )
                    }
                }
            }
        }
        return com.hpu.timetable.data.lab.LabParser.mergeAdjacent(out.distinct())
    }

    private data class Activity(val course: String, val room: String, val weeks: String, val teacher: String)

    /** 按顶层逗号切分函数参数（忽略引号与括号内的逗号） */
    private fun splitTopLevel(s: String): List<String> {
        val out = ArrayList<String>()
        val buf = StringBuilder()
        var quote: Char? = null
        var depth = 0
        for (ch in s) {
            when {
                quote != null -> {
                    buf.append(ch)
                    if (ch == quote) quote = null
                }
                ch == '"' || ch == '\'' -> {
                    quote = ch; buf.append(ch)
                }
                ch == '(' -> { depth++; buf.append(ch) }
                ch == ')' -> { depth--; buf.append(ch) }
                ch == ',' && depth == 0 -> { out += buf.toString().trim(); buf.clear() }
                else -> buf.append(ch)
            }
        }
        if (buf.isNotEmpty()) out += buf.toString().trim()
        return out
    }

    /** "南校区  南校区3号教学楼\t3307" → "南校区3号教学楼 3307" */
    private fun tidyRoom(raw: String): String {
        val flat = raw.replace(Regex("\\s+"), " ").trim()
        val m = Regex("""([^\s]*教学楼)\s+(\S+)""").find(flat)
        return if (m != null) m.groupValues[1] + " " + m.groupValues[2] else flat
    }

    /**
     * 成绩数据。
     *
     * 页面 `/eams/teach/grade/course/person.action` 只是个壳，真正的数据是它用
     * jQuery 发的一个 XHR：`person!search.action?semesterId=<id>&projectType=`。
     * 直接请求 person.action 只会拿到框架页（这就是之前一直解析出 0 条的原因）。
     *
     * 另外学校有评教前置：没评教时同一接口返回一段「以下情况不能查看成绩」的提示。
     */
    suspend fun fetchScores(semesterId: String): Result<ScoreResult> = withContext(Dispatchers.IO) {
        catching {
            ensureJwSession()
            val url = "http://zhjw.hpu.edu.cn/eams/teach/grade/course/person!search.action" +
                "?semesterId=" + semesterId + "&projectType=&_=" + System.currentTimeMillis()
            val html = client.newCall(
                Request.Builder().url(url)
                    .header("User-Agent", ua)
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("Referer", SCORE_PAGE)
                    .build()
            ).execute().use { it.body?.string().orEmpty() }
            com.hpu.timetable.AppLog.d("HPU_SCORE", "semester=" + semesterId + " len=" + html.length + " head=" + html.take(120))
            ensureNotRateLimited(html)
            SessionGate.ensureLoggedIn(html)
            when {
                html.contains("请不要过快点击") ->
                    ScoreResult.Fail("教务提示「请不要过快点击」，等几秒再刷新")
                html.contains("评教") && html.contains("不能查看成绩") ->
                    ScoreResult.EvalRequired
                html.isBlank() ->
                    ScoreResult.Fail("教务返回为空")
                else -> {
                    val parsed = ScoreParser.parse(html)
                    if (parsed.isEmpty() && !html.contains("gridtable") && html.length < 6000) {
                        // 既没有成绩表格，也不是评教/限流提示 → 多半被重定向到登录
                        throw com.hpu.timetable.data.auth.SessionExpiredException()
                    }
                    ScoreResult.Ok(parsed)
                }
            }
        }
    }

    /** 学期列表：教务 dataQuery.action 的 semesterBar，返回一段 JS 字面量，里面带全部学期 id */
    suspend fun fetchSemesters(): Result<List<SemesterInfo>> = withContext(Dispatchers.IO) {
        catching {
            ensureJwSession()
            val page = client.newCall(
                Request.Builder().url(SCORE_PAGE).header("User-Agent", ua).build()
            ).execute().use { it.body?.string().orEmpty() }
            ensureNotRateLimited(page)
            SessionGate.ensureLoggedIn(page)
            // 当前学期 id 只出现在成绩页这段 JS 配置里（页面本身是个 9615 字节的壳）
            val pageCurrent = Regex("""semesterId"\s*:\s*(\d+)""").find(page)?.groupValues?.get(1)
                .orEmpty()
                .ifBlank {
                    Regex("semesterCalendar\\(\\{[^}]*?value:\"(\\d+)\"")
                        .find(page)?.groupValues?.get(1).orEmpty()
                }

            val form = FormBody.Builder()
                .add("tagId", "semesterBar" + System.currentTimeMillis() + "Semester")
                .add("dataType", "semesterCalendar")
                .add("value", pageCurrent)
                .add("empty", "false")
                .build()
            val js = client.newCall(
                Request.Builder().url("http://zhjw.hpu.edu.cn/eams/dataQuery.action")
                    .header("User-Agent", ua)
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("Referer", SCORE_PAGE)
                    .post(form)
                    .build()
            ).execute().use { it.body?.string().orEmpty() }
            com.hpu.timetable.AppLog.d(
                "HPU_SCORE",
                "semesterBar len=" + js.length + " pageCurrent=" + pageCurrent +
                    " head=" + js.replace(Regex("""\s+"""), " ").take(120),
            )

            // 当前学期以响应自带的 semesterId 为准：页面里那个 input 是 JS 渲染的，抓不到
            val current = Regex("""semesterId:"(\d+)"""").find(js)?.groupValues?.get(1)
                .orEmpty().ifBlank { pageCurrent }

            val list = Regex("""\{id:(\d+),schoolYear:"([^"]*)",name:"([^"]*)"\}""")
                .findAll(js).map { m ->
                    val id = m.groupValues[1]
                    SemesterInfo(
                        id = id,
                        label = m.groupValues[2] + " 学年第 " + m.groupValues[3] + " 学期",
                        isCurrent = id == current,
                    )
                }.toList()
            if (list.isEmpty() || pageCurrent.isBlank()) {
                // 正常情况下一定有学期；解析不出来基本是被重定向到登录页，
                // 抛过期异常才能让 SessionGate 去重登并重试。
                throw com.hpu.timetable.data.auth.SessionExpiredException()
            }
            list
        }
    }

    /**
     * 空闲教室：教学楼列表。
     *
     * 教务桌面版没有「楼 × 节次」矩阵接口，只有
     * `free!getBuildingsByCampus.action`（校区→楼）和 `free!search.action`（按时间段给空闲教室）。
     * 教学楼 id 直接从查询页的 `classroom.building.id` 下拉里读，省一次请求。
     */
    /**
     * 教务的「项目/校区」是课表入口建立的，空闲教室页 free.action 却依赖它：
     * 会话里没有项目时，FreeAction.index 会抛 EamsException: project not selected，
     * 返回一页 12k 左右的 Error happened 页面（既不是登录页也不是限流页）。
     * 重登后新会话没有项目，所以这里补一次课表入口，再重试一次原请求。
     */
    private fun warmUpProject() {
        catching {
            client.newCall(
                Request.Builder()
                    .url("https://zhjw.hpu.edu.cn/eams/courseTableForStd.action")
                    .header("User-Agent", ua)
                    .build()
            ).execute().use { it.body?.string() }
        }
        com.hpu.timetable.AppLog.d("HPU_JW", "warmUpProject done")
    }

    suspend fun fetchBuildings(): Result<List<Building>> = withContext(Dispatchers.IO) {
        catching {
            RoomCache.buildings()?.let { return@catching it }
            ensureJwSession()
            var finalUrl = ROOM_PAGE
            fun loadRoomPage(): String = client.newCall(
                Request.Builder().url(ROOM_PAGE).header("User-Agent", ua).build()
            ).execute().use { resp ->
                finalUrl = resp.request.url.toString()
                resp.body?.string().orEmpty()
            }
            var html = loadRoomPage()
            if (html.contains("project not selected")) {
                com.hpu.timetable.AppLog.d("HPU_ROOM", "project not selected，先访问课表入口再重试")
                warmUpProject()
                html = loadRoomPage()
            }
            SessionGate.ensureLoggedIn(html)
            ensureNotRateLimited(html)
            if (!html.contains("classroom.building.id")) {
                // 页面既不是登录页也不是限流页，却没有教学楼下拉：把特征打出来，
                // 别再让"未知页面"靠猜（13913 字节那次就是这种情况）。
                com.hpu.timetable.AppLog.d(
                    "HPU_ROOM",
                    "noSelect url=" + finalUrl + " len=" + html.length +
                        " title=" + (Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL)
                            .find(html)?.groupValues?.get(1)?.trim() ?: "") +
                        " text=" + html.replace(Regex("<[^>]+>"), " ")
                            .replace(Regex("\\s+"), " ").trim().take(240),
                )
                // 把整页分块打进日志（logcat 单条上限约 4KB），方便回来查它到底是哪种提示页
                catching {
                    val step = 3000
                    var i = 0
                    while (i < html.length) {
                        val end = minOf(i + step, html.length)
                        com.hpu.timetable.AppLog.d("HPU_ROOM", "dump" + (i / step) + " " + html.substring(i, end))
                        i = end
                    }
                }
                throw com.hpu.timetable.data.auth.SessionExpiredException()
            }
            com.hpu.timetable.AppLog.d(
                "HPU_ROOM",
                "freePage len=" + html.length + " loginPage=" + SessionGate.isLoginPage(html) +
                    " head=" + html.replace(Regex("""\s+"""), " ").take(160),
            )
            val inner = Regex("""<select[^>]*name="classroom\.building\.id"[^>]*>(.*?)</select>""", DOT)
                .find(html)?.groupValues?.get(1)
                // 拿不到教学楼下拉基本就是会话被挡（含 5058 字节那种"半登录页"），
                // 交给 SessionGate 去重登重试；真没权限时重登后仍会失败，不会死循环。
                ?: throw com.hpu.timetable.data.auth.SessionExpiredException()
            val list = Regex("""<option[^>]*value="(\d*)"[^>]*>([^<]*)</option>""", DOT)
                .findAll(inner)
                .mapNotNull { m ->
                    val id = m.groupValues[1]
                    val name = m.groupValues[2].trim()
                    if (id.isBlank() || name.isBlank()) null else Building(id, name)
                }
                .toList()
            com.hpu.timetable.AppLog.d("HPU_ROOM", "buildings=" + list.size)
            if (list.isEmpty()) error("教学楼列表为空")
            RoomCache.saveBuildings(list)
            list
        }
    }

    /**
     * 某栋楼某天的教室空闲矩阵。
     *
     * 逐个 大节 查 `free!search.action`（服务端一次只回答「这段时间哪些教室空」），
     * 再按教室名合并成 5 列。单楼单节的结果一般几十条，不会触发 100 条分页。
     *
     * [force] = true 时绕过缓存（用户点「刷新」），否则同楼同日期 10 分钟内直接复用。
     */
    suspend fun fetchRoomDay(
        buildingId: String,
        date: java.time.LocalDate,
        force: Boolean = false,
    ): Result<List<RoomDay>> = withContext(Dispatchers.IO) {
        catching {
            if (!force) {
                val cached = RoomCache.get(buildingId, date)
                if (cached != null) {
                    com.hpu.timetable.AppLog.d(
                        "HPU_ROOM",
                        "cache hit building=" + buildingId + " date=" + date + " rooms=" + cached.size,
                    )
                    return@catching cached
                }
            }
            ensureJwSession()
            val slots = IntRange(0, BIG_SLOTS.size - 1)
            val free = slots.map { HashSet<String>() }
            val meta = LinkedHashMap<String, FreeRoom>()

            for (i in slots) {
                val (begin, end) = BIG_SLOTS[i]
                val html = postFreeSearch(buildingId, date, begin, end)
                ensureNotRateLimited(html)
                SessionGate.ensureLoggedIn(html)
                val rooms = RoomParser.parse(html)
                com.hpu.timetable.AppLog.d(
                    "HPU_ROOM",
                    "slot=" + (i + 1) + " " + begin + "-" + end + " len=" + html.length +
                        " rooms=" + rooms.size +
                        " head=" + html.replace(Regex("""\s+"""), " ").take(140),
                )
                rooms.forEach { r ->
                    if (r.name.isNotBlank()) {
                        free[i].add(r.name)
                        meta.putIfAbsent(r.name, r)
                    }
                }
                kotlinx.coroutines.delay(600) // 教务对连续请求敏感，留点间隔
            }

            if (slots.all { free[it].isEmpty() }) {
                // 一个教室都没有，且响应都很短 → 多半整页被挡
                throw com.hpu.timetable.data.auth.SessionExpiredException()
            }
            val names = meta.keys.sorted()
            com.hpu.timetable.AppLog.d(
                "HPU_ROOM",
                "building=" + buildingId + " date=" + date + " rooms=" + names.size,
            )
            names.map { n ->
                RoomDay(
                    name = n,
                    capacity = meta[n]?.capacity.orEmpty(),
                    free = slots.map { i -> n in free[i] },
                )
            }.also { RoomCache.put(buildingId, date, it) }
        }
    }

    /** POST 一次空闲教室查询，返回原始 HTML */
    private fun postFreeSearch(
        buildingId: String,
        date: java.time.LocalDate,
        timeBegin: String,
        timeEnd: String,
    ): String {
        val form = FormBody.Builder()
            .add("classroom.type.id", "")
            .add("classroom.campus.id", "")
            .add("classroom.building.id", buildingId)
            .add("seats", "")
            .add("classroom.name", "")
            .add("cycleTime.cycleCount", "1")
            .add("cycleTime.cycleType", "1")
            .add("cycleTime.dateBegin", date.toString())
            .add("cycleTime.dateEnd", date.toString())
            .add("roomApplyTimeType", "1")
            .add("timeBegin", timeBegin)
            .add("timeEnd", timeEnd)
            .build()
        return client.newCall(
            Request.Builder()
                .url("https://zhjw.hpu.edu.cn/eams/classroom/apply/free!search.action")
                .header("User-Agent", ua)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", ROOM_PAGE)
                .post(form)
                .build()
        ).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                com.hpu.timetable.AppLog.d("HPU_ROOM", "HTTP " + resp.code + " body=" + body.take(200))
                error(if (resp.code == 500) "教务会话可能已失效（HTTP 500）" else "空闲教室 HTTP " + resp.code)
            }
            body
        }
    }


    /**
     * 教务会话预备：只用 login.action 走完 CAS 换票。
     *
     * 这里原来还会再打一次 homeExt.action，但会话失效时它会 302 到 loginExt.action，
     * 等于每次刷新都多向教务要一次登录页，进而触发反洪泛（回 5058 字节的「请不要过快点击」）。
     * fetchCourses 不碰 homeExt 所以一直正常，这就是「更新课表没事、其他刷新报限流」的根因。
     */
    private fun ensureJwSession() {
        catching {
            client.newCall(
                Request.Builder().url("http://zhjw.hpu.edu.cn/eams/login.action").header("User-Agent", ua).build()
            ).execute().use { it.body?.string() }
        }
    }

    /**
     * 考试安排。
     *
     * 入口是 `/eams/stdExamTable.action` 这个壳页：服务端会 302 到
     * `stdExamTable!examTable.action?semester.id=<当前学期>`，最终返回带 gridtable 的片段。
     * 所以不用硬编码学期 id，让它自己跳；跳不到时再从壳页里的 `semester.id=<id>` 兜底取一次。
     */
    suspend fun fetchExams(): Result<List<ExamItem>> = withContext(Dispatchers.IO) {
        catching {
            ensureJwSession()
            val shell = client.newCall(
                Request.Builder().url("http://zhjw.hpu.edu.cn/eams/stdExamTable.action")
                    .header("User-Agent", ua)
                    .build()
            ).execute().use { resp ->
                if (!resp.isSuccessful) error("考试页 HTTP " + resp.code)
                resp.body?.string().orEmpty()
            }
            ensureNotRateLimited(shell)
            SessionGate.ensureLoggedIn(shell)
            val rows = Regex("<table[^>]*gridtable", setOf(RegexOption.IGNORE_CASE)).findAll(shell).count()
            com.hpu.timetable.AppLog.d("HPU_EXAM", "shell len=" + shell.length + " gridtable=" + rows)
            if (!shell.contains("gridtable") && (shell.contains("loginExt") || shell.contains("login.action"))) {
                throw com.hpu.timetable.data.auth.SessionExpiredException()
            }

            val direct = ExamParser.parse(shell)
            if (direct.isNotEmpty()) return@catching direct

            // 兜底：壳页里抠当前学期 id，按页面自己的姿势再要一次片段
            val sid = Regex("""semester\.id=(\d+)""").find(shell)?.groupValues?.get(1)
                ?: return@catching emptyList()
            val frag = client.newCall(
                Request.Builder()
                    .url(
                        "http://zhjw.hpu.edu.cn/eams/stdExamTable!examTable.action?semester.id=" +
                            sid + "&_=" + System.currentTimeMillis()
                    )
                    .header("User-Agent", ua)
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("Referer", "http://zhjw.hpu.edu.cn/eams/stdExamTable.action")
                    .build()
            ).execute().use { it.body?.string().orEmpty() }
            com.hpu.timetable.AppLog.d("HPU_EXAM", "frag len=" + frag.length + " sid=" + sid + " parsed=" + ExamParser.parse(frag).size)
            ExamParser.parse(frag)
        }
    }

    private fun unquote(s: String?): String {
        val t = s?.trim() ?: return ""
        return if (t.length >= 2 && (t.first() == '"' || t.first() == '\'') && t.last() == t.first())
            t.substring(1, t.length - 1) else t
    }

    /** 小节 → 大节 */
    private fun jcToBig(slot: Int): Int = when {
        slot <= 2 -> 1
        slot <= 4 -> 2
        slot <= 7 -> 3
        slot <= 9 -> 4
        else -> 5
    }

    private fun pretty(name: String): String {
        var n = name.replace(Regex("""b-(\d)"""), "B-$1")
        if (Regex("[\\u4e00-\\u9fa5]b$").containsMatchIn(n)) n = n.dropLast(1) + "B"
        return n
    }
}