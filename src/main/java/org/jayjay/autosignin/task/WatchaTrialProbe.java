package org.jayjay.autosignin.task;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 试用采集：打开产品官网首页，把「真打开过一次」能拿到的事实记下来，交给文案层当依据。
 * <p>
 * 只有产品简介让模型写，写出来的东西永远围着简介转；真打开一次页面才知道首屏写了什么、
 * 有没有免费入口、首页挂了多少个功能入口、加载快不快。这些事实是模型编不出来的，也正是
 * 「没试用就写」最容易被看出来的地方。
 */
final class WatchaTrialProbe {

    private static final Pattern TITLE = Pattern.compile("<title[^>]*>(.*?)</title>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern H1 = Pattern.compile("<h1[^>]*>(.*?)</h1>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern OG_IMAGE = Pattern.compile(
            "<meta[^>]+property=[\"']og:image[\"'][^>]+content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern OG_IMAGE_REVERSED = Pattern.compile(
            "<meta[^>]+content=[\"']([^\"']+)[\"'][^>]+property=[\"']og:image[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern META_DESC = Pattern.compile(
            "<meta[^>]+name=[\"']description[\"'][^>]+content=[\"']([^\"']*)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final Pattern SCRIPT = Pattern.compile("<(script|style)[^>]*>.*?</\\1>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final int TIMEOUT = 15000;
    /** 首屏文案最多留这么长，太长会把提示词顶爆。 */
    private static final int SUMMARY_CHARS = 220;

    private WatchaTrialProbe() {
    }

    static Record probe(String url) {
        if (StrUtil.isBlank(url)) {
            return Record.failed("没有官网地址");
        }
        long started = System.currentTimeMillis();
        // 打开官网要像浏览器：只收 HTML，不发 Content-Type，也不带 API 那套 header
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("User-Agent", CheckInTask.userAgent);
        headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        headers.put("Upgrade-Insecure-Requests", "1");
        try (HttpResponse response = HttpRequest.get(url.trim())
                .headerMap(headers, true)
                .timeout(TIMEOUT)
                .execute()) {
            long millis = System.currentTimeMillis() - started;
            String body = response.body();
            if (StrUtil.isBlank(body)) {
                return Record.failed("首页没有返回内容");
            }
            String title = clean(first(TITLE, body));
            String h1 = clean(first(H1, body));
            String description = clean(first(META_DESC, body));
            String image = absolute(url.trim(), first(OG_IMAGE, body));
            if (image.isEmpty()) {
                image = absolute(url.trim(), first(OG_IMAGE_REVERSED, body));
            }
            String screen = clean(SCRIPT.matcher(body).replaceAll(" "));
            String summary = summarize(title, h1, description, screen);
            if (summary.isEmpty()) {
                return Record.failed("首页没有可读文案");
            }
            return new Record(true, response.getStatus(), millis, summary, image, h1, title, null);
        } catch (Exception e) {
            String reason = StrUtil.blankToDefault(e.getMessage(), "打开官网失败");
            return Record.failed(reason.length() > 60 ? reason.substring(0, 60) : reason);
        }
    }

    /**
     * 拼一句能直接给模型当事实用的话。标题和首屏文案是重点，其余留作补充。
     */
    static String summarize(String title, String h1, String description, String screen) {
        StringBuilder text = new StringBuilder();
        if (!title.isEmpty()) {
            text.append("首页标题是「").append(title).append('」');
        }
        if (!h1.isEmpty() && !h1.equals(title)) {
            append(text, "首屏大字是「" + h1 + "」");
        }
        if (!description.isEmpty() && !description.equals(title)) {
            append(text, "页面自述是「" + shorten(description, 60) + "」");
        }
        String visible = shorten(screen, SUMMARY_CHARS);
        if (!visible.isEmpty()) {
            append(text, "首屏能看到的文字大致是「" + visible + "」");
        }
        return text.length() == 0 ? "" : text.toString();
    }

    private static void append(StringBuilder text, String part) {
        if (text.length() > 0) {
            text.append("；");
        }
        text.append(part);
    }

    private static String first(Pattern pattern, String body) {
        Matcher matcher = pattern.matcher(body);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String clean(String html) {
        if (html == null) {
            return "";
        }
        String text = TAG.matcher(html).replaceAll(" ");
        text = text.replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">");
        return text.replaceAll("\\s+", " ").trim();
    }

    private static String shorten(String text, int limit) {
        String value = StrUtil.trimToEmpty(text);
        return value.length() <= limit ? value : value.substring(0, limit) + "…";
    }

    static String absolute(String base, String url) {
        if (StrUtil.isBlank(url)) {
            return "";
        }
        String value = url.trim();
        if (value.startsWith("//")) {
            return "https:" + value;
        }
        if (value.startsWith("http")) {
            return value;
        }
        if (value.startsWith("/")) {
            int end = base.indexOf('/', base.indexOf("//") + 2);
            return (end > 0 ? base.substring(0, end) : base) + value;
        }
        return "";
    }

    /** 一次试用采集的结果。失败时只带原因，不影响主流程。 */
    static final class Record {
        final boolean ok;
        final int status;
        final long millis;
        final String summary;
        final String imageUrl;
        final String h1;
        final String title;
        final String failure;

        Record(boolean ok, int status, long millis, String summary, String imageUrl, String h1, String title,
               String failure) {
            this.ok = ok;
            this.status = status;
            this.millis = millis;
            this.summary = summary;
            this.imageUrl = imageUrl == null ? "" : imageUrl;
            this.h1 = h1 == null ? "" : h1;
            this.title = title == null ? "" : title;
            this.failure = failure;
        }

        static Record failed(String reason) {
            return new Record(false, 0, 0, "", "", "", "", reason);
        }

        /** 写进提示词的试用记录：只有真打开过才有这一行。 */
        String promptText() {
            if (!ok) {
                return "（这次没有取到官网页面：" + StrUtil.blankToDefault(failure, "未知原因") + "）";
            }
            return summary + "；打开耗时 " + millis + " 毫秒";
        }
    }
}
