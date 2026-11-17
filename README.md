# HpuPocket · 河理课表

河南理工大学（HPU）学生的原生课表 App，个人开源项目。把教务、实验平台、作业平台分散的网页系统，整理成一个可离线查看的 Android 应用。

> 非学校官方应用。课表、考试与成绩数据以教务系统为准。

## 功能

- 课表：教务理论课 + 实验平台实验课自动合并，含教室、教师、周次
- 考试安排与倒计时、成绩查询、空闲教室查询
- 作业平台待提交汇总（第三方服务，账号单独填写）
- 上课提醒（精确闹钟 + 每日自愈重排）、每周课表概览
- 课表缓存离线秒开，会话过期自动重登

## 分支

| 分支 | 说明 |
|---|---|
| `main` | 稳定版，面向日常使用，不含开发者选项 |
| `dev` | 开发版，设置页含开发者选项（测试通知、模拟会话过期等） |

## 构建

- Android Studio（或 JDK 17+）打开本目录
- `./gradlew assembleRelease`
- minSdk 31，targetSdk 37；UI 为 Jetpack Compose + Material 3 Expressive（material3 1.5.0-rc01）
- 仓库不含签名密钥：根目录放一份 `keystore.properties`（storeFile/storePassword/keyAlias/keyPassword，已 gitignore）即用正式签名；没有则自动退回 debug 签名，克隆即可编译
- 正式签名与 debug 签名互不兼容，更换签名需卸载重装

## 隐私

- 学号密码经 Android Keystore 加密后只存本机，可在登录页关闭记住密码
- 课表、成绩等缓存只存本机，没有自建服务器，不上传任何数据
- 退出登录清会话与缓存，卸载即全清
- 只读查询，不代替用户提交任何操作
- 「导出日志」由用户主动触发，日志不含密码与原始响应

## 致谢

- 协议与架构思路参考 [ZZUPocket](https://github.com/3beuml/ZZUPocket)（MIT）
- 首页短句来自 [一言](https://hitokoto.cn)

## 许可证

[MIT](LICENSE)