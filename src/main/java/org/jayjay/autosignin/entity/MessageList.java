package org.jayjay.autosignin.entity;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Objects;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MessageList {

    private String title;

    private List<StringBuilder> messages;

    public boolean isSend() {
        return StrUtil.isNotBlank(title) && CollUtil.isNotEmpty(messages);
    }
}
