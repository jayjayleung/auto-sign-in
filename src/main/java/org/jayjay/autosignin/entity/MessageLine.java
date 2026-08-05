package org.jayjay.autosignin.entity;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

/**
 * 一行与通知渠道无关的消息，由一个或多个普通或加粗文本片段组成。
 */
@Getter
public class MessageLine {

    private final List<Part> parts = new ArrayList<>();

    /**
     * 追加普通文本片段，多个参数会先拼接为同一个片段，null 值会被忽略。
     */
    public MessageLine append(String... values) {
        return append(false, values);
    }

    /**
     * 追加需要强调的文本片段，由具体渲染器转换为 HTML 或 Markdown 粗体。
     */
    public MessageLine appendBold(String... values) {
        return append(true, values);
    }

    /**
     * 返回去除样式后的行文本，供纯文本输出、判空和测试使用。
     */
    public String toPlainText() {
        StringBuilder text = new StringBuilder();
        parts.forEach(part -> text.append(part.getText()));
        return text.toString();
    }

    /**
     * 判断当前行是否尚未包含任何有效文本片段。
     */
    public boolean isEmpty() {
        return parts.isEmpty();
    }

    private MessageLine append(boolean bold, String... values) {
        StringBuilder text = new StringBuilder();
        if (values != null) {
            for (String value : values) {
                if (value != null) {
                    text.append(value);
                }
            }
        }
        if (text.length() > 0) {
            parts.add(new Part(text.toString(), bold));
        }
        return this;
    }

    /**
     * 行内最小文本单元，只描述内容及是否加粗，不包含渠道格式标记。
     */
    @Getter
    @AllArgsConstructor
    public static class Part {
        private final String text;
        private final boolean bold;
    }
}
