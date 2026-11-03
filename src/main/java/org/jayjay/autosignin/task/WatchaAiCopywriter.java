package org.jayjay.autosignin.task;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.jayjay.autosignin.util.AiClient;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class WatchaAiCopywriter {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final Pattern AUDIENCE = Pattern.compile("(?:面向|适合)[^，。；、\\s]{2,12}");
    private static final Pattern NUMBER = Pattern.compile("\\d{2,}(?:种|款|个|天|%)?");
    private static final Pattern LABEL = Pattern.compile("([\\u4e00-\\u9fffA-Za-z0-9]{2,12})[：:]");
    private static final Pattern UNRESOLVED = Pattern.compile("(?:但是|还没|没有|不再)[^，。；]{2,16}");
    private static final Pattern BRACKET = Pattern.compile("【([^】]{2,12})】|「([^」]{2,12})」");
    private static final Pattern ENGLISH = Pattern.compile("[A-Za-z]{6,24}");
    private static final Set<String> ENGLISH_STOP = new HashSet<String>(Arrays.asList(
            "there", "their", "these", "those", "which", "where", "about", "would", "could", "should",
            "other", "using", "based", "current", "currently", "however", "beyond", "within", "without",
            "through", "between", "because", "before", "after", "while", "being", "having", "still",
            "often", "across", "around", "another"));
    private static final String[] BANNED = {
            "亲测", "实测", "用过", "用了一段时间", "用了几个月", "用了三个月",
            "我用下来", "体验下来", "赋能", "闭环", "一站式", "数据提升"
    };
    private static final String[] REVIEW_SHAPES = {
            "第一段把功能拆成具体的几件事，第二段写一个介绍没写清、使用前要核实的问题。",
            "第一段写它要解决的现状，第二段写你会把它当成什么，不会把它当成什么。",
            "第一段写适用人群和主要用处，第二段写要拿哪个例子来核对。"
    };
    private static final String[] POST_ANGLES = {
            "只问一个介绍里没写清的问题",
            "只写它适合谁",
            "只写使用前要先核对的一个口径"
    };

    private final AiClient client;

    WatchaAiCopywriter(Map<String, String> environment) {
        this.client = new AiClient(environment, "");
    }

    boolean isReady() {
        return client.isReady();
    }

    boolean isPartial() {
        return client.isPartial();
    }

    DraftResult writeReview(String name, String slogan, String categories, String description, int productId) {
        String text = limit(description);
        List<String> anchors = findAnchors(text);
        if (anchors.size() < 2) {
            return DraftResult.failed("推荐产品介绍太短");
        }
        int shape = (int) Math.floorMod(LocalDate.now(SHANGHAI).toEpochDay() + productId, REVIEW_SHAPES.length);
        String user = "体裁：猹评\n"
                + productBrief(name, slogan, categories, text, anchors)
                + "写法：" + REVIEW_SHAPES[shape] + "\n"
                + "返回 {\"paragraphs\":[\"第一段\",\"第二段\"]}\n"
                + "两段合计 120 到 220 个字。两段里要原样出现至少一个可引用材料。";
        return generate(user, true, text, anchors);
    }

    DraftResult writePost(String name, String slogan, String categories, String description, int publishedCount) {
        String text = limit(description);
        List<String> anchors = findAnchors(text);
        if (anchors.size() < 2) {
            return DraftResult.failed("推荐产品介绍太短");
        }
        int angle = Math.min(Math.max(publishedCount, 0), POST_ANGLES.length - 1);
        String user = "体裁：帖子\n"
                + productBrief(name, slogan, categories, text, anchors)
                + "角度：" + POST_ANGLES[angle] + "\n"
                + "返回 {\"title\":\"标题\",\"paragraphs\":[\"正文\"]}\n"
                + "标题不超过 20 个汉字。正文 60 到 160 个字，写成三到五句，不要写成测评正文。\n"
                + "正文里要原样出现至少一个可引用材料。";
        return generate(user, false, text, anchors);
    }

    private DraftResult generate(String user, boolean review, String source, List<String> anchors) {
        String system = "你在为观猹写一条第一印象。只根据给出的产品介绍，不编造使用时长、个人经历、测试数据和他人评价。\n"
                + "不要使用这些说法：亲测、实测、用过、用了一段时间、用了几个月、用了三个月、我用下来、体验下来、赋能、闭环、一站式、数据提升。\n"
                + "可以写打算去核实什么，不要写成已经用过。\n"
                + "只返回 JSON，不要使用 Markdown。";
        String feedback = null;
        String lastFailure = "返回内容无法读取";
        for (int attempt = 0; attempt < 2; attempt++) {
            String prompt = feedback == null ? user : user + "\n上次的问题：" + feedback + "。请重写。";
            String content;
            try {
                content = client.complete(system, prompt);
            } catch (RuntimeException e) {
                lastFailure = StrUtil.blankToDefault(e.getMessage(), "文案生成失败");
                feedback = "服务没有返回可用文案";
                continue;
            }
            DraftResult result = review ? interpretReview(content, source, anchors) : interpretPost(content, source, anchors);
            if (result.ok()) {
                return result;
            }
            lastFailure = result.failure;
            feedback = result.failure;
        }
        return DraftResult.failed(lastFailure);
    }

    private static DraftResult interpretReview(String content, String source, List<String> anchors) {
        JSONObject json = extractJson(content);
        if (json == null) {
            return DraftResult.failed("返回内容无法读取");
        }
        List<String> paragraphs = textList(json.getJSONArray("paragraphs"));
        if (paragraphs.size() == 1) {
            paragraphs = splitLines(paragraphs.get(0));
        }
        String failure = rejectReview(paragraphs, source, anchors);
        if (failure != null) {
            return DraftResult.failed(failure);
        }
        return DraftResult.review(paragraphs);
    }

    private static DraftResult interpretPost(String content, String source, List<String> anchors) {
        JSONObject json = extractJson(content);
        if (json == null) {
            return DraftResult.failed("返回内容无法读取");
        }
        List<String> paragraphs = textList(json.getJSONArray("paragraphs"));
        String failure = rejectPost(json.getStr("title"), paragraphs, source, anchors);
        if (failure != null) {
            return DraftResult.failed(failure);
        }
        return DraftResult.post(cleanLine(json.getStr("title")), paragraphs);
    }

    static String rejectReview(List<String> paragraphs, String source, List<String> anchors) {
        if (paragraphs == null || paragraphs.size() != 2) {
            return "返回内容不是两段";
        }
        String joined = join(paragraphs);
        String banned = banned(joined);
        if (banned != null) {
            return banned;
        }
        if (copiesSource(source, joined)) {
            return "复制了介绍原句";
        }
        if (!containsAnchor(joined, anchors)) {
            return "没有带上产品材料";
        }
        int length = textLength(joined);
        if (length < 100 || length > 260) {
            return "篇幅不合适";
        }
        return null;
    }

    static String rejectPost(String title, List<String> paragraphs, String source, List<String> anchors) {
        String cleanedTitle = cleanLine(title);
        if (cleanedTitle.isEmpty()) {
            return "标题为空";
        }
        if (displayWidth(cleanedTitle) > 64) {
            return "标题过长";
        }
        if (paragraphs == null || paragraphs.isEmpty()) {
            return "返回内容无法读取";
        }
        String joined = cleanedTitle + join(paragraphs);
        String body = join(paragraphs);
        String banned = banned(joined);
        if (banned != null) {
            return banned;
        }
        if (copiesSource(source, joined)) {
            return "复制了介绍原句";
        }
        if (!containsAnchor(joined, anchors)) {
            return "没有带上产品材料";
        }
        int length = textLength(body);
        if (length < 40 || length > 200) {
            return "篇幅不合适";
        }
        return null;
    }

    static List<String> findAnchors(String text) {
        LinkedHashSet<String> anchors = new LinkedHashSet<String>();
        if (StrUtil.isBlank(text)) {
            return new ArrayList<String>();
        }
        collectBracket(text, anchors);
        collect(AUDIENCE, text, anchors);
        collect(NUMBER, text, anchors);
        Matcher labels = LABEL.matcher(text);
        while (labels.find()) {
            addAnchor(anchors, labels.group(1));
        }
        collect(UNRESOLVED, text, anchors);
        Matcher english = ENGLISH.matcher(text);
        int englishCount = 0;
        while (english.find() && englishCount < 4) {
            String word = english.group();
            if (!ENGLISH_STOP.contains(word.toLowerCase())) {
                addAnchor(anchors, word);
                englishCount++;
            }
        }
        if (anchors.size() < 2) {
            addRepeatedTopics(text, anchors);
        }
        List<String> result = new ArrayList<String>(anchors);
        return result.size() > 8 ? new ArrayList<String>(result.subList(0, 8)) : result;
    }

    static String plainText(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof JSONObject) {
            return walk(value);
        }
        if (value instanceof JSONArray) {
            return walk(value);
        }
        String text = String.valueOf(value).trim();
        if ((text.startsWith("{") || text.startsWith("[")) && JSONUtil.isTypeJSON(text)) {
            return walk(JSONUtil.parse(text));
        }
        return text.replaceAll("\\s+", " ").trim();
    }

    static String limit(String text) {
        String value = text == null ? "" : text.trim();
        return value.length() > 2000 ? value.substring(0, 2000) : value;
    }

    static JSONObject extractJson(String content) {
        if (StrUtil.isBlank(content)) {
            return null;
        }
        String trimmed = content.trim();
        if (trimmed.startsWith("```")) {
            int firstLine = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstLine >= 0 && lastFence > firstLine) {
                trimmed = trimmed.substring(firstLine + 1, lastFence).trim();
            }
        }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        String json = trimmed.substring(start, end + 1);
        return JSONUtil.isTypeJSON(json) ? JSONUtil.parseObj(json) : null;
    }

    private static String productBrief(String name, String slogan, String categories, String description,
                                       List<String> anchors) {
        return "产品名：" + blank(name, "推荐产品") + "\n"
                + "口号：" + blank(slogan, "无") + "\n"
                + "分类：" + blank(categories, "未分类") + "\n"
                + "介绍：" + description + "\n"
                + "可引用材料：" + join(anchors) + "\n";
    }

    private static void collect(Pattern pattern, String text, Set<String> anchors) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            addAnchor(anchors, matcher.group());
        }
    }

    private static void collectBracket(String text, Set<String> anchors) {
        Matcher matcher = BRACKET.matcher(text);
        while (matcher.find()) {
            addAnchor(anchors, matcher.group(1) == null ? matcher.group(2) : matcher.group(1));
        }
    }

    private static void addRepeatedTopics(String text, Set<String> anchors) {
        Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        for (int offset = 0; offset + 4 <= text.length(); offset++) {
            if (!isHan(text.charAt(offset), text, offset)) {
                continue;
            }
            String gram = text.substring(offset, offset + 4);
            Integer count = counts.get(gram);
            counts.put(gram, count == null ? 1 : count + 1);
        }
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() >= 2) {
                addAnchor(anchors, entry.getKey());
            }
            if (anchors.size() >= 2) {
                return;
            }
        }
    }

    private static boolean isHan(char first, String text, int offset) {
        for (int index = 0; index < 4; index++) {
            if (Character.UnicodeScript.of(text.charAt(offset + index)) != Character.UnicodeScript.HAN) {
                return false;
            }
        }
        return Character.UnicodeScript.of(first) == Character.UnicodeScript.HAN;
    }

    private static void addAnchor(Set<String> anchors, String value) {
        if (value == null) {
            return;
        }
        String anchor = value.trim();
        if (anchor.length() < 2 || anchor.length() > 18) {
            return;
        }
        anchors.add(anchor);
    }

    private static String walk(Object node) {
        StringBuilder text = new StringBuilder();
        appendText(node, text);
        return text.toString().replaceAll("\\s+", " ").trim();
    }

    private static void appendText(Object node, StringBuilder text) {
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            if ("text".equals(object.getStr("type")) && object.getStr("text") != null) {
                if (text.length() > 0) {
                    text.append(' ');
                }
                text.append(object.getStr("text"));
            }
            appendText(object.get("content"), text);
            return;
        }
        if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int index = 0; index < array.size(); index++) {
                appendText(array.get(index), text);
            }
        }
    }

    private static List<String> textList(JSONArray array) {
        List<String> lines = new ArrayList<String>();
        if (array == null) {
            return lines;
        }
        for (int index = 0; index < array.size(); index++) {
            Object value = array.get(index);
            if (value == null) {
                continue;
            }
            String text = cleanLine(String.valueOf(value));
            if (!text.isEmpty()) {
                lines.add(text);
            }
        }
        return lines;
    }

    private static List<String> splitLines(String text) {
        List<String> lines = new ArrayList<String>();
        for (String part : text.split("\\n+")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                lines.add(trimmed);
            }
        }
        return lines;
    }

    private static String banned(String text) {
        for (String phrase : BANNED) {
            if (text.contains(phrase)) {
                return "出现了不适合的说法";
            }
        }
        return null;
    }

    static boolean copiesSource(String source, String output) {
        String compactSource = source == null ? "" : source.replaceAll("\\s+", "");
        String compactOutput = output == null ? "" : output.replaceAll("\\s+", "");
        if (compactSource.length() < 20 || compactOutput.isEmpty()) {
            return false;
        }
        for (int index = 0; index + 20 <= compactSource.length(); index += 5) {
            if (compactOutput.contains(compactSource.substring(index, index + 20))) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAnchor(String text, List<String> anchors) {
        if (anchors == null || text == null) {
            return false;
        }
        String folded = text.toLowerCase();
        for (String anchor : anchors) {
            if (anchor != null && anchor.length() >= 2 && folded.contains(anchor.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private static int textLength(String text) {
        return text == null ? 0 : text.replaceAll("\\s+", "").length();
    }

    static int displayWidth(String text) {
        int width = 0;
        for (int index = 0; index < text.length(); ) {
            int codePoint = text.codePointAt(index);
            width += codePoint > 255 ? 2 : 1;
            index += Character.charCount(codePoint);
        }
        return width;
    }

    private static String cleanLine(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\r', ' ').replace('\n', ' ').trim();
    }

    private static String join(List<String> parts) {
        StringBuilder text = new StringBuilder();
        for (String part : parts) {
            if (StrUtil.isBlank(part)) {
                continue;
            }
            if (text.length() > 0) {
                text.append('、');
            }
            text.append(part.trim());
        }
        return text.toString();
    }

    private static String blank(String value, String fallback) {
        return StrUtil.isBlank(value) ? fallback : value.trim();
    }

    static final class DraftResult {
        final String title;
        final List<String> paragraphs;
        final String failure;

        private DraftResult(String title, List<String> paragraphs, String failure) {
            this.title = title;
            this.paragraphs = paragraphs;
            this.failure = failure;
        }

        boolean ok() {
            return failure == null;
        }

        static DraftResult review(List<String> paragraphs) {
            return new DraftResult(null, paragraphs, null);
        }

        static DraftResult post(String title, List<String> paragraphs) {
            return new DraftResult(title, paragraphs, null);
        }

        static DraftResult failed(String failure) {
            return new DraftResult(null, null, failure);
        }
    }
}
