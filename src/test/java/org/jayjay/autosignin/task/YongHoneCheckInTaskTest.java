package org.jayjay.autosignin.task;

import org.junit.Test;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class YongHoneCheckInTaskTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 3);

    @Test
    public void skipsTitlesAlreadyPostedToday() {
        String html = ""
                + "<div class=\"nex_Mythreads_Tuiys\"></div>"
                + "<div class=\"nex_forum_lists\"><a class=\"s xst\">签到</a>"
                + "<dd class=\"nex_forumn_post_dateline\">2026-11-3</dd></div>"
                + "<div class=\"nex_forum_lists\"><a class=\"s xst\">上班卡</a>"
                + "<dd class=\"nex_forumn_post_dateline\">2026-11-2</dd></div>"
                + "<div class=\"nex_forum_lists\"><a class=\"s xst\">打卡签到</a>"
                + "<dd class=\"nex_forumn_post_dateline\">2026-11-3</dd></div>"
                + "<div class=\"nex_forum_lists\"><a class=\"s xst\">上班卡</a>"
                + "<dd class=\"nex_forumn_post_dateline\"><span title=\"2026-11-03\">3 小时前</span></dd></div>";

        assertTrue(YongHoneCheckInTask.threadListReady(html));
        assertEquals(new HashSet<>(Arrays.asList("签到", "上班卡")), YongHoneCheckInTask.postedTitlesOn(html, TODAY));
    }

    @Test
    public void acceptsForumRelativeTimeWhenDateTitleIsToday() {
        String html = ""
                + "<div class=\"nex_forum_lists\"><a class=\"s xst\">签到</a>"
                + "<dd class=\"nex_forumn_post_dateline\">今天 09:11</dd></div>";

        assertEquals(Collections.singleton("签到"), YongHoneCheckInTask.postedTitlesOn(html, TODAY));
    }

    @Test
    public void treatsMissingThreadListAsNotReady() {
        assertFalse(YongHoneCheckInTask.threadListReady("<title>提示信息</title>"));
        assertEquals(Collections.emptySet(), YongHoneCheckInTask.postedTitlesOn("", TODAY));
    }
}
