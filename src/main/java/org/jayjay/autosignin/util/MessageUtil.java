package org.jayjay.autosignin.util;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ReUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.extra.mail.MailAccount;
import cn.hutool.extra.mail.MailUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONObject;
import org.jayjay.autosignin.entity.MessageList;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class MessageUtil {

    public static final String lineEnd = "\n";
    public static final String dbLineEnd = "\n\n";

    public String EMAIL_USER = System.getenv("EMAIL_USER");
    public String EMAIL_PASS = System.getenv("EMAIL_PASS");
    public String EMAIL_TO = System.getenv("EMAIL_TO");
    public String PUSH_PLUS_TOKEN = System.getenv("PUSH_PLUS_TOKEN");
    public String SERVER_CHAN_TOKEN = System.getenv("SERVER_CHAN_TOKEN");

    public void sendMsg(List<MessageList> messageList){
        sendEmail(messageList);
        sendPushPlus(messageList);
        sendServerChan(messageList);

    }



    public void sendEmail(List<MessageList> messageList){
        System.out.println("发送邮件");
        if(StrUtil.isBlank(EMAIL_USER) || StrUtil.isBlank(EMAIL_PASS) || StrUtil.isBlank(EMAIL_TO)){
            System.out.println("发送邮件失败");
            return;
        }

        MailAccount account = new MailAccount();
        account.setHost("smtp."+extractDomain());
        account.setPort(25);
        account.setAuth(true);
        account.setFrom(EMAIL_USER);
        account.setUser(EMAIL_USER);
        account.setPass(EMAIL_PASS);
        StringBuilder message = toHtml(messageList);
//        System.out.println(message);
        MailUtil.send(account, CollUtil.toList(EMAIL_TO.split(",")),
                "签到结果", message.toString(), true);
        System.out.println("发送邮件成功");
    }


    public void sendPushPlus(List<MessageList> messageList){
        System.out.println("发送pushplus");
        if(StrUtil.isBlank(PUSH_PLUS_TOKEN)){
            System.out.println("发送pushplus失败");
            return;
        }
        JSONObject body = new JSONObject();
        body.set("token", PUSH_PLUS_TOKEN);
        body.set("title", "签到结果");
        StringBuilder message = toHtml(messageList);
        body.set("content", message);
//        System.out.println(message);
        HttpResponse execute = HttpRequest.post("http://www.pushplus.plus/send")
                .header("Content-Type","application/json")
                .body(body.toString()).execute();
        System.out.println(execute.body());
        System.out.println("发送pushplus成功");
    }

    public void sendServerChan(List<MessageList> messageList){
        System.out.println("发送serverchan");
        if(StrUtil.isBlank(SERVER_CHAN_TOKEN)){
            System.out.println("发送serverchan失败");
            return;
        }
        String url = "https://sctapi.ftqq.com/" + SERVER_CHAN_TOKEN + ".send";
        JSONObject body = new JSONObject();
//        body.set("token", SERVER_CHAN_TOKEN);
        body.set("title", "签到结果");
        StringBuilder message = toMarkdown(messageList);
//        System.out.println(message);
        body.set("desp", message);
        HttpResponse execute = HttpRequest.post(url)
                .header("Content-Type","application/json")
                .body(body.toString()).execute();
        System.out.println(execute.body());
        System.out.println("发送serverchan成功");
    }

    public String extractDomain() {
        if (EMAIL_USER == null) {
            return null;
        }
        return ReUtil.getGroup1("@([^@]+)$", EMAIL_USER);
    }


    private static StringBuilder toHtml(List<MessageList> messageList) {
        StringBuilder message = new StringBuilder();
//        messageList.forEach(sb -> message.append("<p>").append(sb).append("</p>"));
        messageList.forEach(item->{
            message.append("<h2>").append(item.getTitle()).append("</h2>");
            item.getMessages().forEach(sb->{
                message.append("<p>").append(sb).append("</p>");
            });
        });
        return message;
    }
    private static StringBuilder toMarkdown(List<MessageList> messageList) {
        StringBuilder message = new StringBuilder();
        messageList.forEach(item->{
            message.append("## ").append(item.getTitle()).append(dbLineEnd);
            item.getMessages().forEach(sb->{
                message.append(sb).append(dbLineEnd);
            });
        });
        return message;
    }

}
