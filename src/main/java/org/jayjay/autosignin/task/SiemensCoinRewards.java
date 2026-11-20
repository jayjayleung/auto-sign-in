package org.jayjay.autosignin.task;

import cn.hutool.core.util.StrUtil;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 领取消息中心里需要手动确认的西币奖励。
 * <p>
 * 站点的签到西币和抽奖西币是自动到账的，但论坛发帖、回帖、找答案各类活动奖励
 * 只发一条「获奖通知」站内消息，里面带一个「领取获奖西币 N 枚」的链接，
 * 必须点进去提交一次才真正入账，60 天内不领就作废。这一段就是代替人工点这些链接。
 * <p>
 * 领取接口对同一笔奖励是幂等的：重复提交返回「您已领取过该奖励」，
 * 所以我们不需要精确的「哪些领过了」判断，只要把已确认处理过的通知 ID 记下来、
 * 以后不再重复抓详情页即可。只有真的领取成功或站点明确说已领过才记账，
 * 网络异常这类失败不记账，下次运行继续重试，避免漏掉快过期的奖励。
 */
final class SiemensCoinRewards {

    /**
     * 最多翻几页消息列表。单页 20 条，实测账号用了两个多月积到 6 页，
     * 取 8 页足够覆盖；翻不到的更早消息对应奖励也早已过 60 天有效期。
     */
    private static final int MAX_PAGES = 8;
    /**
     * 单次最多打开几条通知详情。
     * 首次接管账号时消息中心可能积压七八十条历史获奖通知，上限设小了要好几天才领完，
     * 而每条详情页只有几十 KB、耗时不到 1 秒，一次多抓几十条比拖到过期划算。
     * 只有首次会跑满，之后每天新增几条，处理过的通知已记账不会再抓。
     */
    private static final int MAX_DETAILS = 100;
    /** 列表里获奖通知的类型标签。 */
    private static final String AWARD_TAG = "获奖通知";

    private static final Pattern NOTICE_BLOCK = Pattern.compile("<dl[^>]*>(.*?)</dl>", Pattern.DOTALL);
    private static final Pattern NOTICE_ID = Pattern.compile("MyMsgDetail\\.aspx\\?noticeId=(\\d+)");
    private static final Pattern NOTICE_TAG = Pattern.compile("<span class=\"tag\">([^<]*)</span>");
    /** 详情页里的领取链接，指向 PickupCoin.aspx?ulevel=0&id=奖励 ID。 */
    private static final Pattern PICKUP_LINK = Pattern.compile(
            "PickupCoin\\.aspx\\?ulevel=0&(?:amp;)?id=(\\d+)");
    private static final Pattern AWARD_COIN = Pattern.compile("奖励西币\\s*(\\d+)\\s*枚");
    /** 列表页脚本里的分页总数：initPage(1, 2)。 */
    private static final Pattern PAGE_COUNT = Pattern.compile("initPage\\(\\s*\\d+\\s*,\\s*(\\d+)\\s*\\)");

    private final SiemensCheckInTask task;

    SiemensCoinRewards(SiemensCheckInTask task) {
        this.task = task;
    }

    /** 领取结果，供通知拼一句话。 */
    static final class Result {
        /** 本次真正领取成功的笔数。 */
        int claimed;
        /** 本次领取成功的西币总枚数。 */
        int coins;
        /** 提交了但站点没给成功也没说已领过的笔数，不算失败中止，只记日志并提示。 */
        int failed;
    }

    /** 一条获奖通知。 */
    private static final class Notice {
        final String id;

        Notice(String id) {
            this.id = id;
        }
    }

    /** 扫消息列表，把还没处理过的西币奖励全部领掉。 */
    Result collect() {
        Result result = new Result();
        Set<String> handled = task.handledCoinNotices();
        List<Notice> notices = readAwardNotices(handled);
        if (notices.isEmpty()) {
            return result;
        }
        System.out.println("[西门子] 消息中心有 " + notices.size() + " 条西币奖励待确认");
        int checked = 0;
        for (Notice notice : notices) {
            if (checked >= MAX_DETAILS) {
                break;
            }
            checked++;
            SiemensCheckInTask.Page detail = task.follow(task.supportBaseUrl()
                    + "/MySupport/MyMsgDetail.aspx?noticeId=" + notice.id);
            if (detail == null || StrUtil.isBlank(detail.body)) {
                // 详情读不到就不记账，下次再试，避免把还能领的奖励永久跳过。
                continue;
            }
            Matcher link = PICKUP_LINK.matcher(detail.body);
            if (!link.find()) {
                // 祝贺类通知没有领取链接，记下来免得每天重复抓。
                task.markCoinNoticeHandled(notice.id);
                continue;
            }
            claim(notice, link.group(1), detail.body, result);
        }
        return result;
    }

    /** 提交一笔领取，按站点返回区分「成功」「已领过」「异常」。 */
    private void claim(Notice notice, String pickupId, String detailBody, Result result) {
        String url = task.supportBaseUrl() + "/club/bbs/PickupCoin.aspx?ulevel=0&id=" + pickupId;
        // 站点要求带上隐藏字段 ulevel，ulevelstr 留空即可，和页面表单一致。
        SiemensCheckInTask.HttpResult response =
                task.post(url, "ulevelstr=&ulevel=0", task.supportHeaders(url));
        String body = StrUtil.blankToDefault(response.body, "");
        if (body.contains("成功领取")) {
            int coins = coinAmount(body, detailBody);
            result.claimed++;
            result.coins += coins;
            task.markCoinNoticeHandled(notice.id);
            System.out.println("[西门子] 领取消息西币 " + coins + " 枚（通知 " + notice.id + "）");
        } else if (body.contains("已领取过")) {
            task.markCoinNoticeHandled(notice.id);
            System.out.println("[西门子] 通知 " + notice.id + " 的西币之前已领取");
        } else {
            result.failed++;
            System.out.println("[西门子] 领取消息西币未成功：HTTP " + response.status
                    + " " + SiemensCheckInTask.abbreviate(body));
        }
    }

    /**
     * 取本次领到的西币枚数。
     * 领取成功页会把金额再写一遍，取不到时退回详情页正文里的「奖励西币 N 枚」。
     */
    private static int coinAmount(String claimBody, String detailBody) {
        Integer fromClaim = firstNumber(claimBody);
        if (fromClaim != null) {
            return fromClaim;
        }
        Integer fromDetail = firstNumber(detailBody);
        return fromDetail == null ? 0 : fromDetail;
    }

    private static Integer firstNumber(String html) {
        if (StrUtil.isBlank(html)) {
            return null;
        }
        // 金额和标签之间常夹着 <strong>/<span>，先去标签再匹配。
        Matcher matcher = AWARD_COIN.matcher(html.replaceAll("<[^>]+>", " "));
        if (!matcher.find()) {
            return null;
        }
        try {
            return Integer.valueOf(matcher.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 按页读消息列表，只留下还没处理过的获奖通知。 */
    private List<Notice> readAwardNotices(Set<String> handled) {
        List<Notice> notices = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        int pages = 1;
        for (int page = 1; page <= MAX_PAGES; page++) {
            String url = task.supportBaseUrl() + "/MySupport/MyMsgmain.aspx";
            if (page > 1) {
                url = url + "?pageIndex=" + page + "&infoType=all";
            }
            SiemensCheckInTask.Page list = task.follow(url);
            if (list == null || StrUtil.isBlank(list.body)) {
                break;
            }
            if (page == 1) {
                pages = pageCount(list.body);
            }
            for (Notice notice : parseNotices(list.body)) {
                if (!handled.contains(notice.id) && seen.add(notice.id)) {
                    notices.add(notice);
                }
            }
            if (page >= pages) {
                break;
            }
        }
        return notices;
    }

    /** 从列表页里取分页总数，取不到按 1 页处理。 */
    static int pageCount(String html) {
        Matcher matcher = PAGE_COUNT.matcher(html == null ? "" : html);
        if (!matcher.find()) {
            return 1;
        }
        try {
            return Math.max(1, Integer.parseInt(matcher.group(1)));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /** 解析列表页里的获奖通知；故事审核、管理员通知这些没有西币，直接排除。 */
    static List<Notice> parseNotices(String html) {
        List<Notice> notices = new ArrayList<>();
        if (StrUtil.isBlank(html)) {
            return notices;
        }
        Matcher block = NOTICE_BLOCK.matcher(html);
        while (block.find()) {
            String item = block.group(1);
            Matcher tag = NOTICE_TAG.matcher(item);
            if (!tag.find() || !AWARD_TAG.equals(tag.group(1).trim())) {
                continue;
            }
            Matcher id = NOTICE_ID.matcher(item);
            if (id.find()) {
                notices.add(new Notice(id.group(1)));
            }
        }
        return notices;
    }
}
