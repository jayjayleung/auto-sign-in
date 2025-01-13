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
public class TiDbCheckInTask extends CheckInTask {

    String loginUrl = "https://accounts.pingcap.cn/api/login/password";
    String checkInUrl = "https://tidb.net/api/points/daily-checkin";
    String userUrl = "https://tidb.net/api/me";
    String pointsUrl = "https://tidb.net/api/points/me";


    @Override
    public CheckInTask run() {
        addMessage("TiDb 签到");
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
        System.out.println(loginRes.body());
        if (!loginRes.isOk()) {
            System.out.println("TiDb 登录失败！");
            addMessage("TiDb 签到失败！请检查日志!!!");
            return this;
        }

        JSONObject loginBody = toJSON(loginRes.body());
        System.out.println(loginBody);
        System.out.println(loginBody.getStr("detail"));
        List<HttpCookie> cookies = loginRes.getCookies();
        cookies.forEach(System.out::println);
        Map<String, String> checkInHeaders = checkInHeaders();
        checkInHeaders.put("x-csrftoken", loginRes.getCookieValue("csrftoken"));
//        sleep(1000);
        HttpResponse meRes = HttpRequest.get(userUrl).cookie(cookies).headerMap(checkInHeaders(), true)
                .header("x-csrftoken", loginRes.getCookieValue("csrftoken")).execute();
        if (meRes.isOk()) {
            JSONObject userBody = toJSON(meRes.body());
            System.out.println(userBody);
            addMessage(lineMsg("用户：").append(userBody.getJSONObject("data").getStr("username")));
        }
        HttpResponse pointRes = HttpRequest.get(pointsUrl).cookie(cookies).headerMap(checkInHeaders(), true).execute();
        if (pointRes.isOk()) {
            JSONObject pointBody = toJSON(pointRes.body());
            addMessage("当前积分：", pointBody.getJSONObject("data").getStr("current_points"));
        }
        System.out.println("开始签到...");
        HttpResponse checkInRes = HttpRequest.post("https://tidb.net/api/points/daily-checkin")
                .cookie(cookies)
                .headerMap(checkInHeaders, true)
                .execute();
        JSONObject checkInBody = toJSON(checkInRes.body());
        System.out.println(checkInBody);
        StringBuilder checkInMsg = lineMsg("签到:").append(checkInBody.getStr("detail"));
        addMessage("签到：",checkInBody.getStr("detail"));
        JSONObject data = checkInBody.getJSONObject("data");
        if (data!=null && data.containsKey("points")) {
            checkInMsg.append("，积分:").append(data.getStr("points"));
            if (data.containsKey("tomorrow_points")) {
                checkInMsg.append("，明天积分:").append(data.getStr("tomorrow_points"));
            }
        }
        addMessage(checkInMsg);
        System.out.println("TiDb 签到任务结束...");
        return this;
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
