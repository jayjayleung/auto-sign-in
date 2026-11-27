package org.jayjay.autosignin.task;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 西门子锁定探测的测试。
 * <p>
 * 全部用临时目录和构造出来的响应文本，不发任何请求：探测的价值在于「判断准」，
 * 一旦把「会话过期」误判成「已解锁」，就会让人重新打开每日任务并二次撞锁。
 */
public class SiemensLockProbeTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    // ==================== 响应归类 ====================

    @Test
    public void 兑换页锁定提示判为锁定() {
        String body = "<script>alert('您的账号被锁定，不能使用该功能，请联系管理员。');"
                + " location.href='/MySupport/index.aspx'</script>";
        assertEquals(SiemensLockProbe.LOCKED, SiemensLockProbe.classify(200, body));
    }

    @Test
    public void 提问页锁定提示也判为锁定() {
        String body = "<script type='text/javascript'>alert('锁定用户，禁止提问！');"
                + "window.location.href='/service/answer/default.html';</script>";
        assertEquals(SiemensLockProbe.LOCKED, SiemensLockProbe.classify(200, body));
    }

    @Test
    public void 跳登录页判为会话过期而非解锁() {
        // 未登录时兑换页 302 到 Login.aspx：既不是锁定也不能算恢复
        assertEquals(SiemensLockProbe.SESSION_EXPIRED, SiemensLockProbe.classify(302, ""));
    }

    @Test
    public void 正常兑换页判为已解锁() {
        String body = repeat('x', SiemensLockProbe.PAGE_MIN_LENGTH);
        assertEquals(SiemensLockProbe.UNLOCKED, SiemensLockProbe.classify(200, body));
    }

    @Test
    public void 页面太短或请求失败都判为不确定() {
        // 200 但只有一百多字节：既不是锁定提示也不是正常页面，不能猜
        assertEquals(SiemensLockProbe.UNKNOWN, SiemensLockProbe.classify(200, "<html>短页面</html>"));
        assertEquals(SiemensLockProbe.UNKNOWN, SiemensLockProbe.classify(0, ""));
        assertEquals(SiemensLockProbe.UNKNOWN, SiemensLockProbe.classify(500, repeat('x', 5000)));
    }

    @Test
    public void 站点错误页不能当成已解锁() {
        // 错误页有 4 万多字节，只看长度会误判成正常页面，从而误报「已解锁」
        String errorPage = "<html><body>非常抱歉，您当前访问网页不存在或已经过期。"
                + "网页错误描述: " + repeat('x', 40000) + "</body></html>";
        assertEquals(SiemensLockProbe.UNKNOWN, SiemensLockProbe.classify(200, errorPage));
    }

    // ==================== 记录合并 ====================

    @Test
    public void 首次锁定记录起始时间() {
        JSONObject json = SiemensLockProbe.merge(null, SiemensLockProbe.LOCKED,
                "2026-11-27T12:00:00+08:00", "");
        assertEquals("2026-11-27T12:00:00+08:00", json.getStr("firstSeenLocked"));
        assertEquals("", json.getStr("unlockedAt"));
        assertEquals(SiemensLockProbe.LOCKED, json.getStr("status"));
    }

    @Test
    public void 持续锁定不覆盖起始时间() {
        JSONObject first = SiemensLockProbe.merge(null, SiemensLockProbe.LOCKED,
                "2026-11-27T12:00:00+08:00", "");
        JSONObject second = SiemensLockProbe.merge(first, SiemensLockProbe.LOCKED,
                "2026-12-03T12:00:00+08:00", "");
        // 起始时间必须保持 11-27，否则每天都重置会把封锁时长算成 0 天
        assertEquals("2026-11-27T12:00:00+08:00", second.getStr("firstSeenLocked"));
        assertEquals("2026-12-03T12:00:00+08:00", second.getStr("lastLockedAt"));
    }

    @Test
    public void 会话过期这类中间态不改起始时间() {
        JSONObject locked = SiemensLockProbe.merge(null, SiemensLockProbe.LOCKED,
                "2026-11-27T12:00:00+08:00", "");
        JSONObject expired = SiemensLockProbe.merge(locked, SiemensLockProbe.SESSION_EXPIRED,
                "2026-11-28T12:00:00+08:00", "");
        assertEquals("2026-11-27T12:00:00+08:00", expired.getStr("firstSeenLocked"));
        assertEquals("", expired.getStr("unlockedAt"));
    }

    @Test
    public void 解锁记录恢复时间() {
        JSONObject locked = SiemensLockProbe.merge(null, SiemensLockProbe.LOCKED,
                "2026-11-27T12:00:00+08:00", "");
        JSONObject unlocked = SiemensLockProbe.merge(locked, SiemensLockProbe.UNLOCKED,
                "2026-12-04T12:00:00+08:00", "");
        assertEquals("2026-12-04T12:00:00+08:00", unlocked.getStr("unlockedAt"));
        assertEquals(SiemensLockProbe.UNLOCKED, unlocked.getStr("status"));
    }

    @Test
    public void 第二轮锁定重新计算起点() {
        JSONObject locked = SiemensLockProbe.merge(null, SiemensLockProbe.LOCKED,
                "2026-11-27T12:00:00+08:00", "");
        JSONObject unlocked = SiemensLockProbe.merge(locked, SiemensLockProbe.UNLOCKED,
                "2026-12-04T12:00:00+08:00", "");
        JSONObject again = SiemensLockProbe.merge(unlocked, SiemensLockProbe.LOCKED,
                "2027-01-10T12:00:00+08:00", "");
        assertEquals("2027-01-10T12:00:00+08:00", again.getStr("firstSeenLocked"));
        assertEquals("", again.getStr("unlockedAt"));
    }

    @Test
    public void 探测历史按上限裁剪() {
        JSONObject json = null;
        for (int day = 0; day < SiemensLockProbe.MAX_HISTORY + 5; day++) {
            json = SiemensLockProbe.merge(json, SiemensLockProbe.LOCKED,
                    String.format("2026-11-%02dT12:00:00+08:00", (day % 28) + 1), "");
        }
        assertNotNull(json);
        JSONArray history = json.getJSONArray("history");
        assertEquals(SiemensLockProbe.MAX_HISTORY, history.size());
    }

    // ==================== 时长换算 ====================

    @Test
    public void 未解锁时按今天算持续天数() {
        String today = LocalDate.now(SHANGHAI).toString();
        Long days = SiemensLockProbe.lockedDays(today + "T12:00:00+08:00", "");
        assertEquals(0L, days.longValue());

        Long ten = SiemensLockProbe.lockedDays(
                LocalDate.now(SHANGHAI).minusDays(10).toString() + "T12:00:00+08:00", "");
        assertEquals(10L, ten.longValue());
    }

    @Test
    public void 解锁后按恢复日算总时长() {
        Long days = SiemensLockProbe.lockedDays(
                "2026-11-27T12:00:00+08:00", "2026-12-04T12:00:00+08:00");
        assertEquals(7L, days.longValue());
    }

    @Test
    public void 没有基准时间返回空() {
        assertNull(SiemensLockProbe.lockedDays("", ""));
        assertNull(SiemensLockProbe.lockedDays(null, "2026-12-04T12:00:00+08:00"));
        assertNull(SiemensLockProbe.lockedDays("不是时间", ""));
    }

    // ==================== Cookie 读取 ====================

    @Test
    public void 只挑主站域的会话cookie() throws Exception {
        Path file = folder.newFile("siemens-session-abc123.txt").toPath();
        Files.write(file, (
                "acw_tc\tvalue1\twww.ad.siemens.com.cn\t/\t1\t0\n"
                        + "ASP.NET_SessionId\tvalue2\twww.ad.siemens.com.cn\t/\t1\t0\n"
                        + "idsrv\tvalue3\toso.siemens.com.cn\t/\t1\t0\n"
                        + "ciam_identity\tvalue4\tsiemens.com.cn\t/\t0\t0\n"
                        + "broken-line\n")
                .getBytes(StandardCharsets.UTF_8));

        String header = SiemensLockProbe.cookieHeader(file);
        assertTrue(header.contains("acw_tc=value1"));
        assertTrue(header.contains("ASP.NET_SessionId=value2"));
        // 别的子域带上去会被站点当成异常请求，必须过滤掉
        assertFalse(header.contains("idsrv"));
        assertFalse(header.contains("broken-line"));
    }

    @Test
    public void 没有会话文件返回空cookie() throws Exception {
        Path missing = folder.getRoot().toPath().resolve("nope.txt");
        assertEquals("", SiemensLockProbe.cookieHeader(missing));
        assertEquals("", SiemensLockProbe.cookieHeader(null));
    }

    private static String repeat(char value, int count) {
        StringBuilder text = new StringBuilder(count);
        for (int index = 0; index < count; index++) {
            text.append(value);
        }
        return text.toString();
    }
}
