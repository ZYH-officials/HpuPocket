package com.hpu.timetable.ui.lab

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.hpu.timetable.data.auth.PersistCookieJar
import kotlinx.coroutines.delay

/** 实验平台首页 */
private const val LAB_HOME_URL = "http://sj.hpu.edu.cn/TEACH/"
/** 课表页路由 */
private const val TIMETABLE_HASH = "#/6001/modules/teach/stu/timeTable/yearterm"
/** CAS 登录页 */
private const val CAS_LOGIN_URL = "https://uia.hpu.edu.cn/cas/login?service=http%3A%2F%2Fsj.hpu.edu.cn%2Fhpu%2F"
/** TEACH 模块首页 */
private const val TEACH_HOME_URL = "http://sj.hpu.edu.cn/TEACH/#/"

/**
 * 用 WebView 打开实验平台课表页。
 * 流程：
 * 1. 先打开 CAS 登录页，让用户登录
 * 2. 登录成功后自动跟随跳转链，获取 JWT
 * 3. 用 JWT 打开实验平台，让页面自己处理 JWT
 * 4. 跳转到课表页，注入 HOOK 拦截数据
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LabImportScreen(onDone: (String?) -> Unit, onCancel: () -> Unit) {
    val ctx = LocalContext.current
    val jar = remember { PersistCookieJar(ctx) }
    var status by remember { mutableStateOf("请在下方登录统一身份认证") }
    var result by remember { mutableStateOf<String?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var jwt by remember { mutableStateOf<String?>(null) }
    var phase by remember { mutableStateOf("login") } // login -> cas_redirect -> lab_ready
    var hookInjected by remember { mutableStateOf(false) }
    var navigatingToTimetable by remember { mutableStateOf(false) }

    // 轮询拦截到的数据
    LaunchedEffect(phase, webView) {
        if (phase != "lab_ready") return@LaunchedEffect
        // 轮询期间每轮重新取 webView，避免启动时还没赋值
        repeat(90) {
            delay(500)
            val wv = webView ?: return@repeat
            var got: String? = null
            wv.evaluateJavascript("window.__HPU_YEARTERM__ || ''") { raw ->
                // raw 是 JSON 编码的字符串，必须用 JSONTokener 还原转义
                val clean = runCatching {
                    org.json.JSONTokener(raw).nextValue() as? String
                }.getOrNull()
                if (!clean.isNullOrBlank() && clean.length > 200) got = clean
            }
            delay(300) // 等 evaluateJavascript 回调落地
            val data = got
            if (!data.isNullOrBlank()) {
                status = "已抓到课表数据"
                onDone(data)
                return@LaunchedEffect
            }
        }
        status = "超时：没抓到数据"
        onDone(null)
    }

    Column(Modifier.fillMaxSize()) {
        Text(
            status,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(14.dp),
        )
        TextButton(onClick = onCancel) { Text("取消") }
        Box(Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { c ->
                    WebView.setWebContentsDebuggingEnabled(true)

                    WebView(c).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        settings.javaScriptCanOpenWindowsAutomatically = true

                        // 同步 OkHttp 的 cookie
                        val cm = CookieManager.getInstance()
                        cm.setAcceptCookie(true)
                        cm.setAcceptThirdPartyCookies(this, true)
                        jar.all().forEach { ck ->
                            val host = ck.domain.removePrefix(".")
                            cm.setCookie("http://" + host, ck.name + "=" + ck.value)
                            cm.setCookie("https://" + host, ck.name + "=" + ck.value)
                        }
                        cm.flush()

                        webChromeClient = object : android.webkit.WebChromeClient() {
                            override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                                com.hpu.timetable.AppLog.d(
                                    "HPU_LAB",
                                    "console[" + m.messageLevel() + "] " + m.message() + " @" + m.lineNumber(),
                                )
                                return true
                            }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                                com.hpu.timetable.AppLog.d("HPU_LAB", "pageStarted: $url (phase=$phase)")
                                // 尽早装钩子，抢在页面发数据字典请求之前
                                view.evaluateJavascript(STUB_JS, null)
                                view.evaluateJavascript(ERR_JS, null)
                                view.evaluateJavascript(NET_JS, null)
                            }

                            override fun onPageCommitVisible(view: WebView, url: String?) {
                                super.onPageCommitVisible(view, url)
                                com.hpu.timetable.AppLog.d("HPU_LAB", "commitVisible: $url")
                                view.evaluateJavascript(STUB_JS, null)
                                view.evaluateJavascript(NET_JS, null)
                            }

                            override fun onPageFinished(view: WebView, url: String?) {
                                com.hpu.timetable.AppLog.d("HPU_LAB", "pageFinished: $url (phase=$phase)")

                                when (phase) {
                                    "login" -> {
                                        if (url != null && (url.contains("homeage") || url.contains("common/cas"))) {
                                            phase = "lab_ready"
                                            status = "SSO 完成，加载课表页…"
                                            view.evaluateJavascript(
                                                "JSON.stringify(Object.keys(sessionStorage))"
                                            ) { keys -> com.hpu.timetable.AppLog.d("HPU_LAB", "ss keys: " + keys) }
                                        } else if (url != null && url.contains("uia.hpu.edu.cn")) {
                                            status = "会话已过期，请在下方登录"
                                        }
                                    }
                                    "cas_redirect" -> {
                                        // CAS 登录成功，正在跟随跳转链
                                        if (url != null && url.contains("common/cas") && url.contains("id=")) {
                                            // 从 URL 中提取 JWT
                                            val newJwt = url.substringAfter("id=").substringBefore("&")
                                            jwt = newJwt
                                            com.hpu.timetable.AppLog.d("HPU_LAB", "Got JWT from CAS redirect: ${newJwt.take(50)}...")
                                            phase = "lab_ready"
                                            status = "已获取会话，加载课表页…"
                                        }
                                    }
                                    "lab_ready" -> {
                                        if (url != null) {
                                            view.evaluateJavascript("JSON.stringify(Object.keys(sessionStorage))") {
                                                com.hpu.timetable.AppLog.d("HPU_LAB", "ss@$url -> $it")
                                            }
                                        }
                                        // 实验平台页面加载完成
                                        if (url != null && !url.contains("/login") && !url.contains("/common/cas")) {
                                            // 只注入一次 HOOK
                                            view.evaluateJavascript(STUB_JS, null)
                                            view.evaluateJavascript(ERR_JS, null)
                                            view.evaluateJavascript(NET_JS, null)
                                            view.evaluateJavascript(HOOK_JS) { result ->
                                                com.hpu.timetable.AppLog.d("HPU_LAB", "HOOK_JS result: $result")
                                            }
                                            if (!hookInjected) {
                                                hookInjected = true
                                                com.hpu.timetable.AppLog.d("HPU_LAB", "reload to re-fire XHR under hook")
                                                view.postDelayed({ view.reload() }, 800)
                                            }
                                            
                                            // 如果还在系统首页，先跳转到 TEACH 模块
                                            if (url.contains("homeageStu") && !navigatingToTimetable) {
                                                com.hpu.timetable.AppLog.d("HPU_LAB", "On system home, navigating to TEACH module")
                                                navigatingToTimetable = true
                                                // 补上"从门户点应用"才会写入的 4 个键，否则 TEACH 不知道渲染哪个菜单
                                                view.evaluateJavascript(
                                                    "sessionStorage.setItem('MenuId','8907');" +
                                                        "sessionStorage.setItem('menuid_stu','8900');" +
                                                        "sessionStorage.setItem('youid_route','6001');" +
                                                        "sessionStorage.setItem('moduleInfo', JSON.stringify({code:'TEACH',icon:'',name:'实验实践教学管理平台',yeartermArea:''}));" +
                                                        "sessionStorage.setItem('router_link', JSON.stringify('/6001/modules/teach/stu/timeTable/yearterm'));" +
                                                        "'ok'"
                                                ) { r -> com.hpu.timetable.AppLog.d("HPU_LAB", "inject keys: " + r) }
                                                view.postDelayed({
                                                    view.loadUrl(TEACH_HOME_URL)
                                                }, 1200)
                                            }
                                            // 如果在 TEACH 模块首页，跳转到课表页
                                            else if (url.contains("/TEACH/") && !url.contains("timeTable/yearterm") && navigatingToTimetable) {
                                                com.hpu.timetable.AppLog.d("HPU_LAB", "On TEACH home, navigating to timetable")
                                                view.postDelayed({
                                                    com.hpu.timetable.AppLog.d("HPU_LAB", "navigating to $TIMETABLE_HASH")
                                                    view.evaluateJavascript("location.hash='" + TIMETABLE_HASH + "';") { hashResult ->
                                                        com.hpu.timetable.AppLog.d("HPU_LAB", "hash change result: $hashResult")
                                                    }
                                                }, 1000)
                                                // 等待更长时间让页面完全加载，然后多次尝试点击
                                                view.postDelayed({
                                                    view.evaluateJavascript(ROUTER_JS) { r ->
                                                        com.hpu.timetable.AppLog.d("HPU_LAB", "ROUTER_JS: $r")
                                                    }
                                                    view.evaluateJavascript(CLICK_JS) { clickResult ->
                                                        com.hpu.timetable.AppLog.d("HPU_LAB", "CLICK_JS attempt 1: $clickResult")
                                                    }
                                                }, 3000)
                                                view.postDelayed({
                                                    view.evaluateJavascript(CLICK_JS) { clickResult ->
                                                        com.hpu.timetable.AppLog.d("HPU_LAB", "CLICK_JS attempt 2: $clickResult")
                                                    }
                                                }, 5000)
                                                view.postDelayed({
                                                    view.evaluateJavascript(CLICK_JS) { clickResult ->
                                                        com.hpu.timetable.AppLog.d("HPU_LAB", "CLICK_JS attempt 3: $clickResult")
                                                    }
                                                }, 8000)
                                            }
                                        }
                                    }
                                }
                            }

                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                val url = request?.url?.toString() ?: ""
                                com.hpu.timetable.AppLog.d("HPU_LAB", "shouldOverrideUrl: $url (phase=$phase)")

                                // 检测 CAS 登录成功后的跳转
                                if (phase == "login" && url.contains("sj.hpu.edu.cn")) {
                                    phase = "cas_redirect"
                                    status = "登录成功，正在获取会话…"
                                }

                                return false
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                errorCode: Int,
                                description: String?,
                                failingUrl: String?
                            ) {
                                com.hpu.timetable.AppLog.e(
                                    "HPU_LAB",
                                    "onReceivedError: code=$errorCode desc=$description url=$failingUrl",
                                )
                                super.onReceivedError(view, errorCode, description, failingUrl)
                            }
                        }

                        // 先打开 COMMON 端，让页面自己走完整 SSO 并写好 sessionStorage
                        com.hpu.timetable.AppLog.d("HPU_LAB", "loading COMMON for SSO")
                        loadUrl("http://sj.hpu.edu.cn/hpu/")
                        webView = this
                    }
                },
            )
        }
    }
}

private const val HOOK_JS = "(function(){if(window.__HPU_HOOK__)return;window.__HPU_HOOK__=true;" +
    "var o=XMLHttpRequest.prototype.open;XMLHttpRequest.prototype.open=function(m,u){this.__u=u;return o.apply(this,arguments)};" +
    "var s=XMLHttpRequest.prototype.send;XMLHttpRequest.prototype.send=function(b){var t=this;" +
    "this.addEventListener('load',function(){try{if(t.__u&&t.__u.indexOf('listStuYearterm')>=0){window.__HPU_YEARTERM__=t.responseText}}catch(e){}});" +
    "return s.apply(this,arguments)};})()"

private const val STUB_JS = "(function(){if(window.__HPU_STUB__)return;window.__HPU_STUB__=true;" +
    "console.log('STUB_HOOK installed');" +
    "var of=Array.prototype.find;" +
    "Array.prototype.find=function(fn,t){" +
    "try{var r=of.call(this,fn,t);" +
    "if(r===undefined&&this.length===0){return {order_no:0,jc:0,section:0,__stub:true}} " +
    "return r}catch(e){return of.call(this,fn,t)}};" +
    "})()"

private const val ERR_JS = "(function(){if(window.__HPU_ERR__)return;window.__HPU_ERR__=true;" +
    "window.addEventListener('unhandledrejection',function(e){" +
    "try{var r=e.reason;var s=(r&&r.stack)?r.stack:String(r);" +
    "console.log('STACK>> '+s.replace(/\\n/g,' | ').slice(0,900))}catch(x){}});" +
    "window.addEventListener('error',function(e){" +
    "try{console.log('ERR>> '+e.message+' @'+(e.filename||'')+':'+e.lineno)}catch(x){}});" +
    "})()"

private const val NET_JS = "(function(){if(window.__HPU_NET__)return;window.__HPU_NET__=true;" +
    "function lg(u,s,b){try{var t=String(b);console.log('NET '+s+' '+u+' :: '+t.slice(0,200));" +
    "if(u&&u.indexOf('listJcSection')>=0){try{var d=JSON.parse(t).result;var g={};" +
    "d.forEach(function(x){g[x.section]=(g[x.section]||0)+1});" +
    "console.log('JCSEC '+JSON.stringify(g)+' total='+d.length)}catch(e){console.log('JCSEC parse err')}}" +
    "}catch(e){}}" +
    "var o=XMLHttpRequest.prototype.open;XMLHttpRequest.prototype.open=function(m,u){this.__u=u;return o.apply(this,arguments)};" +
    "var s=XMLHttpRequest.prototype.send;XMLHttpRequest.prototype.send=function(b){var t=this;" +
    "this.addEventListener('load',function(){lg(t.__u,t.status,t.responseText);" +
    "if(t.__u&&t.__u.indexOf('listStuYearterm')>=0){window.__HPU_YEARTERM__=t.responseText;console.log('YEARTERM_SET len='+t.responseText.length)}});" +
    "this.addEventListener('error',function(){lg(t.__u,'ERR','')});return s.apply(this,arguments)};" +
    "if(window.fetch){var f=window.fetch;window.fetch=function(){var a=arguments;" +
    "return f.apply(this,a).then(function(r){try{console.log('NET '+r.status+' '+String(a[0]).slice(0,80))}catch(e){}return r})}}" +
    "})()"

private const val ROUTER_JS = "(function(){try{" +
    "var el=document.getElementById('app');if(!el)return 'no #app';" +
    "var vm=el.__vue__;if(!vm)return 'no __vue__';" +
    "var r=vm.\$router;if(!r)return 'no router';" +
    "var p='/6001/modules/teach/stu/timeTable/yearterm';" +
    "var cur=r.currentRoute&&r.currentRoute.path;" +
    "var st=vm.\$store&&vm.\$store.state;" +
    "var keys=st?Object.keys(st):[];" +
    "var mlen='n/a';try{var m=st.menu||st.menus||st.permission;mlen=m?(Array.isArray(m)?('arr'+m.length):('obj'+Object.keys(m).length)):'null'}catch(e){mlen='err'}" +
    "var info='store='+keys.join(',')+'|menu='+mlen;" +
    "if(cur===p){r.replace('/');setTimeout(function(){r.push(p)},300);return 'repush|'+info} " +
    "r.push(p);return 'pushed|'+info;" +
    "}catch(e){return 'err:'+e.message}})()"

private const val CLICK_JS = "(function(){try{" +
    "var el=Array.prototype.slice.call(document.querySelectorAll('.el-tabs__item,a,div,span'))" +
    ".filter(function(e){return e.children.length===0&&e.innerText&&e.innerText.trim()==='个人总课表'})[0];" +
    "if(el){el.click();return 'clicked'}return 'miss'}catch(e){return 'err'}})()"
