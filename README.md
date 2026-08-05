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
- **[云桥积分中心](https://api.quya.org/custom/93bbf0afef76f203)** 多账号签到


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
| TIDB_USERNAME | tidb用户名 | 是 |
| TIDB_PASSWORD | tidb密码 | 是 |
| YH_USERNAME | 永洪社区用户名 | 是 |
| YH_PASSWORD | 永洪社区密码 | 是 |
| YUNQIAO_USERNAME | 云桥单账号邮箱 | 否 |
| YUNQIAO_PASSWORD | 云桥单账号密码 | 否 |
| YUNQIAO_USERNAME_1 | 云桥账号 1 邮箱 | 否 |
| YUNQIAO_PASSWORD_1 | 云桥账号 1 密码 | 否 |
| YUNQIAO_USERNAME_2 | 云桥账号 2 邮箱 | 否 |
| YUNQIAO_PASSWORD_2 | 云桥账号 2 密码 | 否 |
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
| TIDB_USERNAME | TiDB 社区用户名 | 否 |
| TIDB_PASSWORD | TiDB 社区密码 | 否 |
| YH_USERNAME | 永洪社区用户名 | 否 |
| YH_PASSWORD | 永洪社区密码 | 否 |
| YUNQIAO_USERNAME | 云桥单账号邮箱 | 否 |
| YUNQIAO_PASSWORD | 云桥单账号密码 | 否 |
| YUNQIAO_USERNAME_1 | 云桥账号 1 邮箱 | 否 |
| YUNQIAO_PASSWORD_1 | 云桥账号 1 密码 | 否 |
| YUNQIAO_USERNAME_2 | 云桥账号 2 邮箱 | 否 |
| YUNQIAO_PASSWORD_2 | 云桥账号 2 密码 | 否 |
| EMAIL_USERNAME | 发件人邮箱地址(需开启 SMTP) | 否 |
| EMAIL_PASSWORD | 发件人邮箱密码(SMTP密码) | 否 |
| EMAIL_TO | 收件人邮箱，多个用 `,` 分割 | 否 |
| PUSH_PLUS_TOKEN | Pushplus token | 否 |
| SERVER_CHAN_TOKEN | Server酱 token | 否 |

说明:

- 不需要某个站点时，留空对应账号密码即可。
- 云桥只有一个账号时可直接使用 `YUNQIAO_USERNAME` / `YUNQIAO_PASSWORD`，无需添加 `_1`。
- 云桥有多个账号时，第一个账号仍可使用无序号变量，第二个账号直接使用 `YUNQIAO_USERNAME_2` / `YUNQIAO_PASSWORD_2`，后续账号继续使用 `_3`、`_4`。
- 也可以从 `_1` 开始完整编号；无序号和 `_1` 同时配置时，以 `_1` 为准。
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

如果你不想使用 Docker，也可以自行打包后通过系统定时任务运行 JAR。建议确保运行环境为 Java 17+，并通过 `crontab` 或 `systemd timer` 做定时触发。

## 使用问题

1. 如果不需要某一个，比如不需要永洪，不配置`YH_USERNAME`和`YH_PASSWORD`即可。
