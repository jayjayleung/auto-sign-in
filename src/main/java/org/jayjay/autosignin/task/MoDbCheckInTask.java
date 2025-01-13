package org.jayjay.autosignin.task;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.Data;

import java.net.HttpCookie;
import java.util.List;
import java.util.Map;
@Data
public class MoDbCheckInTask implements CheckInTask {
    String loginUrl = "https://www.modb.pro/api/login";
    String checkInUrl = "https://www.modb.pro/api/user/checkIn";
    String userUrl = "https://www.modb.pro/api/follows/detail";

    @Override
    public StringBuilder run() {
        message.append("Modb 签到");
        System.out.println("Modb 签到任务开始");
        System.out.println("开始登录...");
        String modbUsername = System.getenv("MODB_USERNAME");
        String modbPassword = System.getenv("MODB_PASSWORD");
        JSONObject bodyJson = JSONUtil.createObj();
        bodyJson.set("phoneNum", modbUsername);
        bodyJson.set("password", modbPassword);
        HttpResponse loginRes = HttpRequest.post(loginUrl).headerMap(headers(), true)
                .body(bodyJson.toString())
                .execute();

        if (!loginRes.isOk()) {
            System.out.println("Modb 登录失败！");
            message.append("Modb 签到失败！请检查日志!!!").append(lineEnd);
            return message;
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
                .headerMap(headers, true).execute();
        if(userRes.isOk()) {
            JSONObject userBody = toJSON(userRes.body());
            message.append("用户名：").append(userBody.getStr("account")).append(lineEnd);
            message.append("墨值：").append(userBody.getStr("point")).append(lineEnd);
        }
        //签到
        System.out.println("开始签到...");
        HttpResponse checkInRes = HttpRequest.post(checkInUrl)
                .cookie(cookies)
                .headerMap(headers, true).execute();
        if (!checkInRes.isOk()) {
            System.out.println("Modb 签到失败！");
            message.append("Modb 签到失败！请检查日志!!!").append(lineEnd);
            return message;
        }
        String body = checkInRes.body();
        System.out.println(body);
        JSONObject checkInBody = toJSON(body);
        String str = checkInBody.getStr("operateMessage");
        message.append(str).append(lineEnd);
        if(checkInBody.containsKey("operateCallBackObj")){
            JSONObject operateCallBackObj = checkInBody.getJSONObject("operateCallBackObj");
            if(operateCallBackObj!=null && operateCallBackObj.containsKey("point")){
                message.append("当前墨值：").append(operateCallBackObj.getStr("point")).append(lineEnd);
            }
        }
        System.out.println("Modb 签到任务结束...");
        return message;
    }

    public Map<String, String> headers() {
        Map<String, String> headers = commonHeaders();
        headers.put("Host", "www.modb.pro");
        headers.put("Origin", "https://www.modb.pro");
        headers.put("Referer", "https://www.modb.pro/login?redirect=%2ForderList");
        return headers;
    }
}
