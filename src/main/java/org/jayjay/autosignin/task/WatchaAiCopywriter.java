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
            "我用下来", "体验下来", "赋能", "闭环", "一站式", "数据提升",
            "总的来说", "总而言之", "值得一提的是", "无论是", "综上",
            "首先", "其次", "在当今", "随着"
    };
    /** 把自己写成「只读了页面」的说法，读起来就不像在谈产品本身。 */
    private static final Pattern META = Pattern.compile(
            "(?:介绍|简介|描述|文案|材料|页面|详情|宣传|说明)[里中上]"
                    + "|(?:介绍|简介|描述|文案|材料|页面|详情)(?:写到|写着|提到|说)"
                    + "|(?:号称|宣传说|据称|说是|主打|标榜)");
    /** 声称过去已经发生过的使用行为，属于编造经历。 */
    private static final Pattern PAST_USE = Pattern.compile(
            "(?:上周|上个月|去年|昨天|前天|前几天|前两天|这两天|上次|之前用|已经用|用了|试了|试过"
                    + "|跑了一遍|测了)");
    private static final String[] REVIEW_SHAPES = {
            "先说你在什么场合会想起它，具体到一件事；再说一处你自己还没把握、想找个例子验证的地方。",
            "先说它该替你省掉哪一步，把这一步讲具体；再说它可能接不住的那种情形。",
            "先说哪类人会被它打动，落到一个人；再说你自己最不放心的一点。",
            "先说它最实在的那个能力给你什么判断；再说你觉得它容易被高估或被用歪的地方。",
            "先说你现在的做法和换成它之间的取舍；再说你打算怎么判断值不值。"
    };
    private static final String[] POST_ANGLES = {
            "只抛一个它没讲清的问题，像在评论区问人",
            "只聊聊它适合谁，说说你想到的某一种人",
            "只提一个用之前想先弄明白的口径"
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
        List<String> anchors = anchorsFor(name, text);
        if (anchors.size() < 2) {
            return DraftResult.failed("推荐产品介绍太短");
        }
        int shape = (int) Math.floorMod(LocalDate.now(SHANGHAI).toEpochDay() + productId, (long) REVIEW_SHAPES.length);
        String user = "体裁：猹评\n"
                + productBrief(name, slogan, categories, text, anchors)
                + "写法：" + REVIEW_SHAPES[shape] + "\n"
                + "返回 {\"paragraphs\":[\"第一段\",\"第二段\"],\"vote\":\"GOOD 或 BAD\"}\n"
                + "两段合计 120 到 220 个字，读起来像随口说的。正文里至少一处自然地带出产品名或某个功能名。\n"
                + "写你自己的场景和判断，不要复述参数：分辨率、时长、价格、额度这类数字别抄进正文，"
                + "需要提到就说它给你什么感觉。\n"
                + "整篇不要出现「介绍」「简介」「页面」「文案」「材料」「主打」「号称」「说是」这些词，"
                + "也不要写「还没写清」这种交代出处的句子，就当这些认识是你自己的判断。\n"
                + "vote 是你自己的态度：觉得想找机会试试就 GOOD，觉得不打算用或没被说服就 BAD。"
                + "vote 要和正文的语气一致，正文说想试就别投 BAD。";
        return generate(user, true, text, anchors);
    }

    DraftResult writePost(String name, String slogan, String categories, String description, int publishedCount) {
        String text = limit(description);
        List<String> anchors = anchorsFor(name, text);
        if (anchors.size() < 2) {
            return DraftResult.failed("推荐产品介绍太短");
        }
        int angle = Math.min(Math.max(publishedCount, 0), POST_ANGLES.length - 1);
        String user = "体裁：帖子\n"
                + productBrief(name, slogan, categories, text, anchors)
                + "角度：" + POST_ANGLES[angle] + "\n"
                + "返回 {\"title\":\"标题\",\"paragraphs\":[\"正文\"]}\n"
                + "标题不超过 20 个汉字。正文 60 到 160 个字，写成三到五句，不要写成测评正文。\n"
                + "正文里至少一处自然地带出产品名或某个功能名。不要复述参数和数字，"
                + "也不要出现「介绍」「简介」「页面」「主打」「号称」「说是」这些词。";
        return generate(user, false, text, anchors);
    }

    private DraftResult generate(String user, boolean review, String source, List<String> anchors) {
        String system = "你是观猹社区里的一个普通用户，在用自己的话讲对一个产品的判断，不是在做测评、写文案或汇报。\n"
                + "语气像平时发帖：句子短，有转折，可以有点犹豫和小情绪，用「我」「你」这种口吻，避免排比句、分点、总结句和广告腔。\n"
                + "写具体的场景和具体的取舍：说清它会用在哪件事上、替代掉你现在的哪一步、哪类人会觉得值。\n"
                + "不要写成对材料的转述：不要把「介绍」「简介」「页面」「文案」「主打」「号称」这类词写进正文，也不要写「材料里提到」这种话。\n"
                + "不要编造已经发生过的使用经历：不能写「上周给客户做短片」「我试了三天」「用了两天发现」这类过去发生的事，"
                + "也不能编造测试数据、他人评价和具体数字。要说自己，就说打算怎么用、准备拿什么例子验证。\n"
                + "不要使用这些说法：亲测、实测、用过、用了一段时间、用了几个月、我用下来、体验下来、赋能、闭环、一站式、数据提升。\n"
                + "只返回 JSON，不要使用 Markdown。";
        String feedback = null;
        String lastFailure = "返回内容无法读取";
        for (int attempt = 0; attempt < 2; attempt++) {
            String prompt = feedback == null ? user : user + "\n上次的问题：" + feedback + "。请重写。";
            String content;
            try {
                content = client.complete(system, prompt);
            } catch (Exception e) {
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
        List<String> paragraphs = paragraphs(json);
        String failure = rejectReview(paragraphs, source, anchors);
        if (failure != null) {
            return DraftResult.failed(failure);
        }
        return DraftResult.review(paragraphs, voteValue(json.getStr("vote")));
    }

    static int voteValue(String vote) {
        return "BAD".equalsIgnoreCase(StrUtil.trimToEmpty(vote)) ? 2 : 1;
    }

    private static DraftResult interpretPost(String content, String source, List<String> anchors) {
        JSONObject json = extractJson(content);
        if (json == null) {
            return DraftResult.failed("返回内容无法读取");
        }
        List<String> paragraphs = paragraphs(json);
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
        if (META.matcher(joined).find()) {
            return "正文里交代了出处";
        }
        if (PAST_USE.matcher(joined).find()) {
            return "写了没有发生过的使用经历";
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
        if (META.matcher(body).find()) {
            return "正文里交代了出处";
        }
        if (PAST_USE.matcher(body).find()) {
            return "写了没有发生过的使用经历";
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

    /**
     * 可引用材料。产品名本身就是最容易自然出现的词，先放进去；
     * 正文里提到产品名也算带上了材料，不该因为介绍里没重复产品名而判失败。
     */
    static List<String> anchorsFor(String name, String text) {
        LinkedHashSet<String> anchors = new LinkedHashSet<String>();
        String product = StrUtil.trimToEmpty(name);
        if (product.length() >= 2 && product.length() <= 18) {
            anchors.add(product);
        }
        anchors.addAll(findAnchors(text));
        List<String> result = new ArrayList<String>(anchors);
        return result.size() > 8 ? new ArrayList<String>(result.subList(0, 8)) : result;
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
        return parseObject(json);
    }

    private static JSONObject parseObject(String json) {
        try {
            return JSONUtil.parseObj(json);
        } catch (RuntimeException e) {
            return null;
        }
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

    private static List<String> paragraphs(JSONObject json) {
        Object value = json.get("paragraphs");
        if (value instanceof JSONArray) {
            List<String> lines = textList((JSONArray) value);
            if (lines.size() == 1) {
                return splitLines(lines.get(0));
            }
            return lines;
        }
        if (value != null) {
            return splitLines(String.valueOf(value));
        }
        return new ArrayList<String>();
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
        String checked = text.replace("没用过", "").replace("没有用过", "").replace("还没用过", "");
        for (String phrase : BANNED) {
            if (checked.contains(phrase)) {
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
        final int voteValue;
        final String failure;

        private DraftResult(String title, List<String> paragraphs, int voteValue, String failure) {
            this.title = title;
            this.paragraphs = paragraphs;
            this.voteValue = voteValue;
            this.failure = failure;
        }

        boolean ok() {
            return failure == null;
        }

        static DraftResult review(List<String> paragraphs, int voteValue) {
            return new DraftResult(null, paragraphs, voteValue, null);
        }

        static DraftResult post(String title, List<String> paragraphs) {
            return new DraftResult(title, paragraphs, 1, null);
        }

        static DraftResult failed(String failure) {
            return new DraftResult(null, null, 1, failure);
        }
    }
}
