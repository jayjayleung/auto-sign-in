package org.jayjay.autosignin.util;

import cn.hutool.json.JSONObject;
import org.jayjay.autosignin.entity.MessageBlock;
import org.jayjay.autosignin.entity.MessageLine;
import org.jayjay.autosignin.entity.NotificationSection;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;

public class NotificationSenderTest {

    @Test
    public void pushPlusUsesMarkdownTemplate() {
        MessageBlock block = new MessageBlock()
                .addLine(new MessageLine().appendBold("账号").append("（one@example.com）"))
                .addLine("签到结果：今日已签到");
        NotificationSection section = new NotificationSection("云桥签到", Collections.singletonList(block));
        NotificationSender sender = new NotificationSender();
        sender.PUSH_PLUS_TOKEN = "test-token";

        JSONObject body = sender.createPushPlusBody(Collections.singletonList(section));

        assertEquals("test-token", body.getStr("token"));
        assertEquals("签到结果", body.getStr("title"));
        assertEquals("markdown", body.getStr("template"));
        assertEquals(
                "## 云桥签到\n\n**账号**（one@example\\.com）  \n签到结果：今日已签到\n\n",
                body.getStr("content")
        );
    }

    @Test
    public void titleAppendsFailureCount() {
        NotificationSender sender = new NotificationSender();
        MessageBlock ok = MessageBlock.text("签到结果：今日已签到");
        MessageBlock failed = MessageBlock.text("签到失败：用户名或密码错误");

        assertEquals("签到结果", sender.buildTitle(Collections.singletonList(
                new NotificationSection("云桥签到", Collections.singletonList(ok)))));
        assertEquals("签到结果（1 个异常）", sender.buildTitle(java.util.Arrays.asList(
                new NotificationSection("云桥签到", Collections.singletonList(ok)),
                new NotificationSection("西门子签到", Collections.singletonList(failed)))));
        assertEquals("签到结果（2 个异常）", sender.buildTitle(java.util.Arrays.asList(
                new NotificationSection("云桥签到", Collections.singletonList(failed)),
                new NotificationSection("西门子签到", Collections.singletonList(failed)))));
    }
}
