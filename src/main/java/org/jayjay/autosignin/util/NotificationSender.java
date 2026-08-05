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
     * 过滤无有效内容的任务结果，并依次调用已配置的通知渠道。
     */
    public void send(List<NotificationSection> sections) {
        List<NotificationSection> sendableSections = sections.stream()
                .filter(NotificationSection::hasContent)
                .collect(Collectors.toList());
        if (CollUtil.isEmpty(sendableSections)) {
            System.out.println("[通知] 没有需要发送的签到结果，跳过通知");
            return;
        }
        sendEmail(sendableSections);
        sendPushPlus(sendableSections);
        sendServerChan(sendableSections);

    }



    /**
     * 使用 HTML 格式发送邮件通知；邮箱配置不完整时跳过。
     */
    public void sendEmail(List<NotificationSection> sections) {
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
//        System.out.println(message);
        MailUtil.send(account, CollUtil.toList(EMAIL_TO.split(",")),
                "签到结果", message, true);
        System.out.println("[通知] 邮件通知发送成功");
    }


    /**
     * 使用 Markdown 模板发送 PushPlus 通知；Token 未配置时跳过。
     */
    public void sendPushPlus(List<NotificationSection> sections) {
        System.out.println("[通知] 开始发送 PushPlus 通知");
        if (StrUtil.isBlank(PUSH_PLUS_TOKEN)) {
            System.out.println("[通知] PushPlus 通知未配置，跳过发送");
            return;
        }
        JSONObject body = createPushPlusBody(sections);
        HttpResponse execute = HttpRequest.post("http://www.pushplus.plus/send")
                .header("Content-Type","application/json")
                .body(body.toString()).execute();
        System.out.println("[通知] PushPlus 响应（HTTP " + execute.getStatus() + "）：" + execute.body());
    }

    /**
     * 构造 PushPlus Markdown 请求体，独立出来以便验证渠道格式映射。
     */
    JSONObject createPushPlusBody(List<NotificationSection> sections) {
        JSONObject body = new JSONObject();
        body.set("token", PUSH_PLUS_TOKEN);
        body.set("title", "签到结果");
        body.set("content", MessageRenderer.toMarkdown(sections));
        body.set("template", "markdown");
        return body;
    }

    /**
     * 使用 Markdown 格式发送 Server酱通知；Token 未配置时跳过。
     */
    public void sendServerChan(List<NotificationSection> sections) {
        System.out.println("[通知] 开始发送 Server酱通知");
        if (StrUtil.isBlank(SERVER_CHAN_TOKEN)) {
            System.out.println("[通知] Server酱通知未配置，跳过发送");
            return;
        }
        String url = "https://sctapi.ftqq.com/" + SERVER_CHAN_TOKEN + ".send";
        JSONObject body = new JSONObject();
//        body.set("token", SERVER_CHAN_TOKEN);
        body.set("title", "签到结果");
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
