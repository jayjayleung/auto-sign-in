<section align="center">
  <a href="https://github.com/jayjayleung/auto-sign-in" target="_blank">
    <img src="./static/logo.webp" alt="自动签到" width="260" />
  </a>
</section>
<h1 align="center">AutoSignIn-自动签到</h1>

## 简介
能对支持的社区或者论坛等自动化签到。本项目仅为我想偷懒加上老是忘记签到才做的，仅供学习使用。  
目前支持以下社区：
- **[墨天轮(墨道db)](https://tidb.net/)** 签到
- **[tidb](https://www.modb.pro/)** 签到
- **[永洪社区](https://club.yonghongtech.com/)** 签到+抽抽乐
- **[云桥积分中心](https://api.yunqiao.me/custom/93bbf0afef76f203)** 多账号签到
- **[观猹任务中心](https://watcha.cn/quest)** 每日签到、每日猹评和每周发帖
- **[西门子工业支持中心](https://www.ad.siemens.com.cn/MySupport/index.aspx)** 每日签到


## 如何使用?
使用自动化工作流有两种方式：快速使用(在线) 和 私有化部署(本地)

- 快速使用自动化：[阅读 使用](#使用)

- 私有化部署自动化：[阅读 私有化部署](#私有化部署)

  

## 使用

自动化执行任务: 对以上论坛进行日常签到, 最后将结果报告通知订阅人。

自动化运行时间: 北京时间上午 07:30 可在  [auto.yml](./.github/workflows/auto.yml) 中配置。

1. [fork仓库](https://github.com/jayjayleung/auto-sign-in)

2. 仓库 -> Settings -> Secrets -> Actions -> New repository secret，添加 Secrets 变量如下:

| Name | Value | Required |
|---|---|---|
| MODB_USERNAME | 墨天轮用户名 | 是 |
| MODB_PASSWORD | 墨天轮密码 | 是 |
| TIDB_LOGIN_MODE | `password`（默认）或 `cookie` | 否 |
| TIDB_USERNAME | 账号密码模式的 tidb 用户名 | 否 |
| TIDB_PASSWORD | 账号密码模式的 tidb 密码 | 否 |
| TIDB_COOKIE | Cookie 模式的浏览器 Cookie，需包含 `csrftoken` 和 `ssid` | 否 |
| YH_USERNAME | 永洪社区用户名 | 是 |
| YH_PASSWORD | 永洪社区密码 | 是 |
| YUNQIAO_USERNAME | 云桥单账号邮箱 | 否 |
| YUNQIAO_PASSWORD | 云桥单账号密码 | 否 |
| YUNQIAO_USERNAME_1 | 云桥账号 1 邮箱 | 否 |
| YUNQIAO_PASSWORD_1 | 云桥账号 1 密码 | 否 |
| YUNQIAO_USERNAME_2 | 云桥账号 2 邮箱 | 否 |
| YUNQIAO_PASSWORD_2 | 云桥账号 2 密码 | 否 |
| WATCHA_USERNAME | 观猹账号，邮箱或手机号 | 否 |
| WATCHA_PASSWORD | 观猹密码 | 否 |
| WATCHA_AUTO_REVIEW | 在签到通知里列出当日推荐产品和体验入口，默认开启；自动猹评打开后不再推送 | 否 |
| WATCHA_PUBLISH_REVIEW | 自动写猹评并提交（含偶发回评），默认关闭。打开后每天最多 1 条，形状按站点真实分布抽样 | 否 |
| WATCHA_BROWSER | 用真浏览器登录并提交，解决 TLS 指纹、cookie、埋点三件事，默认关闭 | 否 |
| WATCHA_PUBLISH_POST | 自动发讨论帖，默认关闭，需要文案接口；发帖没有风控问题，可以单独打开 | 否 |
| SIEMENS_USERNAME | 西门子工业支持中心账号，用户名、手机号或邮箱 | 否 |
| SIEMENS_PASSWORD | 西门子工业支持中心密码 | 否 |
| SIEMENS_DAILY_TASKS | 西门子每日任务，默认开启；设为 `false` 时只签到 | 否 |
| SIEMENS_STORY_POST | 西门子故事帖，默认开启；设为 `false` 时跳过故事帖，其余任务照常 | 否 |
| SIEMENS_ENABLED | 西门子签到总开关，默认开启；设为 `false` 时整个西门子任务都不执行 | 否 |
| AI_BASE_URL | 文案接口地址，OpenAI 兼容，填到版本前缀，例如 `https://api.openai.com/v1` | 否 |
| AI_API_KEY | 文案接口密钥 | 否 |
| AI_MODEL | 文案模型名 | 否 |
| AI_FALLBACK_MODEL | 备用文案模型。主模型超时或报错时，第二次改用它生成；留空则仍用主模型 | 否 |
| AI_NATIVE_SEARCH | 设为 `true` 时产品资料改由文案服务原生联网搜索，默认 `false` 由程序直接查 Bing | 否 |
| EMAIL_USERNAME | 发件人邮箱地址(需要开启 SMTP) | 否 |
| EMAIL_PASSWORD | 发件人邮箱密码(SMTP密码) | 否 |
| EMAIL_TO | 订阅人邮箱地址(收件人)，如需多人订阅使用 `,` 分割，例如: `a@163.com,b@qq.com` | 否 |
| PUSH_PLUS_TOKEN | [Pushplus](http://www.pushplus.plus/) 官网申请，支持微信消息推送 | 否 |
| SERVER_CHAN_TOKEN | [Server酱](https://sct.ftqq.com//) 官网申请，支持微信消息推送 | 否 |

云桥配置两个账号时，第一个账号可以不加 `_1`，第二个账号直接使用 `_2`：

```text
YUNQIAO_USERNAME=第一个账号邮箱
YUNQIAO_PASSWORD=第一个账号密码
YUNQIAO_USERNAME_2=第二个账号邮箱
YUNQIAO_PASSWORD_2=第二个账号密码
```

也可以使用 `_1`、`_2` 的完整编号方式。无序号配置与 `_1` 同时存在时，账号 1 使用 `_1` 配置。

3. 仓库 -> Actions，检查 Workflows 并启用。

## 私有化部署

适合希望在本地服务器/NAS 上长期运行，不依赖 GitHub Actions 的场景。

### 方式一：Docker Compose（推荐）

#### 1) 准备环境

- 已安装 `Docker` 和 `Docker Compose`
- 本地已拉取项目代码

#### 2) 配置环境变量

复制示例配置并填写你自己的账号信息:

```bash
cp .env.example .env
```

`.env` 变量说明:

| Name | Value | Required |
|---|---|---|
| MODB_USERNAME | 墨天轮用户名 | 否 |
| MODB_PASSWORD | 墨天轮密码 | 否 |
| TIDB_LOGIN_MODE | `password`（默认）或 `cookie` | 否 |
| TIDB_USERNAME | 账号密码模式的 TiDB 社区用户名 | 否 |
| TIDB_PASSWORD | 账号密码模式的 TiDB 社区密码 | 否 |
| TIDB_COOKIE | Cookie 模式的浏览器 Cookie，需包含 `csrftoken` 和 `ssid` | 否 |
| YH_USERNAME | 永洪社区用户名 | 否 |
| YH_PASSWORD | 永洪社区密码 | 否 |
| YUNQIAO_USERNAME | 云桥单账号邮箱 | 否 |
| YUNQIAO_PASSWORD | 云桥单账号密码 | 否 |
| YUNQIAO_USERNAME_1 | 云桥账号 1 邮箱 | 否 |
| YUNQIAO_PASSWORD_1 | 云桥账号 1 密码 | 否 |
| YUNQIAO_USERNAME_2 | 云桥账号 2 邮箱 | 否 |
| YUNQIAO_PASSWORD_2 | 云桥账号 2 密码 | 否 |
| WATCHA_USERNAME | 观猹账号，邮箱或手机号 | 否 |
| WATCHA_PASSWORD | 观猹密码 | 否 |
| WATCHA_AUTO_REVIEW | 在签到通知里列出当日推荐产品和体验入口，默认开启；自动猹评打开后不再推送 | 否 |
| WATCHA_PUBLISH_REVIEW | 自动写猹评并提交（含偶发回评），默认关闭。打开后每天最多 1 条，形状按站点真实分布抽样 | 否 |
| WATCHA_BROWSER | 用真浏览器登录并提交，解决 TLS 指纹、cookie、埋点三件事，默认关闭 | 否 |
| WATCHA_PUBLISH_POST | 自动发讨论帖，默认关闭，需要文案接口；发帖没有风控问题，可以单独打开 | 否 |
| SIEMENS_USERNAME | 西门子工业支持中心账号，用户名、手机号或邮箱 | 否 |
| SIEMENS_PASSWORD | 西门子工业支持中心密码 | 否 |
| SIEMENS_DAILY_TASKS | 西门子每日任务，默认开启；设为 `false` 时只签到 | 否 |
| SIEMENS_STORY_POST | 西门子故事帖，默认开启；设为 `false` 时跳过故事帖，其余任务照常 | 否 |
| SIEMENS_ENABLED | 西门子签到总开关，默认开启；设为 `false` 时整个西门子任务都不执行 | 否 |
| AI_BASE_URL | 文案接口地址，OpenAI 兼容，填到版本前缀，例如 `https://api.openai.com/v1` | 否 |
| AI_API_KEY | 文案接口密钥 | 否 |
| AI_MODEL | 文案模型名 | 否 |
| AI_FALLBACK_MODEL | 备用文案模型。主模型超时或报错时，第二次改用它生成；留空则仍用主模型 | 否 |
| AI_NATIVE_SEARCH | 设为 `true` 时产品资料改由文案服务原生联网搜索，默认 `false` 由程序直接查 Bing | 否 |
| EMAIL_USERNAME | 发件人邮箱地址(需开启 SMTP) | 否 |
| EMAIL_PASSWORD | 发件人邮箱密码(SMTP密码) | 否 |
| EMAIL_TO | 收件人邮箱，多个用 `,` 分割 | 否 |
| PUSH_PLUS_TOKEN | Pushplus token | 否 |
| SERVER_CHAN_TOKEN | Server酱 token | 否 |

说明:

- 不需要某个站点时，留空对应账号密码即可。
- 墨天轮登录成功后会复用会话：登录响应里的 `Authorization` 是一个 7 天有效期的 JWT，程序把它存到 `/app/state/modb-session-<账号哈希>.json`（权限 600），之后每次运行先用它读一次用户信息，有效就直接签到，**只有凭证过期或被服务端拒绝才重新用账号密码登录**。删除该文件可以强制重新登录。凭证是等价于密码的东西，不要外传，也不要让同一个账号的凭证在多个环境间流转。
- TiDB 默认使用账号密码。`TIDB_LOGIN_MODE=cookie` 时改为使用 `TIDB_COOKIE`。两种模式只读取自己需要的配置。
- 云桥只有一个账号时可直接使用 `YUNQIAO_USERNAME` / `YUNQIAO_PASSWORD`，无需添加 `_1`。
- 云桥有多个账号时，第一个账号仍可使用无序号变量，第二个账号直接使用 `YUNQIAO_USERNAME_2` / `YUNQIAO_PASSWORD_2`，后续账号继续使用 `_3`、`_4`。
- 也可以从 `_1` 开始完整编号；无序号和 `_1` 同时配置时，以 `_1` 为准。
- 西门子工业支持中心使用 `SIEMENS_USERNAME` / `SIEMENS_PASSWORD` 登录「我的支持中心」并完成每日签到。账号可以填用户名、手机号或邮箱。未配置则跳过。账号如果开启了短信二次验证，自动登录无法完成，通知里会写明原因。`SIEMENS_ENABLED=false` 是总开关，整个西门子任务（签到、抽奖、每日任务、领奖）都不执行，通知里也不再出现这一组，适合临时停掉账号但保留配置。
- 西门子每日任务的文案优先用外部文案接口（`AI_BASE_URL` / `AI_API_KEY` / `AI_MODEL` 三项通用配置），调用失败再退回商城「西游 AI 助手」，都不可用时用内置文案。商城 AI 走的是另一套 SSO，不少账号已要求短信二次验证或图形验证码，签到正常但 AI 拿不到 token，所以外部接口是主路径。**主模型调用失败（含超时）会自动改用 `AI_FALLBACK_MODEL` 再试一次**，补库和单条生成两条链路都支持；连续 2 次主备都失败就熔断本轮，后续任务不再等超时。西门子这边建议用响应快的模型：生成内容时单批 5 条、主模型超时 240 秒、备用模型 90 秒，推理模型记得给足 `max_tokens`，否则正文可能为空。
- 提问、探讨帖、故事帖三类内容的标题由站点全局查重，重复会直接拒收，因此在 `/app/state/siemens-content-bank.json` 维护预制内容库（纯 JSON 文件，每条含 kind/title/body/status/addedAt/usedAt，status 为 ready、used 或 rejected）：库完全为空时先补到 8 条，之后跌破 5 条再补齐到 10 条；三个库共享 5 分钟补库预算，先到先用完即停。发布时逐条取用并标记已用，被站点判重复的条目标记作废，永不复用。库为空时现场生成一条应急。**补库在所有每日任务跑完之后才执行**：它要批量生成十几条内容、会在几分钟内占满文案网关，排在前面会把回帖、评论、回答这些必须现场生成的任务挤到超时。补库没补完不影响当天任务，库存跨天累积，下次运行继续补。
- 多个容器同时跑时会按账号算出不同序号，错开挑选要收藏的问答、要评论的帖子和最佳答案，避免所有账号每天操作同一条内容。收藏问答和收藏帖子会跳过自己已收藏的，评论和回帖会跳过自己发的及已经评论过的帖子。
- 西门子每日任务默认一起执行：签到后读取任务列表，把还没完成的收藏问答、收藏帖子、找答案回答、找答案提问、评论最佳答案、论坛回帖、评论帖子、论坛发帖、论坛发故事帖逐项做掉。`SIEMENS_DAILY_TASKS=false` 可以只签到不执行这些动作。故事帖要站点审核，提交当天不一定计入任务，只有提交成功才按「待审核」处理，失败会照实写进未完成。`SIEMENS_STORY_POST=false` 只跳过故事帖这一项，其余每日任务照常执行，通知里会注明「故事帖已按配置关闭」而不是算成未完成。短时间反复登录会触发风控，返回「登录太频繁」或「账号被临时锁定」，这种情况下等一段时间再跑。
- 西门子登录会复用会话：成功登录后把 cookie 存到 `/app/state/siemens-session-<账号哈希>.txt`，下次运行先试这个会话，只有会话过期才重新走密码登录，避免每天密码登录把账号打到要图形验证码。删掉该文件即可强制重新登录。
- 西门子签到后会检查抽奖次数，`GetCoinAwardCount` 大于 0 就自动抽西币，`GetBigAwardCount` 大于 0 就抽实物奖，中奖结果写进通知。抽奖机会由签到发放，`NextSignGiveCoinAwardCount=true` 表示下次签到还会给，所以每天都检查一次能避免机会堆积浪费。
- 除了签到和抽奖，**论坛发帖、回帖、找答案活动这类奖励不发到余额里，而是发一条「获奖通知」站内消息，要手动点「领取获奖西币 N 枚」才入账，60 天内不领会作废**。程序在每日任务跑完后会扫一遍消息中心（最多 8 页列表，实测账号两个多月积到 6 页），只看类型为「获奖通知」的消息，打开详情页发现 `PickupCoin.aspx?id=xxx` 链接就提交领取，领到的金额写进通知「消息奖励：领取 N 笔，西币 X」。领取接口对同一笔奖励幂等，重复提交回「您已领取过该奖励」，所以不需要精确判断领没领过：已确认处理过的通知 ID 记在 `state/siemens-coin-notices-<账号哈希>.txt`，以后不再重复抓详情页；只有真领到、或站点明确说已领过才记账，网络异常不记账，留到下次重试（奖励有效期 60 天，漏一次不影响）。没有领取链接的通知（「找答案快侠」「最佳答案贺信」「西币已到账」等）也一并标记，避免天天重复抓。单次最多打开 100 条详情（首次接管账号会跑满，之后每天只处理新增的几条）；领取失败会在通知里写「N 笔领取未成功，稍后会重试」，不影响签到结果。
- 西门子签到弹层底部还有「7 天挑战赛」每日答题（`/Event/7DaysChallenge/Question.aspx`），规则是每天答一题、连续 7 天一个周期，中断就从第一天重来；第 1、4、7 天答完可领奖（第 1 天是电子资料、第 7 天抽西币）。**站点把题库和正确答案一起下发**：`type=getquestion` 直接返回题目和 `TrueAnswer`，程序取题后按答案提交，无需猜题。答题属于每日任务的一部分，`SIEMENS_DAILY_TASKS=false` 时只签到不答题。答题接口不限制当天重复提交，但**重复提交不会推进进度**（实测同一账号连答两次仍停在第二天），所以「今天答过没有」由本地记录 `state/siemens-challenge-<账号哈希>.txt` 决定，避免多容器把站点当刷分入口。页面上的「点击领奖」按钮只反映**今天是第几天**（第 1/4/7 天显示），不反映奖有没有领过——实测已领过奖的账号按钮常年亮着，所以程序只按提交返回的 `IsCanOneDayPrize` / `IsCanSevenDayPrize` 决定是否领奖。通知里写成「7 天挑战：答题正确，已领答题奖励」。
- 西门子「一路上有你·25 周年庆」的分享抽奖（`/event/Siemens25Anniversary/index.aspx`）每人限一次，奖品含 25 西币这类直接到账的奖励。程序先 `type=getstatus` 查 `AlreadyDrawn`，没抽过才 `type=shareanddraw` 抽一次，抽中结果写进通知「25 周年抽奖：恭喜您抽中…」。活动有固定结束时间，页面出现「活动已结束」时直接跳过。
- 西门子的通知文案用自然句而不是「标签：值」的平铺，签到天数和西币合成一行（例如「签到成功，连续 5 天，西币 153（+9）」），因为余额要等每日任务跑完才准确，这一行是最后补插到最前面的；未完成项写成「还有 2 项未完成：找答案提问、收藏问答」。
- 站点任务列表里还有一类 `TaskType=2` 的**有奖任务**（完善注册信息 15 西币、绑定手机号/邮箱各 10 西币、订阅电子杂志 10 西币、加入西推官），程序**不自动执行**：完善注册信息要填公司类型、职务、工作年限、省市等真实个人资料，绑定手机/邮箱要走短信或邮件验证码，订阅杂志要留一个真实收件邮箱——这些都会产生编造数据或不可撤销的真实资料变更。订阅杂志在技术上可自动化（`AjaxAPI/SubscribeSubmitAjax.ashx` 提交 `email` + `userID` 即可），但需要一个真实可用邮箱，故留作手动。这类任务的西币是**完成即自动到账**（`CanGetCoin` 只是标称值），不需要额外领奖动作；接口里的 `TotalCoin`/`RemainTotalCoin` 是历史统计字段，前端页面从不展示，`ChestOneState`～`ChestSixState`、`LunPanSmallState`/`LunPanBigState` 是已结束的 25 周年宝箱转盘留下的，同样无人消费，均无需处理。
- 西门子每日任务内置风控保护：同一账号当天执行过就不再重复跑，避免多个容器或 Actions 同时配置导致重复发帖被判灌水；论坛类操作（发帖、故事帖、回帖、评论）和找答案类操作（提问、回答、评论最佳答案）各自保持至少 2 分钟间隔；连续 2 项失败会自动中止剩余任务，避免在站点风控期间继续请求，中止时当天不写「已执行」标记，方便稍后重试，通知里会列出未完成项。单条文案生成超时为 100 秒，接口熔断后不再重试也不再翻查候选。
- 多个西门子账号容器要**错开启动时间**：`docker-compose.yml` 里三个服务的 `CRON_SCHEDULE` 现在是 9:11、9:21、9:31。原先只差 1-3 分钟，三个容器会同时打同一个文案网关，把单条生成推到超时边缘，回帖、评论、回答这类必须贴合原文的任务成批跳过。整轮运行（含补库）约 10-15 分钟，间隔 10 分钟足够避开。
- 西门子每日任务在生成文案前会先读站点上的真实上下文：找答案回答会带上问题标题和问题描述，评论最佳答案会带上最佳答案原文（取自问答列表接口的 `A_Content`），论坛回帖和评论帖子会带上原帖标题和正文摘要（各截 600 字）。回帖和评论帖子还会**按帖子主题挑**：站点的「最新发帖」里混着谈天说地、活动报名、版务公告，这类帖没有技术内容，对着它们写出来的回复必然是套话，所以会在候选里逐篇抓详情页判断主题（看是否含型号、报警码、`PROFINET`/`组态`/`掉站` 这类技术线索），命中才回；最多检查 4 篇，一篇都不合格就当天空着这一项并在通知里列为未完成。回帖、评论最佳答案、找答案回答这三类**必须贴合原帖**：取不到原帖内容时直接跳过，不写通用文案；生成后还会校验一次相关性（原文里的型号、报警码、参数名等至少要命中一个，纯中文叙述则看字符重合度），跑题的内容会重写一次，仍跑题就跳过。宁可当天少做一项，也不在别人帖子下回一段与主题无关的话。发帖、故事帖、提问这些自己发起的内容仍保留内置兜底文案，AI 不可用时照常执行。
- 多个西门子账号可以互相当托，形成「A 提问 → B 回答 → A 采纳 → C 评论」的闭环：站点只允许提问者采纳最佳答案，单账号自己转不起来。轮次记录放在共享目录 `/app/shared/siemens-pipeline.json`（对应宿主机 `state/shared`，三个容器挂同一个目录），每个容器运行时会登记账号、采纳兄弟账号的回答、回答兄弟账号的提问、评论兄弟账号产出的最佳答案。只采纳和评论兄弟账号的内容，不会替自己采纳站上陌生人的回答。
- 互评是隔天一次，而且是**三个账号在同一天一起评**：所有账号共用一份日历，以轮次文件里最早的互评日期为锚点，每 2 天开口一次（`SIBLING_COMMENT_INTERVAL_DAYS`），休息日三个都退回站上随机找已解决问答评论，每日任务照常完成。这样才不会漂移成「A/C 今天能评、B 明天才能评」——各自独立算间隔的话，每天只有一两个账号能动，剩下的最佳答案一直排不上队。开口日里每个账号仍只评一条，同一天不会重复提交；如果某个账号自己昨天刚评过，它那天会再等一天，但不影响另外两个。采纳动作不受此限制。
- 西门子新注册账号有「当日主贴数量不能超过 2 个」的限制，被挡下时通知会写成「受站点每日主贴上限限制，明天会重试」，既不算成功也不触发风控熔断。收藏问答和收藏帖子提交时必须带标签（`tagSave` 填标签名、`tagSaveType=0`），不带标签站点只会原样返回表单、既不报错也不生效；已经收藏过的会跳过，避免每天重复收藏同一条。评论帖子会跳过自己和已经评论过的帖子，站点对重复评论只返回 `false`。
- 故事帖由站点人工审核，结果通过站内消息「故事审核通知」下发，常见结论是「非原创内容」驳回，或者「被转换为普通帖发布到谈天说地」。程序每次运行会读这些通知，把被驳回的标题在内容库里标记作废（不再复用）。**这些结果不进日常通知**：AI 生成的故事基本天天被拒，每天写一行「N 篇未过审」只会变成噪音，程序自己已经处理好了。只有连续 3 天全部被拒时才提醒一次「可考虑设 `SIEMENS_STORY_POST=false`」，计数记在 `state/siemens-story-streak-<账号哈希>.txt`，任一篇放行或降级就清零。单次最多读 3 条审核通知，积压的历史通知标记已处理后跳过；处理过的通知 ID 记在 `state/siemens-story-reviews-<账号哈希>.txt`。因为审核是异步的，当天提交的故事帖第二天才能看到结论，成功提交当天仍按「待审核」处理。
- 故事帖的生成提示词走「技术复盘」路线而不是「现场故事」：标题固定为 `【分享】`+具体技术点，正文按「现象 → 排查顺序 → 定位依据 → 结论」写，要求带报警代码、诊断缓冲区原文或测量数值，型号和参数名要符合西门子真实产品体系。站点对故事帖是人工审核，之前那种「记一次夜班抢修」式的纯叙事会被判「非原创内容」——站上通过审核的同类帖子基本都是 `【分享】V90迁移S200注意事项` 这类具体技术内容。
- 跟日期有关的判断（每日任务当天去重标记、内容库的入库和使用时间）一律显式按北京时间 `Asia/Shanghai` 计算，不看机器默认时区，因此容器（`TZ=Asia/Shanghai`）和 GitHub Actions（runner 默认 UTC）对「今天」的判断一致，同一账号两边跑不会因为差 8 小时而互相不认去重标记。
- 会话复用：登录响应里的 `access_token` 只有 15 分钟有效期，`refresh_token` 有 30 天。程序把两个都存进 `state/watcha-session.json`，每天先用 refresh_token 静默换新令牌，**只有在 refresh 也失效时才真的用密码登录**。登录是唯一会把密码送到服务端、也最容易被 449 验证码拦下的动作，所以能省就省。浏览器模式下还会把令牌灌进 localStorage，并持久化浏览器资料目录 `state/watcha-browser`，cookie 和历史都留着，看起来就是同一台电脑同一个浏览器。
- 观猹使用任务中心的每日签到。`WATCHA_USERNAME` 填写登录用的邮箱或手机号。猹评和帖子由两个独立开关控制：
  - `WATCHA_AUTO_REVIEW=true`（默认）：在通知里列出当日推荐产品及产品页链接，由本人体验后手写猹评。**只提醒，不自动提交**——站点规范禁止猹评通篇由 AI 生成，自动发布会触发折叠甚至封号。此项不需要 AI 配置。
  - `WATCHA_PUBLISH_POST=true`：本周帖子少于 3 篇时每天发 1 篇讨论帖，需要 `AI_BASE_URL`、`AI_API_KEY`、`AI_MODEL` 三项齐全。发帖目前没有触发过风控，可以单独打开。

  两个开关互相独立，例如可以 `WATCHA_AUTO_REVIEW=false` + `WATCHA_PUBLISH_POST=true`，只要发帖不要提醒。动作之间会间隔 20 到 90 秒。旧键 `WATCHA_PUBLISH` 作为帖子开关后备值，`WATCHA_REVIEW_REMINDER` 作为提醒开关后备值。
- `WATCHA_PUBLISH_REVIEW=true`：自动写猹评。每天最多 1 条，流程是「挑一个还没评过的推荐产品 → 真打开一次官网采集首页事实 → 按当天抽到的形状成稿 → 提交」，需要 AI 三项配置，默认关闭。
- 就两个开关：`WATCHA_PUBLISH_REVIEW` 管猹评（回评跟着它走），`WATCHA_BROWSER` 管要不要用真浏览器。其余参数都有实测默认值，只有想调的时候才需要写进环境变量：`WATCHA_REVIEW_SKIP_RATE=0.35`、`WATCHA_REVIEW_DELAY_MINUTES=25`、`WATCHA_REPLY_RATE=0.3`、`WATCHA_REPLY_DELAY_MINUTES=8`、`WATCHA_JITTER_MINUTES=20`、`WATCHA_REVIEW_DRY_RUN=false`。
- 猹评的形状由 `WatchaReviewPlan` 决定，参数按站点 195 条真实猹评统计出来的分布抽样：跳过率 35%（真人不会天天写）、约 49% 只写一段、字数按 0-60/60-120/120-200/200-300/300-600/600+ 六档 16/14/25/17/16/12% 抽、小标题 1%、引用 1%、加粗 14%、带图 45%（直接用产品封面，不额外上传）、投「不推荐」15%、提交前随机等 0 到 25 分钟。
  之所以这么设计：老实现把「必须两段、100 到 260 字、无图无格式」写死在代码里，20 条猹评每条都落在真实分布最窄的 4% 格子里——文本相似度只有 0.08，但形状一模一样，打开账号主页一眼就看出来。真正暴露的从来不是措辞，是形状和节奏。
- 频率与熔断（这两条才是「不被封」的关键）：默认 `WATCHA_REVIEW_SKIP_RATE=0.4`，约每周 4 条；`WATCHA_REVIEW_OFF_QUEST_RATE=0.15` 的日子故意写一个非推荐产品（放弃当天奖励），避免「永远只写推荐位」这条规律；程序把发过的猹评记在 `state/watcha-review-ledger.json`，每天回查，**少一条就记一次违规，累计两次自动停写猹评**，通知里会写明停到哪天，人工确认后清空台账即可恢复。
- 开头约束：提示词明确禁止用「上周/前两天/前几天/那天/上个月/前阵子/最近」起句，改成四种起手之一（直接给结论、直接说功能、和以前用的对比、先说遇到的问题）。原因是账号历史上 20 多条猹评几乎全是「时间状语 + 我」，而真人样本的开口五花八门。
- 账号人格：`WATCHA_REVIEW_PERSONA`（默认 `medium`）。真人的写作风格差异极大——实测 12 个真实用户里，有人 33 字、97% 单段、从不配图，有人 774 字、条条配图、几乎不写单段。按「全站混合分布」抽样会让账号每天换一种风格，反而不像人，所以改成固定人格：`short`（20-120 字、95% 单段、2% 配图）/ `medium`（70-330 字、55% 单段、12% 配图）/ `long`（350-900 字、多数 2-5 段、70% 配图）。
- 配图三个来源，按顺序尝试：**试用截图**（`WATCHA_REVIEW_SHOT=true` + `WATCHA_BROWSER=true`；截图直接传进观猹自己的对象存储，不需要任何外部图床）→ **产品官网的 og:image**（试用采集时顺手取回，经 `/api/v2/infra/external-resources/image` 镜像成站内图）→ **平台封面**。三档都失败就不配图，不会影响发布。
  站内直传走的是前端同一条链路：`GET /api/v2/infra/request-upload-image?postfix=png` 拿凭证（bucket/key/policy/签名），再把这些字段连同文件 multipart POST 到凭证里的 TOS 地址，返回 204 后地址是 `upload_url + key`。落地形态和真实用户截图完全一样（`prod/user/uploads/...`）。`WATCHA_SHOT_HOST` 只是站内直传失败时的备选外部图床。
- 试运行：`WATCHA_REVIEW_DRY_RUN=true` 会把完整流程跑到提交前，成稿和 `vote_value` 只打进日志，方便先看几天文风再决定要不要开提交。
- 偶发回评：自动猹评打开后跟着生效，按 `WATCHA_REPLY_RATE`（默认 0.3）的概率，在**今天评过的产品**下面挑一条别人的猹评接一句。目标条件：近 10 天、正文 40 到 800 字、回复数不超过 20、我没有回过。内容校验要求回复和原评至少共享一个两字片段，且不能出现「同意」「学习了」「感谢分享」，也不能描述页面（首页、大字、标题、宣传语），空话和跑题会被打回重写。
- 回评接口和猹评不一样：`POST /api/v2/reviews/{id}/replies` 的 `content` 收的是 doc 的 **JSON 字符串**，不是对象。
- 日常运维（每天看什么、怎么停、怎么恢复停写）见 [WATCHA-OPS.md](WATCHA-OPS.md)。
- 请求头和行为层面的指纹审计见 [WATCHA-FINGERPRINT.md](WATCHA-FINGERPRINT.md)：已处理 Referer、启动时间、领奖间隔、`sec-ch-ua`、GET 头裁剪；TLS 指纹、cookie、埋点这三项是纯 HTTP 客户端改不掉的。
- 注意：观猹[社区内容规范](https://docs.watcha.cn/post/review-rule/)明确禁止猹评通篇由 AI 自动生成，也禁止长期频繁发布近似内容。这个开关做的是「真打开一次 + 形状随机化」，不是免责：被折叠或封号的风险由使用者自己承担。
- 观猹生成文案前会先联网核实产品官网和主要功能，再以用过后的第一人称体验口吻生成。默认由程序用「产品名 官网」「产品名 分类 功能」直接查询 Bing 公开搜索，每个查询失败会重试 1 次，取得摘要和来源后交给 Chat Completions 生成文案，不依赖文案服务的联网能力。文案服务能真正执行原生 `web_search` 时，可设 `AI_NATIVE_SEARCH=true` 改由 Responses 检索；若服务只返回 `websearch` 或 `web_search` 函数请求，仍由程序执行 Bing 查询。`AI_SEARCH_URL` 可替换公开搜索地址，一般不用填。没有搜到资料或来源时跳过发布。文案生成单次最多等待 180 秒，最多尝试 2 次；第一次超时、报错或返回的不是合法 JSON 时，第二次改用 `AI_FALLBACK_MODEL`，只是内容不合格时仍用主模型重写。请求会带 `response_format=json_object` 要求服务返回合法 JSON，服务不认这个参数时自动去掉重试；模型偶尔在正文里用英文双引号或输出被截断时，程序会先尝试修复再判定失败。同一产品在本次任务中复用检索资料，文案重写不会重复搜索。搜索资料用于补充产品事实，生成的体验口吻不代表程序实际操作过该产品。
- 通知方式可选，邮箱/PushPlus/Server酱可按需配置其一或多个。

两个云桥账号的 `.env` 示例：

```env
YUNQIAO_USERNAME=first@example.com
YUNQIAO_PASSWORD=first-password
YUNQIAO_USERNAME_2=second@example.com
YUNQIAO_PASSWORD_2=second-password
```

#### 3) 启动服务

```bash
docker compose up -d --build
```

默认使用北京时间时区 `Asia/Shanghai`，并按 `docker-compose.yml` 中的 `CRON_SCHEDULE` 定时执行。

查看日志:

```bash
docker compose logs -f auto-sign-in
```

停止服务:

```bash
docker compose down
```

#### 4) 多账号运行（可选）

项目中的 `docker-compose.yml` 已提供多服务示例:

- `auto-sign-in` 使用 `.env`
- `auto-sign-in-xxx` 使用 `.env.xxx`

你可以复制一份新的环境变量文件（例如 `.env.xxx`），填写另一组账号，实现多账号定时签到。

### 方式二：本地直接运行 JAR（可选）

如果你不想使用 Docker，也可以自行打包后通过系统定时任务运行 JAR。构建需要 JDK 17+，运行需要 Java 17+，并可通过 `crontab` 或 `systemd timer` 做定时触发。

### 只读看板（可选）

签到结果除了推送，还会写一份到 `state/run-history/`（可用 `RUN_HISTORY_ENABLED=false` 关闭），
运行日志同时落一份到 `state/logs/<日期>.log`。
容器重建会清空 `docker logs`，但这两份文件在挂载的 `state/` 里，不会丢。
基于它们和 state 目录里的其它状态，可以起一个只读看板查看今日是否已跑、内容库水位、互评闭环、最近一次结果和运行日志：

```bash
cp .env.dashboard.example .env.dashboard   # 填好 DASHBOARD_USER 和 DASHBOARD_PASSWORD
docker compose up -d auto-sign-in-dashboard
# 本机：http://127.0.0.1:8090/
# 局域网：http://<本机 IP>:8090/
```

口令文件缺失时看板不会崩溃或反复重启，而是在 8090 上给出配置提示页，`/health` 返回 503。

看板复用签到镜像，不新增依赖，只读挂载 `state/` 和三份 env 文件。
页面是多页应用，地址可以直接分享或收藏：

| 路径 | 内容 |
|---|---|
| `/` | 概览：今日执行、异常、风控、实例摘要、互评闭环 |
| `/instances` | 实例列表 |
| `/instances/<实例>` | 实例详情：西门子标记、内容库、观猹、运行历史、日志、配置 |
| `/runs` | 运行历史，支持「只看有异常」和按实例筛选、分页 |
| `/runs/<实例>/<记录>` | 单次运行的完整结果，与推送内容一致 |
| `/config` | 多实例配置并排对比 |

- 访问需要账号口令，口令只从 `.env.dashboard` 读取。
- 没建 `.env.dashboard` 也能跑：看板会在 8090 给出配置提示页，接口一律 503，
  签到容器完全不受影响；建好文件后执行 `docker compose up -d auto-sign-in-dashboard` 即可。
- 默认绑所有网卡，同一局域网可以直接用手机或别的电脑打开。
- 只在内网使用。若这台机器有公网 IP，务必改回 `127.0.0.1:8090:8090` 再用 SSH 隧道访问。
- 页面只显示「已配置/未配置」，不会回显任何密码、Cookie 或令牌；接口有单元测试守住这条。
- 全部只读，不提供触发签到、修改开关或清台账的操作。

接口按页面拆开，首屏只需一份小 JSON：

| 接口 | 用途 |
|---|---|
| `GET /api/overview` | 概览 |
| `GET /api/instances` | 实例列表 |
| `GET /api/instances/<name>?history=&logs=&tail=` | 实例详情 |
| `GET /api/runs?offset=&limit=&onlyFailed=&instance=` | 运行历史 |
| `GET /api/runs/<instance>/<file>` | 单次运行 |
| `GET /api/config` | 配置对比 |
| `GET /api/pipeline` | 互评闭环 |

## 使用问题

1. 如果不需要某一个，比如不需要永洪，不配置`YH_USERNAME`和`YH_PASSWORD`即可。
