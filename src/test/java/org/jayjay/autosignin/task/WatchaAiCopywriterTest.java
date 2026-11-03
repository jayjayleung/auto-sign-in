package org.jayjay.autosignin.task;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.jayjay.autosignin.util.AiClient;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class WatchaAiCopywriterTest {

    private static final String PRODUCT_7 = "面向科研人员。提供深度检索，还没写清推荐依据。";
    private static final String REVIEW_P1 = "这款工具面向科研人员，介绍里把深度检索写成主要能力。它想把筛选收成一件具体的事，先看和题目有关的结果，再决定要不要继续。";
    private static final String REVIEW_P2 = "我会先核实推荐是怎么来的。介绍里写到还没写清推荐依据，所以正式拿来查资料之前，我会用一个自己熟悉的题目看结果还在不在点上。";

    @Test
    public void buildsChatEndpoint() {
        assertEquals("http://127.0.0.1/v1/chat/completions", AiClient.chatEndpoint("http://127.0.0.1/v1/"));
        assertEquals("http://127.0.0.1/v1/chat/completions",
                AiClient.chatEndpoint("http://127.0.0.1/v1/chat/completions"));
    }

    @Test
    public void readsJsonWrappedInFence() {
        JSONObject json = WatchaAiCopywriter.extractJson("```json\n{\"paragraphs\":[\"甲\",\"乙\"]}\n```");

        assertEquals("甲", String.valueOf(json.getJSONArray("paragraphs").get(0)));
    }

    @Test
    public void readsPlainProductDescription() {
        JSONObject doc = JSONUtil.parseObj("{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"提供深度检索\"}]}]}");

        assertTrue(WatchaAiCopywriter.plainText(doc.toString()).contains("深度检索"));
    }

    @Test
    public void findsAudienceAndRepeatedTopic() {
        List<String> anchors = WatchaAiCopywriter.findAnchors(
                "时者玄空是玄空飞星排盘工具。玄空飞星重视时间规则，适合学习者。");

        assertTrue(anchors.size() >= 2);
        assertTrue(anchors.contains("适合学习者"));
        assertTrue(anchors.contains("玄空飞星"));
    }

    @Test
    public void skipsThinDescription() {
        assertTrue(WatchaAiCopywriter.findAnchors("一个工具。").size() < 2);
    }

    @Test
    public void acceptsGroundedReview() {
        List<String> anchors = WatchaAiCopywriter.findAnchors(PRODUCT_7);

        assertTrue(anchors.size() >= 2);
        assertNull(WatchaAiCopywriter.rejectReview(Arrays.asList(REVIEW_P1, REVIEW_P2), PRODUCT_7, anchors));
    }

    @Test
    public void rejectsBannedPhraseAndCopiedIntroduction() {
        assertEquals("出现了不适合的说法", WatchaAiCopywriter.rejectReview(
                Arrays.asList("亲测很好用。", "第二段也写了赋能。"), PRODUCT_7, Arrays.asList("科研人员")));

        String source = "这是一段用于复制检测的产品介绍文本内容补充";
        assertEquals("复制了介绍原句", WatchaAiCopywriter.rejectReview(
                Arrays.asList(source.substring(0, 20) + "尾巴", "第二段"), source, Arrays.asList("复制检测")));
    }

    @Test
    public void mapsVoteToValue() {
        assertEquals(1, WatchaAiCopywriter.voteValue("GOOD"));
        assertEquals(2, WatchaAiCopywriter.voteValue("bad"));
        assertEquals(2, WatchaAiCopywriter.voteValue(" BAD "));
        assertEquals(1, WatchaAiCopywriter.voteValue(null));
        assertEquals(1, WatchaAiCopywriter.voteValue("随便"));
    }

    @Test
    public void rejectsWideTitle() {
        StringBuilder title = new StringBuilder();
        for (int index = 0; index < 40; index++) {
            title.append('题');
        }

        assertEquals("标题过长", WatchaAiCopywriter.rejectPost(title.toString(),
                Arrays.asList("面向科研人员的介绍还没写清推荐依据，所以发布前会先看推荐是怎么来的。"),
                PRODUCT_7, Arrays.asList("面向科研人员")));
        assertEquals("标题为空", WatchaAiCopywriter.rejectPost("  ",
                Arrays.asList("面向科研人员的介绍还没写清推荐依据，所以发布前会先看推荐是怎么来的。"),
                PRODUCT_7, Arrays.asList("面向科研人员")));
    }
}
