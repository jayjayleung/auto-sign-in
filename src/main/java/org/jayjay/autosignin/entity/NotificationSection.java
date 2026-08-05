package org.jayjay.autosignin.entity;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 一个签到任务的通知分组，包含分组标题及该任务产生的消息块。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class NotificationSection {

    private String title;

    private List<MessageBlock> blocks;

    /**
     * 只有标题和至少一个非空消息块同时存在时，该任务结果才参与通知。
     */
    public boolean hasContent() {
        return StrUtil.isNotBlank(title)
                && CollUtil.isNotEmpty(blocks)
                && blocks.stream().anyMatch(block -> block != null && !block.isBlank());
    }
}
