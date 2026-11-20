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
| WATCHA_PUBLISH | 观猹自动发布，`true` 或 `1` 开启，留空或 `false` 则只签到 | 否 |
| SIEMENS_USERNAME | 西门子工业支持中心账号，用户名、手机号或邮箱 | 否 |
| SIEMENS_PASSWORD | 西门子工业支持中心密码 | 否 |
| SIEMENS_DAILY_TASKS | 西门子每日任务，默认开启；设为 `false` 时只签到 | 否 |
| SIEMENS_STORY_POST | 西门子故事帖，默认开启；设为 `false` 时跳过故事帖，其余任务照常 | 否 |
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
| WATCHA_PUBLISH | 观猹自动发布，`true` 或 `1` 开启，留空或 `false` 则只签到 | 否 |
| SIEMENS_USERNAME | 西门子工业支持中心账号，用户名、手机号或邮箱 | 否 |
| SIEMENS_PASSWORD | 西门子工业支持中心密码 | 否 |
| SIEMENS_DAILY_TASKS | 西门子每日任务，默认开启；设为 `false` 时只签到 | 否 |
| SIEMENS_STORY_POST | 西门子故事帖，默认开启；设为 `false` 时跳过故事帖，其余任务照常 | 否 |
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
- TiDB 默认使用账号密码。`TIDB_LOGIN_MODE=cookie` 时改为使用 `TIDB_COOKIE`。两种模式只读取自己需要的配置。
- 云桥只有一个账号时可直接使用 `YUNQIAO_USERNAME` / `YUNQIAO_PASSWORD`，无需添加 `_1`。
- 云桥有多个账号时，第一个账号仍可使用无序号变量，第二个账号直接使用 `YUNQIAO_USERNAME_2` / `YUNQIAO_PASSWORD_2`，后续账号继续使用 `_3`、`_4`。
- 也可以从 `_1` 开始完整编号；无序号和 `_1` 同时配置时，以 `_1` 为准。
- 西门子工业支持中心使用 `SIEMENS_USERNAME` / `SIEMENS_PASSWORD` 登录「我的支持中心」并完成每日签到。账号可以填用户名、手机号或邮箱。未配置则跳过。账号如果开启了短信二次验证，自动登录无法完成，通知里会写明原因。
- 西门子每日任务的文案优先用外部文案接口（`AI_BASE_URL` / `AI_API_KEY` / `AI_MODEL` 三项通用配置），调用失败再退回商城「西游 AI 助手」，都不可用时用内置文案。商城 AI 走的是另一套 SSO，不少账号已要求短信二次验证或图形验证码，签到正常但 AI 拿不到 token，所以外部接口是主路径。**主模型调用失败（含超时）会自动改用 `AI_FALLBACK_MODEL` 再试一次**，补库和单条生成两条链路都支持；连续 2 次主备都失败就熔断本轮，后续任务不再等超时。西门子这边建议用响应快的模型：生成内容时单批 5 条、主模型超时 240 秒、备用模型 90 秒，推理模型记得给足 `max_tokens`，否则正文可能为空。
- 提问、探讨帖、故事帖三类内容的标题由站点全局查重，重复会直接拒收，因此在 `/app/state/siemens-content-bank.json` 维护预制内容库（纯 JSON 文件，每条含 kind/title/body/status/addedAt/usedAt，status 为 ready、used 或 rejected）：库完全为空时先补到 10 条，之后跌破 5 条再补齐到 20 条，逐步累积；三个库共享 10 分钟补库预算，先到先用完即停。发布时逐条取用并标记已用，被站点判重复的条目标记作废，永不复用。库为空时现场生成一条应急。
- 多个容器同时跑时会按账号算出不同序号，错开挑选要收藏的问答、要评论的帖子和最佳答案，避免所有账号每天操作同一条内容。收藏问答和收藏帖子会跳过自己已收藏的，评论和回帖会跳过自己发的及已经评论过的帖子。
- 西门子每日任务默认一起执行：签到后读取任务列表，把还没完成的收藏问答、收藏帖子、找答案回答、找答案提问、评论最佳答案、论坛回帖、评论帖子、论坛发帖、论坛发故事帖逐项做掉。`SIEMENS_DAILY_TASKS=false` 可以只签到不执行这些动作。故事帖要站点审核，提交当天不一定计入任务，只有提交成功才按「待审核」处理，失败会照实写进未完成。`SIEMENS_STORY_POST=false` 只跳过故事帖这一项，其余每日任务照常执行，通知里会注明「故事帖已按配置关闭」而不是算成未完成。短时间反复登录会触发风控，返回「登录太频繁」或「账号被临时锁定」，这种情况下等一段时间再跑。
- 西门子登录会复用会话：成功登录后把 cookie 存到 `/app/state/siemens-session-<账号哈希>.txt`，下次运行先试这个会话，只有会话过期才重新走密码登录，避免每天密码登录把账号打到要图形验证码。删掉该文件即可强制重新登录。
- 西门子签到后会检查抽奖次数，`GetCoinAwardCount` 大于 0 就自动抽西币，`GetBigAwardCount` 大于 0 就抽实物奖，中奖结果写进通知。抽奖机会由签到发放，`NextSignGiveCoinAwardCount=true` 表示下次签到还会给，所以每天都检查一次能避免机会堆积浪费。
- 西门子的通知文案用自然句而不是「标签：值」的平铺，签到天数和西币合成一行（例如「签到成功，连续 5 天，西币 153（+9）」），因为余额要等每日任务跑完才准确，这一行是最后补插到最前面的；未完成项写成「还有 2 项未完成：找答案提问、收藏问答」。
- 西门子每日任务内置风控保护：同一账号当天执行过就不再重复跑，避免多个容器或 Actions 同时配置导致重复发帖被判灌水；论坛类操作（发帖、故事帖、回帖、评论）和找答案类操作（提问、回答、评论最佳答案）各自保持至少 2 分钟间隔；连续 2 项失败会自动中止剩余任务，避免在站点风控期间继续请求，中止时当天不写「已执行」标记，方便稍后重试，通知里会列出未完成项。单条文案生成超时为 100 秒，接口熔断后不再重试也不再翻查候选。
- 多个西门子账号容器要**错开启动时间**：`docker-compose.yml` 里三个服务的 `CRON_SCHEDULE` 现在是 9:11、9:21、9:31。原先只差 1-3 分钟，三个容器会同时打同一个文案网关，把单条生成推到超时边缘，回帖、评论、回答这类必须贴合原文的任务成批跳过。整轮运行（含补库）约 10-15 分钟，间隔 10 分钟足够避开。
- 西门子每日任务在生成文案前会先读站点上的真实上下文：找答案回答会带上问题标题和问题描述，评论最佳答案会带上最佳答案原文（取自问答列表接口的 `A_Content`），论坛回帖和评论帖子会带上原帖标题和正文摘要（各截 600 字）。回帖和评论帖子还会**按帖子主题挑**：站点的「最新发帖」里混着谈天说地、活动报名、版务公告，这类帖没有技术内容，对着它们写出来的回复必然是套话，所以会在候选里逐篇抓详情页判断主题（看是否含型号、报警码、`PROFINET`/`组态`/`掉站` 这类技术线索），命中才回；最多检查 4 篇，一篇都不合格就当天空着这一项并在通知里列为未完成。回帖、评论最佳答案、找答案回答这三类**必须贴合原帖**：取不到原帖内容时直接跳过，不写通用文案；生成后还会校验一次相关性（原文里的型号、报警码、参数名等至少要命中一个，纯中文叙述则看字符重合度），跑题的内容会重写一次，仍跑题就跳过。宁可当天少做一项，也不在别人帖子下回一段与主题无关的话。发帖、故事帖、提问这些自己发起的内容仍保留内置兜底文案，AI 不可用时照常执行。
- 多个西门子账号可以互相当托，形成「A 提问 → B 回答 → A 采纳 → C 评论」的闭环：站点只允许提问者采纳最佳答案，单账号自己转不起来。轮次记录放在共享目录 `/app/shared/siemens-pipeline.json`（对应宿主机 `state/shared`，三个容器挂同一个目录），每个容器运行时会登记账号、采纳兄弟账号的回答、回答兄弟账号的提问、评论兄弟账号产出的最佳答案。只采纳和评论兄弟账号的内容，不会替自己采纳站上陌生人的回答。
- 互评是隔天一次，而且是**三个账号在同一天一起评**：所有账号共用一份日历，以轮次文件里最早的互评日期为锚点，每 2 天开口一次（`SIBLING_COMMENT_INTERVAL_DAYS`），休息日三个都退回站上随机找已解决问答评论，每日任务照常完成。这样才不会漂移成「A/C 今天能评、B 明天才能评」——各自独立算间隔的话，每天只有一两个账号能动，剩下的最佳答案一直排不上队。开口日里每个账号仍只评一条，同一天不会重复提交；如果某个账号自己昨天刚评过，它那天会再等一天，但不影响另外两个。采纳动作不受此限制。
- 西门子新注册账号有「当日主贴数量不能超过 2 个」的限制，被挡下时通知会写成「受站点每日主贴上限限制，明天会重试」，既不算成功也不触发风控熔断。收藏问答和收藏帖子提交时必须带标签（`tagSave` 填标签名、`tagSaveType=0`），不带标签站点只会原样返回表单、既不报错也不生效；已经收藏过的会跳过，避免每天重复收藏同一条。评论帖子会跳过自己和已经评论过的帖子，站点对重复评论只返回 `false`。
- 故事帖由站点人工审核，结果通过站内消息「故事审核通知」下发，常见结论是「非原创内容」驳回，或者「被转换为普通帖发布到谈天说地」。程序每次运行会读这些通知，把被驳回的标题在内容库里标记作废（不再复用）。**这些结果不进日常通知**：AI 生成的故事基本天天被拒，每天写一行「N 篇未过审」只会变成噪音，程序自己已经处理好了。只有连续 3 天全部被拒时才提醒一次「可考虑设 `SIEMENS_STORY_POST=false`」，计数记在 `state/siemens-story-streak-<账号哈希>.txt`，任一篇放行或降级就清零。单次最多读 3 条审核通知，积压的历史通知标记已处理后跳过；处理过的通知 ID 记在 `state/siemens-story-reviews-<账号哈希>.txt`。因为审核是异步的，当天提交的故事帖第二天才能看到结论，成功提交当天仍按「待审核」处理。
- 故事帖的生成提示词走「技术复盘」路线而不是「现场故事」：标题固定为 `【分享】`+具体技术点，正文按「现象 → 排查顺序 → 定位依据 → 结论」写，要求带报警代码、诊断缓冲区原文或测量数值，型号和参数名要符合西门子真实产品体系。站点对故事帖是人工审核，之前那种「记一次夜班抢修」式的纯叙事会被判「非原创内容」——站上通过审核的同类帖子基本都是 `【分享】V90迁移S200注意事项` 这类具体技术内容。
- 跟日期有关的判断（每日任务当天去重标记、内容库的入库和使用时间）一律显式按北京时间 `Asia/Shanghai` 计算，不看机器默认时区，因此容器（`TZ=Asia/Shanghai`）和 GitHub Actions（runner 默认 UTC）对「今天」的判断一致，同一账号两边跑不会因为差 8 小时而互相不认去重标记。
- 观猹使用任务中心的每日签到。`WATCHA_USERNAME` 填写登录用的邮箱或手机号。`WATCHA_PUBLISH` 设为 `true`，并且文案接口 `AI_BASE_URL`、`AI_API_KEY`、`AI_MODEL` 三项都填写后，每天再发 1 篇当日推荐产品的猹评；本周帖子少于 3 篇时再发 1 篇。两次发布之间会间隔 20 到 90 秒。开关留空或文案接口留空则只签到。
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

如果你不想使用 Docker，也可以自行打包后通过系统定时任务运行 JAR。构建需要 JDK 8+，运行需要 Java 8+，并可通过 `crontab` 或 `systemd timer` 做定时触发。

## 使用问题

1. 如果不需要某一个，比如不需要永洪，不配置`YH_USERNAME`和`YH_PASSWORD`即可。
