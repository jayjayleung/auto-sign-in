package org.jayjay.autosignin.task;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.jayjay.autosignin.entity.MessageList;

import java.net.HttpCookie;
import java.util.List;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
public class TiDbCheckInTask extends CheckInTask {

    String loginUrl = "https://accounts.pingcap.cn/api/login/password";
    String checkInUrl = "https://tidb.net/api/points/daily-checkin";
    String userUrl = "https://tidb.net/api/me";
    String pointsUrl = "https://tidb.net/api/points/me";
    @Override
    public MessageList messageList() {
        return new MessageList("Tidb 签到", listMessage);
    }

    @Override
    public CheckInTask run() {
        System.out.println("TiDb 签到任务开始");
        String tidbUsername = System.getenv("TIDB_USERNAME");
        String tidbPassword = System.getenv("TIDB_PASSWORD");
        if(StrUtil.isBlank(tidbUsername) || StrUtil.isBlank(tidbPassword)){
            System.out.println("TiDb 账号密码未配置，跳过签到");
            isRun = !isRun;
            return this;
        }
        System.out.println("开始登录...");
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
            addMessage(lineMsg("用户名：").append(userBody.getJSONObject("data").getStr("username")));
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
        StringBuilder checkInMsg = lineMsg("签到：").append(checkInBody.getStr("detail"));
        addMessage(checkInMsg);
        JSONObject data = checkInBody.getJSONObject("data");
        if (data!=null && data.containsKey("points")) {
            StringBuilder points = lineMsg("获得").append(data.getStr("points")).append("积分");
            if (data.containsKey("tomorrow_points")) {
                points.append("，明天签到获得").append(data.getStr("tomorrow_points")).append("积分");
            }
            addMessage(points);
        }
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
