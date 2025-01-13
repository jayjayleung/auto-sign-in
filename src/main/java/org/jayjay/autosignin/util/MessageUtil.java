package org.jayjay.autosignin.util;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.ReUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.extra.mail.MailAccount;
import cn.hutool.extra.mail.MailUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONObject;
import org.jayjay.autosignin.task.CheckInTask;

import java.util.List;

public class MessageUtil {

    String EMAIL_USER = System.getenv("EMAIL_USER");
    String EMAIL_PASS = System.getenv("EMAIL_PASS");
    String EMAIL_TO = System.getenv("EMAIL_TO");
    String PUSH_PLUS_TOKEN = System.getenv("PUSH_PLUS_TOKEN");
    String SERVER_CHAN_TOKEN = System.getenv("SERVER_CHAN_TOKEN");

    public void sendMsg(List<StringBuilder> messageList){
        sendEmail(messageList);
        sendPushPlus(messageList);
        sendServerChan(messageList);

    }


    public void sendEmail(List<StringBuilder> messageList){
        if(StrUtil.isBlank(EMAIL_USER) || StrUtil.isBlank(EMAIL_PASS) || StrUtil.isBlank(EMAIL_TO)){
            return;
        }

        MailAccount account = new MailAccount();
        account.setHost("smtp."+extractDomain());
        account.setPort(25);
        account.setAuth(true);
        account.setFrom(EMAIL_USER);
        account.setUser(EMAIL_USER);
        account.setPass(EMAIL_PASS);
        StringBuilder message = new StringBuilder();
        messageList.forEach(sb -> message.append("<p>").append(sb).append("</p>"));
        MailUtil.send(account, CollUtil.toList(EMAIL_TO.split(",")),
                "签到结果", message.toString(), true);
    }

    public void sendPushPlus(List<StringBuilder> messageList){
        if(StrUtil.isBlank(PUSH_PLUS_TOKEN)){
            return;
        }
        JSONObject body = new JSONObject();
        body.set("token", PUSH_PLUS_TOKEN);
        body.set("title", "签到结果");
        StringBuilder message = new StringBuilder();
        messageList.forEach(sb -> message.append(sb).append(CheckInTask.lineEnd));
        body.set("content", message);
        HttpResponse execute = HttpRequest.post("https://www.pushplus.plus/send").body(body.toString()).execute();
        System.out.println(execute.body());
    }

    public void sendServerChan(List<StringBuilder> messageList){
        if(StrUtil.isBlank(SERVER_CHAN_TOKEN)){
            return;
        }
        String url = "https://sctapi.ftqq.com/" + SERVER_CHAN_TOKEN + ".send";
        JSONObject body = new JSONObject();
//        body.set("token", SERVER_CHAN_TOKEN);
        body.set("title", "签到结果");
        StringBuilder message = new StringBuilder();
        messageList.forEach(sb -> message.append(sb).append(CheckInTask.lineEnd));
        body.set("desp", message);
        HttpResponse execute = HttpRequest.post(url).body(body.toString()).execute();
        System.out.println(execute.body());
    }

    public String extractDomain() {
        if (EMAIL_USER == null) {
            return null;
        }
        return ReUtil.getGroup1("@([^@]+)$", EMAIL_USER);
    }
}
