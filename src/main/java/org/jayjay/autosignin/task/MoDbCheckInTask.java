package org.jayjay.autosignin.task;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.jayjay.autosignin.entity.MessageList;
import org.jayjay.autosignin.util.MessageUtil;
import org.junit.Test;

import java.net.HttpCookie;
import java.util.List;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
public class MoDbCheckInTask extends CheckInTask {
    String loginUrl = "https://www.modb.pro/api/login";
    String checkInUrl = "https://www.modb.pro/api/user/checkIn";
    String userUrl = "https://www.modb.pro/api/user/detail";


    @Override
    public MessageList messageList() {
        return new MessageList("Modb 签到", listMessage);
    }

    @Test()
    public void testRun() {
        run();
    }

    @Override
    public CheckInTask run() {
        System.out.println("Modb 签到任务开始");
        String modbUsername = System.getenv("MODB_USERNAME");
        String modbPassword = System.getenv("MODB_PASSWORD");
        if (StrUtil.isBlank(modbUsername) || StrUtil.isBlank(modbPassword)) {
            System.out.println("Modb 账号密码未配置，跳过签到");
            isRun = !isRun;
            return this;
        }
        System.out.println("开始登录...");
        JSONObject bodyJson = JSONUtil.createObj();
        bodyJson.set("phoneNum", modbUsername);
        bodyJson.set("password", modbPassword);
        HttpResponse loginRes = HttpRequest.post(loginUrl).headerMap(headers(), true)
                .body(bodyJson.toString())
                .execute();

        if (!loginRes.isOk()) {
            System.out.println("Modb 登录失败！");
            System.out.println(loginRes.body());
            addMessage("Modb 签到失败！请检查日志!!!");
            return this;
        }
        JSONObject loginBody = toJSON(loginRes.body());
        System.out.println(loginBody.getStr("operateMessage"));
        List<HttpCookie> cookies = loginRes.getCookies();
        loginRes.getCookies().forEach(System.out::println);
        Map<String, String> headers = headers();
        headers.put("Authorization", loginRes.header("Authorization"));
        //查询用户信息
        HttpResponse userRes = HttpRequest.get(userUrl)
                .cookie(cookies)
                .headerMap(userHeaders(loginRes.header("Authorization")), true).execute();
        if (userRes.isOk()) {
            JSONObject userBody = toJSON(userRes.body());
            System.out.println(userBody);
            StringBuilder account = lineMsg("用户名：").append(userBody.getStr("account"));
            addMessage(account);
            addMessage("墨值：", userBody.getStr("point"));
        }
        //签到
        System.out.println("开始签到...");
        HttpResponse checkInRes = HttpRequest.post(checkInUrl)
                .cookie(cookies)
                .headerMap(headers, true).execute();
        if (!checkInRes.isOk()) {
            System.out.println("Modb 签到失败！");
            addMessage("Modb 签到失败！请检查日志!!!");
            return this;
        }
        String body = checkInRes.body();
        System.out.println(body);
        JSONObject checkInBody = toJSON(body);
        String str = checkInBody.getStr("operateMessage");
        StringBuilder checkInMsg = lineMsg(str);

//        if(checkInBody.containsKey("operateCallBackObj")){
//            JSONObject operateCallBackObj = checkInBody.getJSONObject("operateCallBackObj");
//            if(operateCallBackObj!=null && operateCallBackObj.containsKey("point")){
//                checkInMsg.append("当前墨值：").append(operateCallBackObj.getStr("point")).append(MessageUtil.lineEnd);
//            }
//        }
        addMessage(checkInMsg);
        System.out.println("Modb 签到任务结束...");
        return this;
    }

    public Map<String, String> headers() {
        Map<String, String> headers = commonHeaders();
        headers.put("Host", "www.modb.pro");
        headers.put("Origin", "https://www.modb.pro");
        headers.put("Referer", "https://www.modb.pro/login?redirect=%2ForderList");
        return headers;
    }

    public Map<String, String> userHeaders(String token) {
        Map<String, String> headers = commonHeaders();
        headers.put("Host", "www.modb.pro");
        headers.put("Origin", "https://www.modb.pro");
        headers.put("Authorization", token);
//        headers.put("Referer", "https://www.modb.pro/login?redirect=%2ForderList");
        return headers;
    }
}
