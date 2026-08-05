package org.jayjay.autosignin.util;

import org.jayjay.autosignin.entity.MessageBlock;
import org.jayjay.autosignin.entity.MessageLine;
import org.jayjay.autosignin.entity.NotificationSection;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MessageRendererTest {

    @Test
    public void rendersStructuredMessagesForEveryChannel() {
        NotificationSection section = yunqiaoSection();
        List<NotificationSection> sections = Collections.singletonList(section);

        assertEquals(
                "云桥签到\n"
                        + "账号 1（one@example.com）\n"
                        + "当前积分：6\n"
                        + "连续签到：2 天\n"
                        + "签到结果：今日已签到\n"
                        + "账号 2（two@example.com）\n"
                        + "当前积分：7\n"
                        + "连续签到：2 天\n"
                        + "签到结果：今日已签到\n",
                MessageRenderer.toPlainText(sections)
        );
        assertEquals(
                "<h2>云桥签到</h2>"
                        + "<p><strong>账号 1</strong>（one@example.com）<br>当前积分：6<br>连续签到：2 天<br>签到结果：今日已签到</p>"
                        + "<p><strong>账号 2</strong>（two@example.com）<br>当前积分：7<br>连续签到：2 天<br>签到结果：今日已签到</p>",
                MessageRenderer.toHtml(sections)
        );
        assertEquals(
                "## 云桥签到\n\n"
                        + "**账号 1**（one@example\\.com）  \n"
                        + "当前积分：6  \n"
                        + "连续签到：2 天  \n"
                        + "签到结果：今日已签到\n\n"
                        + "**账号 2**（two@example\\.com）  \n"
                        + "当前积分：7  \n"
                        + "连续签到：2 天  \n"
                        + "签到结果：今日已签到\n\n",
                MessageRenderer.toMarkdown(sections)
        );
    }

    @Test
    public void escapesChannelSpecificMarkup() {
        MessageBlock block = new MessageBlock().addLine(
                new MessageLine().appendBold("粗*体").append(" <tag> & _value_")
        );
        List<NotificationSection> sections = Collections.singletonList(
                new NotificationSection("标题 <&>", Collections.singletonList(block))
        );

        assertEquals("标题 <&>\n粗*体 <tag> & _value_\n", MessageRenderer.toPlainText(sections));
        assertEquals(
                "<h2>标题 &lt;&amp;&gt;</h2><p><strong>粗*体</strong> &lt;tag&gt; &amp; _value_</p>",
                MessageRenderer.toHtml(sections)
        );
        assertEquals(
                "## 标题 \\<\\&\\>\n\n**粗\\*体** \\<tag\\> \\& \\_value\\_\n\n",
                MessageRenderer.toMarkdown(sections)
        );
    }

    @Test
    public void preservesEmbeddedLineBreaksAcrossRichTextChannels() {
        MessageBlock block = new MessageBlock().addLine(
                new MessageLine().appendBold("第一行\r\n第二行").append("\r第三行")
        );
        List<NotificationSection> sections = Collections.singletonList(
                new NotificationSection("换行", Collections.singletonList(block))
        );

        assertEquals("换行\n第一行\n第二行\n第三行\n", MessageRenderer.toPlainText(sections));
        assertEquals(
                "<h2>换行</h2><p><strong>第一行<br>第二行</strong><br>第三行</p>",
                MessageRenderer.toHtml(sections)
        );
        assertEquals(
                "## 换行\n\n**第一行  \n第二行**  \n第三行\n\n",
                MessageRenderer.toMarkdown(sections)
        );
    }

    @Test
    public void ignoresNullAndEmptyMessages() {
        MessageLine emptyLine = new MessageLine().append((String) null);
        MessageBlock emptyBlock = new MessageBlock().addLine(emptyLine);
        MessageBlock contentBlock = MessageBlock.text("有效内容");
        NotificationSection emptySection = new NotificationSection("空消息", Arrays.asList(null, emptyBlock));
        NotificationSection mixedSection = new NotificationSection("有效消息", Arrays.asList(null, emptyBlock, contentBlock));

        assertTrue(emptyLine.isEmpty());
        assertTrue(emptyBlock.isEmpty());
        assertFalse(emptySection.hasContent());
        assertTrue(mixedSection.hasContent());
        assertEquals("有效消息\n有效内容\n", MessageRenderer.toPlainText(Arrays.asList(emptySection, mixedSection)));
        assertEquals("<h2>有效消息</h2><p>有效内容</p>", MessageRenderer.toHtml(Arrays.asList(emptySection, mixedSection)));
        assertEquals("## 有效消息\n\n有效内容\n\n", MessageRenderer.toMarkdown(Arrays.asList(emptySection, mixedSection)));
    }

    private static NotificationSection yunqiaoSection() {
        MessageBlock first = new MessageBlock()
                .addLine(new MessageLine().appendBold("账号 1").append("（one@example.com）"))
                .addLine("当前积分：6")
                .addLine("连续签到：2 天")
                .addLine("签到结果：今日已签到");
        MessageBlock second = new MessageBlock()
                .addLine(new MessageLine().appendBold("账号 2").append("（two@example.com）"))
                .addLine("当前积分：7")
                .addLine("连续签到：2 天")
                .addLine("签到结果：今日已签到");
        return new NotificationSection("云桥签到", Arrays.asList(first, second));
    }
}
