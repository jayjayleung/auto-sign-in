# 观猹自动化的日常运维

## 每天看什么

```bash
docker logs --tail 60 auto-sign-in | grep 观猹
```

四行足够判断状态：

| 日志 | 含义 |
|---|---|
| `用 refresh_token 换了新令牌，不重新登录` | 会话层正常（正常情况下 30 天才登录一次） |
| `浏览器通道自检：通过（昵称）` | 浏览器层正常 |
| `猹评计划：约 xxx 字/x 段/...` | 当天要发，形状已定 |
| `今日猹评跳过` | 静默日，正常现象（约三成日子） |

出现下面任一行才需要处理：

- `会话已失效，需要重新登录` —— refresh 到期或失效，程序自己会重登；连着几天出现说明密码变了
- `浏览器通道不可用，回退 HTTP` —— Chrome 起不来，签到不受影响，但指纹会退化
- `任务失败：...需要完成验证码` —— 触发 449，停一天再看
- `今日猹评：已自动停写` —— 连续两条猹评消失，**先去人工看账号**

## 出问题怎么停

```bash
# 只停猹评，其它照常
sed -i 's/^WATCHA_PUBLISH_REVIEW=.*/WATCHA_PUBLISH_REVIEW=false/' .env && docker compose up -d
```

## 怎么恢复停写

确认账号没问题后，清空台账即可（下一次运行重建）：

```bash
rm -f state/auto-sign-in/watcha-review-ledger.json
```

## 状态文件在哪

| 路径 | 内容 |
|---|---|
| `state/auto-sign-in/watcha-session.json` | access/refresh 令牌（等价于 cookie，别外传） |
| `state/auto-sign-in/watcha-review-ledger.json` | 发过的猹评、违规次数（熔断依据） |
| `state/auto-sign-in/watcha-browser/` | 浏览器资料目录（cookie、localStorage） |
| `state/auto-sign-in/trials/` | 试用截图（上传成功后即删） |
| `state/auto-sign-in/siemens-*` | 西门子那边的状态，和观猹无关 |

## 参数在哪调

`s .env` 里只有两个开关（`WATCHA_PUBLISH_REVIEW`、`WATCHA_BROWSER`），其余都有实测默认值，需要时再加：

| 键 | 默认 | 作用 |
|---|---|---|
| `WATCHA_REVIEW_PERSONA` | medium | 账号风格：short / medium / long |
| `WATCHA_REVIEW_QUIET_WEEK_RATE` | 0.3 | 整周几乎不写的概率，决定空档频率 |
| `WATCHA_REVIEW_OFF_QUEST_RATE` | 0.15 | 写普通产品而不是推荐位的概率 |
| `WATCHA_REVIEW_DELAY_MINUTES` | 25 | 提交前随机等待上限 |
| `WATCHA_REVIEW_DRY_RUN` | false | 只出草稿不提交 |
| `WATCHA_REVIEW_SHOT` | true | 配图用试用截图（直传站内） |
| `WATCHA_REVIEW_FORCE` | false | 忽略节奏强制发一条（调试用） |

## 已知边界

- 读请求仍走纯 HTTP，TLS 指纹和浏览器不一致（写入已经走 Chrome）
- 登录被 449 拦时不会自动过验证码，当天就放弃，第二天恢复
- 一条猹评从采集到提交大约 1-2 分钟（含 7 秒页面采集 + 文案生成）
