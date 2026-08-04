package org.jayjay.autosignin.task;

import org.junit.Test;

import java.time.DayOfWeek;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class YongHoneCheckInTaskTest {

    @Test
    public void retryCountTracksRetriesInsteadOfAttempts() {
        assertEquals(0, YongHoneCheckInTask.retryCountForAttempt(1));
        assertEquals(1, YongHoneCheckInTask.retryCountForAttempt(2));
    }

    @Test
    public void loginErrorIsExtractedFromKnownErrorContainers() {
        assertEquals("用户名或密码错误",
                YongHoneCheckInTask.extractLoginError(
                        "<div id='messagetext' class='alert_error'><p>用户名或密码错误</p></div>"));
        assertEquals("登录操作过于频繁",
                YongHoneCheckInTask.extractLoginError(
                        "<div class='alert_error'>登录操作过于频繁</div>"));
    }

    @Test
    public void normalLoginPageHasNoExplicitLoginError() {
        assertNull(YongHoneCheckInTask.extractLoginError(
                "<form><input name='username'><input name='password'></form>"));
        assertNull(YongHoneCheckInTask.extractLoginError(
                "<div id='messagetext' class='alert_info'>登录成功，正在跳转</div>"));
        assertNull(YongHoneCheckInTask.extractLoginError(null));
    }

    @Test
    public void commentResultRequiresNewPostMatchingContentAndUser() {
        String html = "<div id='post_100'>"
                + "<a href='forum.php?mod=viewthread&authorid=42'>作者</a>"
                + "<div id='postmessage_100'>旧评论</div></div>"
                + "<div id='post_101'>"
                + "<img src='https://club.yonghongtech.com/uc_server/avatar.php?uid=42&size=middle'>"
                + "<div id='postmessage_101'>签到</div></div>";

        assertTrue(YongHoneCheckInTask.commentResultMatches(
                html, Collections.singletonList("post_100"), "签到", "42"));
        assertFalse(YongHoneCheckInTask.commentResultMatches(
                html, Arrays.asList("post_100", "post_101"), "签到", "42"));
        assertFalse(YongHoneCheckInTask.commentResultMatches(
                html, Collections.singletonList("post_100"), "签到", "43"));
        assertFalse(YongHoneCheckInTask.commentResultMatches(
                html, Collections.singletonList("post_100"), "其他内容", "42"));
    }

    @Test
    public void completedStagesAreNotAvailableForReplay() {
        YongHoneCheckInTask.RunProgress progress = new YongHoneCheckInTask.RunProgress(1, 0);
        progress.beginLottery();
        progress.completeLottery("100", "抽奖成功");

        YongHoneCheckInTask.PostProgress checkIn = progress.checkInPost();
        checkIn.beginCreation();
        checkIn.completeCreation("https://club.yonghongtech.com/thread-1-1-1.html");
        checkIn.beginComment();
        checkIn.completeComment();

        YongHoneCheckInTask.PostProgress work = progress.workPost();
        work.beginCreation();
        work.completeCreation("https://club.yonghongtech.com/thread-2-1-1.html");

        assertTrue(progress.isComplete());
        assertTrue(progress.lotteryCompleted());
        assertTrue(checkIn.isComplete());
        assertTrue(work.isComplete());
        assertEquals("https://club.yonghongtech.com/thread-1-1-1.html", checkIn.threadUrl());
        assertNull(progress.uncertainOperation());

        try {
            progress.beginLottery();
            fail("已完成的抽奖不应再次开始");
        } catch (IllegalStateException expected) {
            // 已完成阶段必须保持不可重放。
        }
        try {
            checkIn.beginCreation();
            fail("已完成的帖子不应再次创建");
        } catch (IllegalStateException expected) {
            // 已完成阶段必须保持不可重放。
        }
    }

    @Test
    public void inFlightLotteryIsReportedAsUncertain() {
        YongHoneCheckInTask.RunProgress progress = new YongHoneCheckInTask.RunProgress(0, 0);

        progress.beginLottery();

        assertFalse(progress.isComplete());
        assertEquals("抽奖结果未确认", progress.uncertainOperation());
    }

    @Test
    public void inFlightPostCreationAndCommentAreReportedAsUncertain() {
        YongHoneCheckInTask.PostProgress post = new YongHoneCheckInTask.PostProgress("签到", 1);

        post.beginCreation();
        assertEquals("论坛签到帖子创建结果未确认", post.uncertainOperation());

        post.completeCreation("https://club.yonghongtech.com/thread-1-1-1.html");
        post.beginComment();
        assertEquals("论坛签到第1次评论结果未确认", post.uncertainOperation());

        post.completeComment();
        assertTrue(post.isComplete());
        assertNull(post.uncertainOperation());
    }

    @Test
    public void dateAndTitleHelpersNormalizeExpectedValues() {
        assertTrue(YongHoneCheckInTask.isDoubleDay(DayOfWeek.TUESDAY));
        assertTrue(YongHoneCheckInTask.isDoubleDay(DayOfWeek.THURSDAY));
        assertFalse(YongHoneCheckInTask.isDoubleDay(DayOfWeek.MONDAY));
        assertFalse(YongHoneCheckInTask.isDoubleDay(DayOfWeek.SUNDAY));
        assertFalse(YongHoneCheckInTask.isDoubleDay(null));

        assertTrue(YongHoneCheckInTask.titlesMatch("  上班卡\n", "上班卡"));
        assertFalse(YongHoneCheckInTask.titlesMatch("签到", "上班卡"));
        assertFalse(YongHoneCheckInTask.titlesMatch("", "签到"));
    }
}
