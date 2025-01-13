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
public class TiDbCheckInTask implements CheckInTask {

    String loginUrl = "https://accounts.pingcap.cn/api/login/password";
    String checkInUrl = "https://tidb.net/api/points/daily-checkin";
    String userUrl = "https://tidb.net/api/me";
    String pointsUrl = "https://tidb.net/api/points";


    @Override
    public StringBuilder run() {
        message.append("TiDb 签到");
        System.out.println("TiDb 签到任务开始");
        System.out.println("开始登录...");
        String tidbUsername = System.getenv("TIDB_USERNAME");
        String tidbPassword = System.getenv("TIDB_PASSWORD");
        JSONObject bodyJson = JSONUtil.createObj();
        bodyJson.set("identifier", tidbUsername);
        bodyJson.set("password", tidbPassword);
        bodyJson.set("redirect_to", "https://tidb.net/member");
        HttpResponse loginRes = HttpRequest.post(loginUrl).headerMap(headers(), true)
                .body(bodyJson.toString())
                .execute();
        if (!loginRes.isOk()) {
            System.out.println("TiDb 登录失败！");
            message.append("TiDb 签到失败！请检查日志!!!").append(lineEnd);
            return message;
        }

        JSONObject loginBody = toJSON(loginRes.body());
        System.out.println(loginBody.getStr("detail"));
        List<HttpCookie> cookies = loginRes.getCookies();
        Map<String, String> checkInHeaders = checkInHeaders();
        checkInHeaders.put("x-csrftoken", loginRes.getCookieValue("csrftoken"));
        HttpResponse meRes = HttpRequest.get(userUrl).cookie(cookies).headerMap(checkInHeaders, true).execute();
        HttpResponse pointRes = HttpRequest.get(pointsUrl).cookie(cookies).headerMap(checkInHeaders, true).execute();
        if (meRes.isOk()) {
            message.append("用户：").append(toJSON(meRes.body()).getStr("username")).append(lineEnd);
        }
        if (pointRes.isOk()) {
            message.append("当前积分：").append(toJSON(pointRes.body()).getStr("current_points")).append(lineEnd);
        }
        System.out.println("开始签到...");
        HttpResponse checkInRes = HttpRequest.post("https://tidb.net/api/points/daily-checkin")
                .cookie(cookies)
                .headerMap(checkInHeaders, true)
                .execute();
        if (!checkInRes.isOk()) {
            System.out.println("tidb 签到失败！");
            message.append("tidb 签到失败！请检查日志!!!").append(lineEnd);
            return message;
        }
        JSONObject checkInBody = toJSON(checkInRes.body());
        System.out.println(checkInBody);
        message.append("签到:").append(checkInBody.getStr("detail"));
        JSONObject data = checkInBody.getJSONObject("data");
        if (data.containsKey("points")) {
            message.append("，积分:").append(data.getStr("points"));
            if (data.containsKey("tomorrow_points")) {
                message.append("，明天积分:").append(data.getStr("tomorrow_points"));
            }
            message.append(lineEnd);
        }

        System.out.println("TiDb 签到任务结束...");
        return message;
    }



    public Map<String, String> headers() {
        Map<String, String> headers = commonHeaders();
        headers.put("Origin", "https://accounts.pingcap.cn");
        return headers;
    }

    public Map<String, String> checkInHeaders() {
        Map<String, String> headers = commonHeaders();
        headers.put("Origin", "https://tidb.net");
        headers.put("Referer", "https://tidb.net/member");
        return headers;
    }
}
