package org.jayjay.autosignin.task;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.Data;
import org.jayjay.autosignin.entity.MessageBlock;
import org.jayjay.autosignin.entity.MessageLine;
import org.jayjay.autosignin.entity.NotificationSection;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
public abstract class CheckInTask {

    static final String userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36 Edg/114.0.1823.82";


    /** 当前签到任务产生的渠道无关消息块。 */
    final List<MessageBlock> messageBlocks = new ArrayList<>();


    /** 当前任务是否启用；缺少必要配置时会关闭该任务的通知分组。 */
    boolean enabled = true;


    abstract CheckInTask run() throws IOException, InterruptedException;


    public static Map<String, String> commonHeaders() {
        Map<String, String> headers = new HashMap<>();
        // 填充 HTTP 头信息
        headers.put("Accept", "application/json, text/plain, */*");
        headers.put("Accept-Encoding", "gzip, deflate");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8,en-GB;q=0.7,en-US;q=0.6");
        headers.put("Connection", "keep-alive");
        headers.put("Content-Type", "application/json;charset=UTF-8");
        headers.put("Sec-Fetch-Dest", "empty");
        headers.put("Sec-Fetch-Mode", "cors");
        headers.put("Sec-Fetch-Site", "same-origin");
        headers.put("User-Agent", userAgent);
        headers.put("sec-ch-ua-platform", "Windows");
        return headers;
    }

    /**
     * 转换json
     *
     * @param str json字符串
     * @return Json对象
     */
    public static JSONObject toJSON(String str) {
        if (StrUtil.isBlank(str)) {
            return null;
        }
        if (!JSONUtil.isTypeJSON(str)) {
            return null;
        }
        return JSONUtil.parseObj(str);
    }


    /**
     * 添加一条简单的单行消息，适用于不需要局部加粗或多行布局的结果。
     */
    protected void addMessage(String... message) {
        addMessage(new MessageBlock().addLine(message));
    }

    /**
     * 添加完整消息块，适用于多行内容或包含加粗片段的复杂结果。
     */
    protected void addMessage(MessageBlock message) {
        if (message != null && !message.isBlank()) {
            messageBlocks.add(message);
        }
    }

    /**
     * 将一条已包含样式语义的消息行包装为独立消息块后添加。
     */
    protected void addMessage(MessageLine message) {
        addMessage(new MessageBlock().addLine(message));
    }

    /**
     * 清空当前任务已收集的消息，供需要重试并替换中间结果的任务使用。
     */
    protected void clearMessages() {
        messageBlocks.clear();
    }


    public static void sleep(long time) {
        try {
            Thread.sleep(time);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 返回当前任务可发送的通知分组；未启用的任务返回空分组并跳过通知。
     */
    public NotificationSection getNotificationSection() {
        return enabled ? buildNotificationSection() : new NotificationSection();
    }

    /**
     * 将当前任务标题和消息块组装为通知结果，子类可覆盖标题。
     */
    public NotificationSection buildNotificationSection() {
        return new NotificationSection("签到", messageBlocks);
    }

}
