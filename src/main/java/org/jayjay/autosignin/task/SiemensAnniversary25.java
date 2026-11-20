package org.jayjay.autosignin.task;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 西门子「一路上有你·25 周年庆」活动的分享抽奖。
 * <p>
 * 活动页 /event/Siemens25Anniversary/index.aspx 底部有一个「分享抽奖」按钮，每人限抽一次，
 * 奖品里有 25 西币这类直接到账的奖励。站点接口：
 * <ul>
 *   <li>{@code type=getstatus} 查是否已抽过，返回 {@code AlreadyDrawn}；</li>
 *   <li>{@code type=shareanddraw} 抽一次，返回 {@code AwardMsg} 中奖文案，
 *       已抽过时 ErrorCode=2。</li>
 * </ul>
 * 抽奖是幂等的：已抽过再抽只会回「已抽过」，不会重复发奖，所以先查状态再决定要不要抽。
 * <p>
 * 活动有固定结束时间（脚本里写死），结束后页面会提示活动已结束、接口也不再发奖；
 * 这种情况下抽奖会失败，只记日志不影响其它任务。
 */
final class SiemensAnniversary25 {

    private static final String EVENT_PAGE = "/event/Siemens25Anniversary/index.aspx";
    private static final String API = "/event/AjaxAPI/Siemens25.ashx";
    /**
     * 活动页把当前用户 ID 写进脚本：var s25UserId = Number('1820833');
     * 用活动页自己的 ID 而不是首页的，避免两个页面口径不一致。
     */
    private static final Pattern USER_ID = Pattern.compile("s25UserId\\s*=\\s*Number\\('(\\d+)'\\)");
    /**
     * 活动页面在活动结束后仍可访问，但页面上没有抽奖横幅。
     * 有这段文案说明活动已收尾，不必再打接口。
     */
    private static final String ENDED_MARKER = "活动已结束";

    private final SiemensCheckInTask task;

    SiemensAnniversary25(SiemensCheckInTask task) {
        this.task = task;
    }

    /** 抽奖结果，供通知拼一句话。 */
    static final class Result {
        /** 本次是否真的抽了一次。 */
        boolean drawn;
        /** 站点返回的中奖文案，例如「恭喜您抽中"25西币"…」。 */
        String message = "";
        /** 已抽过、活动结束、未登录等跳过的原因。 */
        String skipReason = "";
    }

    /** 如果还没抽过就抽一次；已抽过或活动结束则什么都不做。 */
    Result run() {
        Result result = new Result();
        SiemensCheckInTask.Page page = task.follow(task.supportBaseUrl() + EVENT_PAGE);
        if (page == null || StrUtil.isBlank(page.body)) {
            result.skipReason = "打不开活动页";
            return result;
        }
        if (page.body.contains(ENDED_MARKER)) {
            result.skipReason = "活动已结束";
            return result;
        }
        String userId = group(USER_ID, page.body);
        if (StrUtil.isBlank(userId) || "0".equals(userId)) {
            result.skipReason = "活动页未登录";
            return result;
        }
        JSONObject status = post("type=getstatus", userId);
        if (status == null) {
            result.skipReason = "查询抽奖状态失败";
            return result;
        }
        if (Boolean.TRUE.equals(status.getBool("AlreadyDrawn"))) {
            // 每人只能抽一次，抽过就不必再问，免得每天白打一次接口。
            result.skipReason = "已经抽过";
            return result;
        }
        JSONObject draw = post("type=shareanddraw", userId);
        if (draw == null) {
            result.skipReason = "抽奖请求失败";
            return result;
        }
        int code = draw.getInt("ErrorCode", -1);
        String message = StrUtil.blankToDefault(draw.getStr("AwardMsg"),
                StrUtil.blankToDefault(draw.getStr("ErrorDetail"), ""));
        if (code == 0) {
            result.drawn = true;
        } else if (code == 2) {
            // 接口自己说已抽过，不是错误。
            result.skipReason = "已经抽过";
            return result;
        } else {
            result.skipReason = StrUtil.isBlank(message) ? "抽奖未成功" : message;
            return result;
        }
        result.message = message;
        System.out.println("[西门子] 25 周年抽奖：" + (StrUtil.isBlank(message) ? "已抽奖" : message));
        return result;
    }

    private JSONObject post(String type, String userId) {
        String url = task.supportBaseUrl() + API;
        try {
            SiemensCheckInTask.HttpResult response = task.post(url,
                    type + "&uid=" + userId + "&from=pc", task.supportHeaders(url));
            return SiemensCheckInTask.parseObject(response.body);
        } catch (Exception e) {
            System.out.println("[西门子] 25 周年抽奖接口异常：" + e.getMessage());
            return null;
        }
    }

    private static String group(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text == null ? "" : text);
        return matcher.find() ? matcher.group(1) : null;
    }
}
