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
import java.util.stream.Collectors;

public class MessageUtil {

    public static final String lineEnd = "\n";
    public static final String dbLineEnd = "\n\n";

    public String EMAIL_USERNAME = System.getenv("EMAIL_USERNAME");
    public String EMAIL_PASSWORD = System.getenv("EMAIL_PASSWORD");
    public String EMAIL_TO = System.getenv("EMAIL_TO");
    public String PUSH_PLUS_TOKEN = System.getenv("PUSH_PLUS_TOKEN");
    public String SERVER_CHAN_TOKEN = System.getenv("SERVER_CHAN_TOKEN");


    public void sendMsg(List<MessageList> messageList){
        List<MessageList> sendList = messageList.stream().filter(MessageList::isSend).collect(Collectors.toList());
        if(CollUtil.isEmpty(sendList)){
            System.out.println("没有需要发送的消息，停止发送");
            return;
        }
        sendEmail(sendList);
        sendPushPlus(sendList);
        sendServerChan(sendList);

    }



    public void sendEmail(List<MessageList> messageList){
        System.out.println("发送邮件");
        if(StrUtil.isBlank(EMAIL_USERNAME) || StrUtil.isBlank(EMAIL_PASSWORD) || StrUtil.isBlank(EMAIL_TO)){
            System.out.println("发送邮件失败");
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
        if (EMAIL_USERNAME == null) {
            return null;
        }
        return ReUtil.getGroup1("@([^@]+)$", EMAIL_USERNAME);
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
