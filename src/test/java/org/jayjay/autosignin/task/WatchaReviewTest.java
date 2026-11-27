package org.jayjay.autosignin.task;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 猹评的形状、校验和文档结构：老实现写死「必须两段、100 到 260 字」，
 * 这里要保证形状跟着计划走，同时该拦的内容照样拦。
 */
public class WatchaReviewTest {

    private static final String SOURCE = "这是一段产品介绍原文，用来测试是否被整句复制进正文里";
    private static final List<String> ANCHORS = Arrays.asList("可灵", "生成视频");

    private static String body(int chars) {
        StringBuilder text = new StringBuilder();
        while (text.length() < chars) {
            text.append("我用可灵剪了段片子，导出比预想快");
        }
        return text.substring(0, chars);
    }

    /** 段数在不同人格里范围不同，短段用中等人格、多段用长评人格去找。 */
    private static WatchaReviewPlan planWithParagraphs(int paragraphs) {
        WatchaReviewPlan.Persona[] order = {WatchaReviewPlan.Persona.MEDIUM, WatchaReviewPlan.Persona.SHORT,
                WatchaReviewPlan.Persona.LONG};
        for (WatchaReviewPlan.Persona persona : order) {
            for (long seed = 0; seed < 20000; seed++) {
                WatchaReviewPlan plan = WatchaReviewPlan.plan(seed, 0, 0, persona);
                if (!plan.skip && plan.paragraphs == paragraphs) {
                    return plan;
                }
            }
        }
        throw new IllegalStateException("找不到 " + paragraphs + " 段的计划");
    }

    private static List<String> paragraphsFor(WatchaReviewPlan plan) {
        List<String> paragraphs = new ArrayList<String>();
        int each = Math.max(1, plan.targetChars / plan.paragraphs);
        for (int index = 0; index < plan.paragraphs; index++) {
            paragraphs.add(body(each));
        }
        return paragraphs;
    }

    @Test
    public void acceptsPlanShapedReview() {
        for (int count : new int[]{1, 2, 3, 5}) {
            WatchaReviewPlan plan = planWithParagraphs(count);
            List<String> paragraphs = paragraphsFor(plan);
            assertEquals("文案校验（" + count + " 段）",
                    null, WatchaAiCopywriter.rejectReview(paragraphs, "标题", SOURCE, ANCHORS, plan));
        }
    }

    @Test
    public void rejectsWrongParagraphCount() {
        WatchaReviewPlan plan = planWithParagraphs(3);
        List<String> paragraphs = paragraphsFor(plan);
        String failure = WatchaAiCopywriter.rejectReview(paragraphs.subList(0, 2), "标题", SOURCE, ANCHORS, plan);
        assertEquals("段数应该是 3 段", failure);
    }

    @Test
    public void rejectsWrongLength() {
        WatchaReviewPlan plan = planWithParagraphs(2);
        List<String> shortOne = Arrays.asList(body(8), body(8));
        assertEquals("篇幅不合适", WatchaAiCopywriter.rejectReview(shortOne, "标题", SOURCE, ANCHORS, plan));
        String tooLong = body(plan.maxChars() + 50);
        assertEquals("篇幅不合适",
                WatchaAiCopywriter.rejectReview(Arrays.asList(tooLong, body(10)), "标题", SOURCE, ANCHORS, plan));
    }

    @Test
    public void rejectsMissingProductAnchor() {
        WatchaReviewPlan plan = planWithParagraphs(2);
        List<String> paragraphs = Arrays.asList(
                "我折腾了半天才弄明白这个功能该怎么用，后半段倒是顺手",
                "换个场景再试一次，结果和上次差不多，省了不少来回");
        assertEquals("没有带上产品材料",
                WatchaAiCopywriter.rejectReview(paragraphs, "标题", SOURCE, ANCHORS, plan));
    }

    @Test
    public void rejectsMissingHeadingWhenPlanWantsOne() {
        for (long seed = 0; seed < 20000; seed++) {
            WatchaReviewPlan plan = WatchaReviewPlan.plan(seed, 0, 0);
            if (plan.skip || !plan.heading) {
                continue;
            }
            assertEquals("缺少小标题",
                    WatchaAiCopywriter.rejectReview(paragraphsFor(plan), "  ", SOURCE, ANCHORS, plan));
            assertNull("给了小标题就该通过",
                    WatchaAiCopywriter.rejectReview(paragraphsFor(plan), "一个普通的小标题", SOURCE, ANCHORS, plan));
            return;
        }
        throw new IllegalStateException("样本里没有带小标题的计划");
    }

    @Test
    public void documentFollowsPlan() {
        WatchaReviewPlan decorated = null;
        for (long seed = 0; seed < 300000; seed++) {
            WatchaReviewPlan plan = WatchaReviewPlan.plan(seed, 0, 0);
            if (!plan.skip && plan.heading && plan.bold && plan.paragraphs >= 2) {
                decorated = plan;
                break;
            }
        }
        assertNotNull("应该能找到带小标题和加粗的计划", decorated);
        List<String> paragraphs = paragraphsFor(decorated);
        JSONObject doc = WatchaCheckInTask.reviewDocument("我的小标题", paragraphs, decorated, "可灵");
        JSONArray blocks = doc.getJSONArray("content");
        assertEquals("doc", doc.getStr("type"));
        assertEquals(paragraphs.size() + 1, blocks.size());

        JSONObject heading = blocks.getJSONObject(0);
        assertEquals("heading", heading.getStr("type"));
        assertEquals("我的小标题", heading.getJSONArray("content").getJSONObject(0).getStr("text"));

        JSONObject first = blocks.getJSONObject(1);
        assertEquals("paragraph", first.getStr("type"));
        boolean bolded = false;
        for (Object node : first.getJSONArray("content")) {
            JSONObject inline = (JSONObject) node;
            if (inline.getJSONArray("marks") != null && "可灵".equals(inline.getStr("text"))) {
                bolded = true;
            }
        }
        assertTrue("首段应该把产品名标成加粗", bolded);

    }

    @Test
    public void documentWrapsLastParagraphAsQuoteWhenPlanned() {
        WatchaReviewPlan quoted = null;
        for (long seed = 0; seed < 300000; seed++) {
            WatchaReviewPlan plan = WatchaReviewPlan.plan(seed, 0, 0);
            if (!plan.skip && plan.quote && plan.paragraphs >= 2) {
                quoted = plan;
                break;
            }
        }
        assertNotNull("应该能找到带引用的计划", quoted);
        JSONObject doc = WatchaCheckInTask.reviewDocument("", paragraphsFor(quoted), quoted, "可灵");
        JSONArray blocks = doc.getJSONArray("content");
        assertEquals(quoted.paragraphs, blocks.size());
        JSONObject last = blocks.getJSONObject(blocks.size() - 1);
        assertEquals("最后一段应当包成引用块", "blockquote", last.getStr("type"));
        assertEquals("paragraph", last.getJSONArray("content").getJSONObject(0).getStr("type"));
    }

    @Test
    public void documentWithoutDecorationIsPlainParagraphs() {
        WatchaReviewPlan plain = null;
        for (long seed = 0; seed < 20000; seed++) {
            WatchaReviewPlan plan = WatchaReviewPlan.plan(seed, 0, 0);
            if (!plan.skip && !plan.heading && !plan.quote && !plan.bold && plan.paragraphs == 1) {
                plain = plan;
                break;
            }
        }
        assertNotNull(plain);
        JSONObject doc = WatchaCheckInTask.reviewDocument("", paragraphsFor(plain), plain, "可灵");
        JSONArray blocks = doc.getJSONArray("content");
        assertEquals(1, blocks.size());
        assertEquals("paragraph", blocks.getJSONObject(0).getStr("type"));
        assertFalse(blocks.getJSONObject(0).containsKey("marks"));
    }

    @Test
    public void replyMustPickUpSomethingFromTheOriginalReview() {
        String other = "我昨天用它把三份会议纪要整理成一页，主要卡在旧模板没被自动识别，得手动传参考文件。";
        assertEquals("回复太空泛",
                WatchaAiCopywriter.rejectReply("同意，学习了，感谢分享", other));
        assertEquals("回复没有接住对方提到的内容",
                WatchaAiCopywriter.rejectReply("这个我倒是没试过，平时都用另一款", other));
        assertNull("接住旧模板那件事就该通过",
                WatchaAiCopywriter.rejectReply("旧模板那步我也卡了一下，后来干脆把模板另存成新文件才认。", other));
    }

    @Test
    public void replyRejectsCopyLengthAndLinks() {
        String other = "我昨天用它把三份会议纪要整理成一页，主要卡在旧模板没被自动识别。";
        assertEquals("回复长度不合适", WatchaAiCopywriter.rejectReply("太短", other));
        assertEquals("回复复述了原评",
                WatchaAiCopywriter.rejectReply("我昨天用它把三份会议纪要整理成一页，主要卡在旧模板没被自动识别。", other));
        assertNull(WatchaAiCopywriter.rejectReply("会议纪要这几份我也试过，直接给链接它反而不认。",
                "会议纪要这类杂事我一般直接丢进去，但给链接它不认。"));
    }

    @Test
    public void sharedPhraseMeasuresOverlap() {
        assertTrue("无关的两句话不该共享三字片段",
                WatchaAiCopywriter.sharedPhrase("完全无关的一段话", "另一件事情") < 3);
        assertTrue(WatchaAiCopywriter.sharedPhrase("旧模板没被识别", "卡在旧模板这一步") >= 3);
    }

    @Test
    public void replyDocumentIsSingleParagraph() {
        JSONObject doc = WatchaCheckInTask.replyDocument("旧模板那步我也卡了一下");
        assertEquals("doc", doc.getStr("type"));
        JSONArray blocks = doc.getJSONArray("content");
        assertEquals(1, blocks.size());
        assertEquals("paragraph", blocks.getJSONObject(0).getStr("type"));
        assertEquals("旧模板那步我也卡了一下",
                blocks.getJSONObject(0).getJSONArray("content").getJSONObject(0).getStr("text"));
    }

    @Test
    public void paragraphWithBoldFallsBackWhenNameMissing() {
        JSONObject paragraph = WatchaCheckInTask.paragraphWithBold("完全没有提到那个名字", "可灵");
        JSONArray content = paragraph.getJSONArray("content");
        assertEquals(1, content.size());
        assertEquals("完全没有提到那个名字", content.getJSONObject(0).getStr("text"));
    }
}
