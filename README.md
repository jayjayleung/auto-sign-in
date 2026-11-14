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
| AI_BASE_URL | 文案接口地址，填到版本前缀，例如 `https://api.openai.com/v1` | 否 |
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
| AI_BASE_URL | 文案接口地址，填到版本前缀，例如 `https://api.openai.com/v1` | 否 |
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
- 西门子每日任务默认一起执行：签到后读取任务列表，把还没完成的收藏问答、收藏帖子、找答案回答、找答案提问、评论最佳答案、论坛回帖、评论帖子、论坛发帖、论坛发故事帖逐项做掉。帖子、回答和评论的文案由商城「西游 AI 助手」生成，走的是同一套西门子账号，所以内容来自官方知识库而不是随机编造；AI 助手登录要图形验证码或超时改用时，会自动改用内置文案，不会因此整轮中止。`SIEMENS_DAILY_TASKS=false` 可以只签到不执行这些动作。故事帖要站点审核，提交当天不一定计入任务，只有提交成功才按「待审核」处理，失败会照实写进未完成。短时间反复登录会触发风控，返回「登录太频繁」或「账号被临时锁定」，这种情况下等一段时间再跑。
- 西门子登录会复用会话：成功登录后把 cookie 存到 `/app/state/siemens-session-<账号哈希>.txt`，下次运行先试这个会话，只有会话过期才重新走密码登录，避免每天密码登录把账号打到要图形验证码。删掉该文件即可强制重新登录。
- 西门子签到后会检查抽奖次数，`GetCoinAwardCount` 大于 0 就自动抽西币，`GetBigAwardCount` 大于 0 就抽实物奖，中奖结果写进通知。抽奖机会由签到发放，`NextSignGiveCoinAwardCount=true` 表示下次签到还会给，所以每天都检查一次能避免机会堆积浪费。
- 西门子每日任务内置风控保护：同一账号当天执行过就不再重复跑，避免多个容器或 Actions 同时配置导致重复发帖被判灌水；论坛类操作（发帖、故事帖、回帖、评论）和找答案类操作（提问、回答、评论最佳答案）各自保持至少 2 分钟间隔；连续 2 项失败会自动中止剩余任务，避免在站点风控期间继续请求，中止时当天不写「已执行」标记，方便稍后重试，通知里会列出未完成项。AI 生成单次超时放宽到 60 秒。
- 西门子新注册账号有「当日主贴数量不能超过 2 个」的限制，被挡下时通知会写成「受站点每日主贴上限限制，明天会重试」，既不算成功也不触发风控熔断。收藏问答只对「已解决」的问题生效，代码取的是「新解决的问题」列表。
- 观猹使用任务中心的每日签到。`WATCHA_USERNAME` 填写登录用的邮箱或手机号。`WATCHA_PUBLISH` 设为 `true`，并且文案接口 `AI_BASE_URL`、`AI_API_KEY`、`AI_MODEL` 三项都填写后，每天再发 1 篇当日推荐产品的猹评；本周帖子少于 3 篇时再发 1 篇。两次发布之间会间隔 20 到 90 秒。开关留空或文案接口留空则只签到。
- 观猹生成文案前会先联网核实产品官网和主要功能，再以用过后的第一人称体验口吻生成。默认由程序用「产品名 官网」「产品名 分类 功能」直接查询 Bing 公开搜索，每个查询失败会重试 1 次，取得摘要和来源后交给 Chat Completions 生成文案，不依赖文案服务的联网能力。文案服务能真正执行原生 `web_search` 时，可设 `AI_NATIVE_SEARCH=true` 改由 Responses 检索；若服务只返回 `websearch` 或 `web_search` 函数请求，仍由程序执行 Bing 查询。`AI_SEARCH_URL` 可替换公开搜索地址，一般不用填。没有搜到资料或来源时跳过发布。文案生成单次最多等待 180 秒，最多尝试 2 次；第一次超时或报错时，第二次改用 `AI_FALLBACK_MODEL`，只是没通过校验时仍用主模型重写。同一产品在本次任务中复用检索资料，文案重写不会重复搜索。搜索资料用于补充产品事实，生成的体验口吻不代表程序实际操作过该产品。
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
