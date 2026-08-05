package org.jayjay.autosignin.entity;

import cn.hutool.core.util.StrUtil;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

/**
 * 一段结构化消息，通常表示一个独立结果或一个账号，由多行消息组成。
 */
@Getter
public class MessageBlock {

    private final List<MessageLine> lines = new ArrayList<>();

    /**
     * 创建仅包含一行普通文本的消息块。
     */
    public static MessageBlock text(CharSequence text) {
        return new MessageBlock().addLine(text == null ? "" : text.toString());
    }

    /**
     * 将多个普通文本片段拼接为一行并加入当前消息块。
     */
    public MessageBlock addLine(String... values) {
        return addLine(new MessageLine().append(values));
    }

    /**
     * 将已包含样式语义的消息行加入当前消息块，空行会被忽略。
     */
    public MessageBlock addLine(MessageLine line) {
        if (line != null && !line.isEmpty()) {
            lines.add(line);
        }
        return this;
    }

    /**
     * 判断当前消息块是否没有任何消息行。
     */
    public boolean isEmpty() {
        return lines.isEmpty();
    }

    /**
     * 判断当前消息块是否没有可展示的文本内容。
     */
    public boolean isBlank() {
        return StrUtil.isBlank(toPlainText());
    }

    /**
     * 按行拼接为规范化换行的纯文本，不输出任何 HTML 或 Markdown 标记。
     */
    public String toPlainText() {
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < lines.size(); index++) {
            if (index > 0) {
                text.append('\n');
            }
            text.append(lines.get(index).toPlainText());
        }
        return text.toString().replace("\r\n", "\n").replace('\r', '\n');
    }

    @Override
    public String toString() {
        return toPlainText();
    }
}
