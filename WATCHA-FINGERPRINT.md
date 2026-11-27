# 观猹自动化的指纹审计（2026-11-27）

审计范围：`WatchaCheckInTask`、`WatchaAiCopywriter`、`WatchaReviewPlan`、`WatchaTrialProbe`、`CheckInTask.commonHeaders()`。

判断依据两处：站点上 195 条真实猹评的统计分布，以及本账号 2026-11-06 至 11-25 那 20 条自动猹评的实测数据。

## 已经处理掉的

| # | 指纹 | 证据 | 处理 |
|---|---|---|---|
| 1 | 提交时间固定在同一个两分钟 | 20 条猹评里 19 条落在北京时间 09:13–09:14，相邻间隔中位 24.0 小时 | `WatchaPause.startJitter()`，开了自动发布时默认随机延后 0–20 分钟 |
| 2 | 所有请求的 Referer 都是 `/quest` | 猹评实际是在产品页提交的 | `headers(path)` 按动作传：猹评和回评用 `/products/{id}` |
| 3 | 领奖一秒内连发 | `claimAll()` 循环里连续 POST | 每次点击前 `pause.quick()` 0.8–3.0 秒 |
| 4 | 连续探测候选产品 | 每个产品两次请求背靠背 | 候选之间同样 `quick()` |
| 5 | 请求头缺 `sec-ch-ua`、`sec-ch-ua-mobile`，`sec-ch-ua-platform` 没加引号 | UA 声称 Edge 114 | `commonHeaders()` 补齐三个头 |
| 6 | GET 也带 `Content-Type`、`Origin`、`Connection: keep-alive` | 浏览器 GET 不带前两个，HTTP/1.1 默认也不显式写第三个 | `authorized()` 按方法裁剪，去掉固定 keep-alive |
| 7 | 猹评形状单一 | 20 条全是「恰好两段、130–235 字、无图无格式」，落在真实分布最窄的 4% 格子里；真实盘 45% 带图、22% 单段、14% 有加粗 | `WatchaReviewPlan` 按真实分布抽样，见下表 |
| 8 | 只会发，不会互动 | 历史上 0 条回评 | 新增偶发回评，见 `replyToOthers()` |

形状降维对照（10 万次抽样 vs 195 条真实样本）：

| 维度 | 真实 | 现在 |
|---|---|---|
| 跳过不发 | 不适用 | 34.7% |
| 字数 0-60 / 60-120 / 120-200 / 200-300 / 300-600 / 600+ | 16 / 14 / 25 / 17 / 16 / 12% | 16 / 14 / 25 / 17 / 16 / 12% |
| 段数 | 42% 一段，其余铺到十几段 | 49 / 8 / 9 / 8 / 6 / 5 / 4 / 8% |
| 带图 | 45% | 45.3% |
| 加粗 / 小标题 / 引用 | 14 / 2 / 3% | 14.0 / 1.1 / 1.3% |
| 投「不推荐」 | 15% | 14.8% |

## 三个结构性指紼的处理

纯 HTTP 客户端改不掉的三个（TLS 指纹、cookie/埋点、Accept-Encoding），走真浏览器一次解决：

| 指纹 | 浏览器模式下的状态 |
|---|---|
| TLS / JA3 与 UA 不符 | 握手由 Chrome 自己完成，UA 也用同一版 Chrome，两边一致 |
| 无 cookie、无埋点 | 登录、浏览、提交都在同一个浏览器上下文里；打开页面会真的触发 `recorder.js` 和统计脚本 |
| `Accept-Encoding` 不带 br/zstd | 由 Chrome 自己声明，含 `br`、`zstd` |

开关是 `WATCHA_BROWSER=true`，实现见 `WatchaBrowserTransport`：

1. `page.goTo("/signin")` 打开登录页（产生一次真实页面浏览）；
2. 在页面上下文 `fetch("/api/v2/auth/signin")`，cookie 自动落到浏览器；
3. 写操作前先 `goTo("/products/{id}")`，再在页面里 `fetch` 提交，Origin/Referer/Sec-Fetch 全由浏览器生成；
4. 每次启动会做一次只读自检（`GET /api/v2/users/me`），日志里有「浏览器通道自检：通过/失败」。

实测记录（2026-11-27）：容器内 Chrome for Testing 142.0.7444.175 启动成功，打开 `https://watcha.cn/` 取到标题「观猹丨玩 AI，上观猹！」；本机侧完整链路登录、`/users/me` 自检、`/checkins/today` 查询全部通过，UA 与 `sec-ch-ua` 都是 142。浏览器缓存落在 `state/auto-sign-in/browser`，重建容器不重下。

另外有个容易踩的坑：jvppeteer 的 `page.evaluate(String)` 会把字符串当函数体**立即调用**，所以脚本必须写成函数表达式（`async () => {...}`），写成自执行 IIFE `(async()=>{...})()` 会报 `Given expression does not evaluate to a function`。

没开浏览器模式时，代码仍然走原来的纯 HTTP 通道（快、稳），三个指纹就还是原样。

## 频率与熔断

内容像不像人，决定的是「会不会被折叠」；被折叠之后还按原频率继续发，才决定「会不会被封」。所以频率和熔断比文案更重要：

| 措施 | 现在 |
|---|---|
| 发布节奏 | 按周聚类：静默周概率 0.3（一周 0-1 条），活跃周 5-6 天有产出，平均约每周 4 条；实测真人会出现 5-8 天空档、偶尔一天 3 条 |
| 非推荐产品抽样 | 15% 的日子写普通产品，放弃当天任务奖励 |
| 会话复用 | access_token 15 分钟、refresh_token 30 天；每天静默刷新，不重复登录（登录是最容易被 449 拦下的一步） |
| 台账回查 | 每天把发过的猹评拉一遍，少一条记一次违规 |
| 自动停写 | 累计两次违规停写猹评，通知里写明，人工确认后清空台账恢复 |

## 还剩下的，按能不能修排序

| # | 指纹 | 说明 | 能不能修 |
|---|---|---|---|
| 1 | TLS / JA3 指纹和 UA 对不上 | Java 的 HTTP 客户端握手特征和 Chromium 完全不同；声称 Edge 114 却用 JDK 的 TLS 栈，是最硬的一层 | 纯 HTTP 客户端修不了，要么换 curl-impersonate 这类带浏览器握手的客户端，要么整条链路走真实浏览器 |
| 2 | 没有 cookie、没有埋点上报 | 真实访问会带 `_ga`、站点 session，还会打到 `hm.baidu.com` 和 `analysis.watcha.cn/recorder.js`；现在是「有登录、有发帖、没有一次页面浏览和埋点」 | 结构性问题，只能在浏览器里跑才有 |
| 3 | `Accept-Encoding` 不声明 `br`/`zstd` | 真实 Chromium 一定带；hutool 解不了 br，改了会解析失败 | 需要换 HTTP 客户端 |
| 4 | 选题 100% 来自当日推荐 | 真人不会天天刚好写推荐位那个产品 | 可以改，但会丢掉每日任务的瓜子，取舍问题 |
| 5 | 配图用产品封面 | 同一张图可能被多个账号用；真人配的是自己截的图 | 可以改：真浏览器截图 → `POST /api/v2/infra/external-resources/image` 换成站内图 |
| 6 | 每日同一账号、同一出口 IP、每天登录一次 | 没有设备或网络变化 | 多出口代理能缓解，但代理本身也有特征 |

结论：已经在代码层面能改的都改了。剩下的是「这个程序不是浏览器」这个事实带来的，靠调参解决不了。
