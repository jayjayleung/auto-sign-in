package org.jayjay.autosignin.util;

import cn.hutool.http.HtmlUtil;
import org.jayjay.autosignin.entity.MessageBlock;
import org.jayjay.autosignin.entity.MessageLine;
import org.jayjay.autosignin.entity.NotificationSection;

import java.util.List;

/**
 * 将渠道无关的结构化消息渲染为控制台纯文本、HTML 或 Markdown。
 */
public final class MessageRenderer {

    private static final String LINE_END = "\n";
    private static final String BLOCK_END = "\n\n";
    private static final String MARKDOWN_SPECIAL_CHARACTERS = "\\`*_{}[]()#+-.!|<>&~";

    private MessageRenderer() {
    }

    /**
     * 渲染供控制台和日志使用的纯文本内容。
     */
    public static String toPlainText(List<NotificationSection> sections) {
        StringBuilder message = new StringBuilder();
        for (NotificationSection section : sections) {
            if (!section.hasContent()) {
                continue;
            }
            message.append(section.getTitle()).append(LINE_END);
            for (MessageBlock block : section.getBlocks()) {
                if (block == null || block.isBlank()) {
                    continue;
                }
                message.append(block.toPlainText()).append(LINE_END);
            }
        }
        return message.toString();
    }

    /**
     * 渲染供邮件使用的 HTML，并转义所有业务文本。
     */
    public static String toHtml(List<NotificationSection> sections) {
        StringBuilder message = new StringBuilder();
        for (NotificationSection section : sections) {
            if (!section.hasContent()) {
                continue;
            }
            message.append("<h2>").append(HtmlUtil.escape(section.getTitle())).append("</h2>");
            for (MessageBlock block : section.getBlocks()) {
                if (block == null || block.isBlank()) {
                    continue;
                }
                message.append("<p>");
                appendHtmlBlock(message, block);
                message.append("</p>");
            }
        }
        return message.toString();
    }

    /**
     * 渲染供 PushPlus 和 Server酱使用的 Markdown，并转义业务文本中的格式字符。
     */
    public static String toMarkdown(List<NotificationSection> sections) {
        StringBuilder message = new StringBuilder();
        for (NotificationSection section : sections) {
            if (!section.hasContent()) {
                continue;
            }
            message.append("## ").append(escapeMarkdown(section.getTitle())).append(BLOCK_END);
            for (MessageBlock block : section.getBlocks()) {
                if (block == null || block.isBlank()) {
                    continue;
                }
                appendMarkdownBlock(message, block);
                message.append(BLOCK_END);
            }
        }
        return message.toString();
    }

    private static void appendHtmlBlock(StringBuilder message, MessageBlock block) {
        List<MessageLine> lines = block.getLines();
        for (int index = 0; index < lines.size(); index++) {
            if (index > 0) {
                message.append("<br>");
            }
            for (MessageLine.Part part : lines.get(index).getParts()) {
                String text = escapeHtml(part.getText());
                if (part.isBold()) {
                    message.append("<strong>").append(text).append("</strong>");
                } else {
                    message.append(text);
                }
            }
        }
    }

    private static void appendMarkdownBlock(StringBuilder message, MessageBlock block) {
        List<MessageLine> lines = block.getLines();
        for (int index = 0; index < lines.size(); index++) {
            if (index > 0) {
                message.append("  ").append(LINE_END);
            }
            for (MessageLine.Part part : lines.get(index).getParts()) {
                String text = escapeMarkdown(part.getText());
                if (part.isBold()) {
                    message.append("**").append(text).append("**");
                } else {
                    message.append(text);
                }
            }
        }
    }

    private static String escapeMarkdown(String text) {
        String normalized = normalizeLineEndings(text);
        StringBuilder escaped = new StringBuilder(normalized.length());
        for (int index = 0; index < normalized.length(); index++) {
            char character = normalized.charAt(index);
            if (character == '\n') {
                escaped.append("  ").append(LINE_END);
            } else {
                if (MARKDOWN_SPECIAL_CHARACTERS.indexOf(character) >= 0) {
                    escaped.append('\\');
                }
                escaped.append(character);
            }
        }
        return escaped.toString();
    }

    private static String escapeHtml(String text) {
        return HtmlUtil.escape(normalizeLineEndings(text)).replace(LINE_END, "<br>");
    }

    private static String normalizeLineEndings(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\r\n", LINE_END).replace('\r', '\n');
    }
}
