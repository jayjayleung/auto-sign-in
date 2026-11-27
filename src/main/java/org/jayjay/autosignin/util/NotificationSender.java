package org.jayjay.autosignin.util;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ReUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.extra.mail.MailAccount;
import cn.hutool.extra.mail.MailUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONObject;
import org.jayjay.autosignin.entity.NotificationSection;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 根据各通知渠道的配置发送签到结果，消息格式统一由 MessageRenderer 生成。
 */
public class NotificationSender {

    public String EMAIL_USERNAME = System.getenv("EMAIL_USERNAME");
    public String EMAIL_PASSWORD = System.getenv("EMAIL_PASSWORD");
    public String EMAIL_TO = System.getenv("EMAIL_TO");
    public String PUSH_PLUS_TOKEN = System.getenv("PUSH_PLUS_TOKEN");
    public String SERVER_CHAN_TOKEN = System.getenv("SERVER_CHAN_TOKEN");

    /**
     * 通知标题。默认固定为「签到结果」，有站点失败时追加异常数，
     * 这样锁屏上也能看出今天要不要点开排查。
     */
    String buildTitle(List<NotificationSection> sections) {
        int failed = 0;
        if (sections != null) {
            for (NotificationSection section : sections) {
                if (!section.hasContent()) {
                    continue;
                }
                for (org.jayjay.autosignin.entity.MessageBlock block : section.getBlocks()) {
                    String text = block == null ? "" : block.toPlainText();
                    if (text.contains("失败")) {
                        failed++;
                        break;
                    }
                }
            }
        }
        return failed > 0 ? "签到结果（" + failed + " 个异常）" : "签到结果";
    }


    /**
     * 过滤无有效内容的任务结果，并依次调用已配置的通知渠道。
     */
    public void send(List<NotificationSection> sections) {
        send(sections, null);
    }

    /**
     * 指定标题发送；标题留空时按结果自动生成。
     * 各渠道互不影响：某个渠道抛异常只记日志，其余渠道照发。
     */
    public void send(List<NotificationSection> sections, String title) {
        List<NotificationSection> sendableSections = sections.stream()
                .filter(NotificationSection::hasContent)
                .collect(Collectors.toList());
        if (CollUtil.isEmpty(sendableSections)) {
            System.out.println("[通知] 没有需要发送的签到结果，跳过通知");
            return;
        }
        String subject = StrUtil.isBlank(title) ? buildTitle(sendableSections) : title;
        deliver("邮件", () -> sendEmail(sendableSections, subject));
        deliver("PushPlus", () -> sendPushPlus(sendableSections, subject));
        deliver("Server酱", () -> sendServerChan(sendableSections, subject));
    }

    private void deliver(String channel, Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            System.out.println("[通知] " + channel + " 通知发送失败：" + redact(String.valueOf(e.getMessage())));
        }
    }

    /**
     * 异常信息里可能带请求地址，Server酱的令牌就在地址里。
     * 过短的值不替换，否则会把报错里的普通字母一起打码。
     */
    private String redact(String message) {
        String text = StrUtil.maxLength(message, 200);
        for (String secret : new String[]{SERVER_CHAN_TOKEN, PUSH_PLUS_TOKEN, EMAIL_PASSWORD}) {
            if (StrUtil.isNotBlank(secret) && secret.trim().length() >= 6) {
                text = text.replace(secret, "***");
            }
        }
        return text;
    }

    /**
     * 使用 HTML 格式发送邮件通知；邮箱配置不完整时跳过。
     */
    public void sendEmail(List<NotificationSection> sections, String title) {
        System.out.println("[通知] 开始发送邮件通知");
        if (StrUtil.isBlank(EMAIL_USERNAME) || StrUtil.isBlank(EMAIL_PASSWORD) || StrUtil.isBlank(EMAIL_TO)) {
            System.out.println("[通知] 邮件通知未配置，跳过发送");
            return;
        }

        MailAccount account = new MailAccount();
        account.setHost("smtp."+extractDomain());
        //端口25是不加密的，465是SSL/TLS加密的 587是STARTTLS加密的
        //如果使用465端口，需要在邮箱设置中开启SSL/TLS加密，否则会报错
        //如果使用587端口，需要在邮箱设置中开启STARTTLS加密，否则会报错
        //如果使用25端口，不需要在邮箱设置中开启加密，但是会有安全风险，不建议使用
//        account.setPort(25);
        account.setPort(465);
        account.setSslEnable(true);
//        account.setStarttlsEnable(true);
        account.setAuth(true);
        account.setFrom(EMAIL_USERNAME);
        account.setUser(EMAIL_USERNAME);
        account.setPass(EMAIL_PASSWORD);
        String message = MessageRenderer.toHtml(sections);
        MailUtil.send(account, CollUtil.toList(EMAIL_TO.split(",")),
                title, message, true);
        System.out.println("[通知] 邮件通知发送成功");
    }


    /**
     * 使用 Markdown 模板发送 PushPlus 通知；Token 未配置时跳过。
     */
    public void sendPushPlus(List<NotificationSection> sections, String title) {
        System.out.println("[通知] 开始发送 PushPlus 通知");
        if (StrUtil.isBlank(PUSH_PLUS_TOKEN)) {
            System.out.println("[通知] PushPlus 通知未配置，跳过发送");
            return;
        }
        JSONObject body = createPushPlusBody(sections, title);
        HttpResponse execute = HttpRequest.post("http://www.pushplus.plus/send")
                .header("Content-Type","application/json")
                .body(body.toString()).execute();
        System.out.println("[通知] PushPlus 响应（HTTP " + execute.getStatus() + "）：" + execute.body());
    }

    /**
     * 构造 PushPlus Markdown 请求体，独立出来以便验证渠道格式映射。
     */
    JSONObject createPushPlusBody(List<NotificationSection> sections) {
        return createPushPlusBody(sections, buildTitle(sections));
    }

    JSONObject createPushPlusBody(List<NotificationSection> sections, String title) {
        JSONObject body = new JSONObject();
        body.set("token", PUSH_PLUS_TOKEN);
        body.set("title", title);
        body.set("content", MessageRenderer.toMarkdown(sections));
        body.set("template", "markdown");
        return body;
    }

    /**
     * 使用 Markdown 格式发送 Server酱通知；Token 未配置时跳过。
     */
    public void sendServerChan(List<NotificationSection> sections, String title) {
        System.out.println("[通知] 开始发送 Server酱通知");
        if (StrUtil.isBlank(SERVER_CHAN_TOKEN)) {
            System.out.println("[通知] Server酱通知未配置，跳过发送");
            return;
        }
        String url = "https://sctapi.ftqq.com/" + SERVER_CHAN_TOKEN + ".send";
        JSONObject body = new JSONObject();
//        body.set("token", SERVER_CHAN_TOKEN);
        body.set("title", title);
        String message = MessageRenderer.toMarkdown(sections);
//        System.out.println(message);
        body.set("desp", message);
        HttpResponse execute = HttpRequest.post(url)
                .header("Content-Type","application/json")
                .body(body.toString()).execute();
        System.out.println("[通知] Server酱响应（HTTP " + execute.getStatus() + "）：" + execute.body());
    }

    /**
     * 从发件邮箱地址中提取 SMTP 域名。
     */
    public String extractDomain() {
        if (EMAIL_USERNAME == null) {
            return null;
        }
        return ReUtil.getGroup1("@([^@]+)$", EMAIL_USERNAME);
    }

}
