package org.jayjay.autosignin.task;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.jayjay.autosignin.entity.MessageLine;
import org.jayjay.autosignin.entity.NotificationSection;

import java.net.HttpCookie;
import java.util.List;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
public class TiDbCheckInTask extends CheckInTask {

//    String loginUrl = "https://accounts.pingcap.cn/api/login/password";
    String loginUrl = "https://pingkai.cn/accounts/api/login/password";
    String checkInUrl = "https://pingkai.cn/accounts/api/points/daily-checkin";
    String userUrl = "https://pingkai.cn/accounts/api/me";
    String pointsUrl = "https://pingkai.cn/accounts/api/points/me";
    String redirectTo = "https://pingkai.cn/tidbcommunity/member";

    @Override
    public NotificationSection buildNotificationSection() {
        return new NotificationSection("TiDB 签到", messageBlocks);
    }

    @Override
    public CheckInTask run() {
        try {
            System.out.println("[TiDB] 签到任务开始");
            String tidbUsername = System.getenv("TIDB_USERNAME");
            String tidbPassword = System.getenv("TIDB_PASSWORD");
            if (StrUtil.isBlank(tidbUsername) || StrUtil.isBlank(tidbPassword)) {
                System.out.println("[TiDB] 用户名或密码未配置，跳过签到");
                enabled = false;
                return this;
            }
            System.out.println("[TiDB] 开始登录");
            JSONObject bodyJson = JSONUtil.createObj();
            bodyJson.set("identifier", tidbUsername);
            bodyJson.set("password", tidbPassword);
            bodyJson.set("redirect_to", redirectTo);
            HttpResponse loginRes = HttpRequest.post(loginUrl).headerMap(headers(), true)
                    .body(bodyJson.toString())
                    .execute();
            if (!loginRes.isOk()) {
                System.err.println("[TiDB] 登录失败，HTTP " + loginRes.getStatus());
                addMessage("TiDB 签到失败，请查看日志");
                return this;
            }

            JSONObject loginBody = toJSON(loginRes.body());
            System.out.println("[TiDB] 登录结果：" + loginBody.getStr("detail"));
            List<HttpCookie> cookies = loginRes.getCookies();
            Map<String, String> checkInHeaders = checkInHeaders();
            checkInHeaders.put("x-csrftoken", loginRes.getCookieValue("csrftoken"));
//        sleep(1000);
            HttpResponse meRes = HttpRequest.get(userUrl).cookie(cookies).headerMap(checkInHeaders(), true)
                    .header("x-csrftoken", loginRes.getCookieValue("csrftoken")).execute();
            if (meRes.isOk()) {
                JSONObject userBody = toJSON(meRes.body());
                addMessage("用户名：", userBody.getJSONObject("data").getStr("username"));
            }
            HttpResponse pointRes = HttpRequest.get(pointsUrl).cookie(cookies).headerMap(checkInHeaders(), true).execute();
            if (pointRes.isOk()) {
                JSONObject pointBody = toJSON(pointRes.body());
                addMessage("当前积分：", pointBody.getJSONObject("data").getStr("current_points"));
            }
            System.out.println("[TiDB] 开始签到");
            HttpResponse checkInRes = HttpRequest.post(checkInUrl)
                    .cookie(cookies)
                    .headerMap(checkInHeaders, true)
                    .execute();
            JSONObject checkInBody = toJSON(checkInRes.body());
            System.out.println("[TiDB] 签到结果：" + checkInBody.getStr("detail"));
            addMessage("签到结果：", checkInBody.getStr("detail"));
            JSONObject data = checkInBody.getJSONObject("data");
            if (data != null && data.containsKey("points")) {
                MessageLine points = new MessageLine()
                        .append("本次获得 ", data.getStr("points"), " 积分");
                if (data.containsKey("tomorrow_points")) {
                    points.append("，明日签到可获得 ", data.getStr("tomorrow_points"), " 积分");
                }
                addMessage(points);
            }
            System.out.println("[TiDB] 签到任务结束");
        } catch (Exception e) {
            e.printStackTrace();
            addMessage("TiDB 签到失败，请查看日志");
        }
        return this;
    }


    public Map<String, String> headers() {
        Map<String, String> headers = commonHeaders();
        headers.put("Origin", "https://pingkai.cn");
        return headers;
    }

    public Map<String, String> checkInHeaders() {
        Map<String, String> headers = commonHeaders();
        headers.put("Origin", "https://pingkai.cn");
        headers.put("Referer", "https://pingkai.cn/tidbcommunity/member");
        return headers;
    }

}
