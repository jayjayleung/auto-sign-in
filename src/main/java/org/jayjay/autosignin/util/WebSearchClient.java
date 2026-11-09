package org.jayjay.autosignin.util;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 执行兼容 AI 服务返回的搜索查询，返回实际搜索摘要和来源。 */
class WebSearchClient {

    private static final String DEFAULT_ENDPOINT = "https://www.bing.com/search";
    private static final int TIMEOUT = 20000;
    private static final int MAX_RESULTS = 6;
    private static final String USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36";

    private final String endpoint;

    WebSearchClient() {
        this(DEFAULT_ENDPOINT);
    }

    WebSearchClient(String endpoint) {
        this.endpoint = StrUtil.isBlank(endpoint) ? DEFAULT_ENDPOINT : endpoint.trim();
    }

    String search(Set<String> queries) {
        long started = System.currentTimeMillis();
        Map<String, String> results = new LinkedHashMap<String, String>();
        String failure = "文案生成失败：公开搜索没有返回可用资料或来源";
        int searched = 0;
        for (String query : queries) {
            if (searched++ >= 2) {
                break;
            }
            for (int attempt = 1; attempt <= 2; attempt++) {
                String error = fetch(query, results);
                if (error == null) {
                    break;
                }
                failure = error;
                System.out.println("[AI] 公开搜索失败（第 " + attempt + " 次）：" + error);
            }
            if (results.size() >= 3) {
                break;
            }
        }
        if (results.isEmpty()) {
            throw new IllegalStateException(failure);
        }
        System.out.println("[AI] 公开搜索完成，取得 " + results.size() + " 条资料，耗时 "
                + seconds(System.currentTimeMillis() - started) + " 秒");
        StringBuilder material = new StringBuilder("以下为实际联网搜索的结果摘要，仅采用其中可确认的产品事实。\n");
        for (Map.Entry<String, String> result : results.entrySet()) {
            material.append(result.getValue()).append("\n网址：").append(result.getKey()).append("\n\n");
        }
        return material.append("检索来源：\n").append(String.join("\n", results.keySet())).toString();
    }

    /** 请求失败或返回非 200 时给出原因以便重试；页面正常但没有结果不算失败，不重试。 */
    private String fetch(String query, Map<String, String> results) {
        try (HttpResponse response = HttpRequest.get(endpoint)
                .form("q", query)
                .form("count", 5)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html")
                .setMaxRedirectCount(3)
                .timeout(TIMEOUT)
                .execute()) {
            if (response.getStatus() != 200) {
                return "文案生成失败：公开搜索返回 HTTP " + response.getStatus();
            }
            for (Element result : Jsoup.parse(response.body()).select("li.b_algo")) {
                Element link = result.selectFirst("h2 a[href]");
                Element snippet = result.selectFirst(".b_caption p");
                if (snippet == null) {
                    snippet = result.selectFirst("p");
                }
                if (link == null || snippet == null) {
                    continue;
                }
                String url = link.attr("href").trim();
                String title = link.text().trim();
                String summary = snippet.text().trim();
                if (!isSource(url) || title.isEmpty() || summary.isEmpty()) {
                    continue;
                }
                if (!results.containsKey(url) && results.size() < MAX_RESULTS) {
                    results.put(url, "标题：" + limit(title, 160) + "\n摘要：" + limit(summary, 600));
                }
            }
            return null;
        } catch (RuntimeException e) {
            return "文案生成失败：公开搜索请求超时或不可用";
        }
    }

    private static String seconds(long millis) {
        return String.format(Locale.ROOT, "%.1f", millis / 1000.0);
    }

    private static boolean isSource(String url) {
        try {
            URI source = URI.create(url);
            return url.length() <= 1000 && source.getHost() != null && source.getUserInfo() == null
                    && ("https".equals(source.getScheme()) || "http".equals(source.getScheme()));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String limit(String text, int length) {
        return text.length() <= length ? text : text.substring(0, length);
    }
}
