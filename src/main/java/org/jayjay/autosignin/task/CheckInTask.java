package org.jayjay.autosignin.task;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.Data;
import org.jayjay.autosignin.entity.MessageBlock;
import org.jayjay.autosignin.entity.MessageLine;
import org.jayjay.autosignin.entity.NotificationSection;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
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
        headers.put("Content-Type", "application/json;charset=UTF-8");
        headers.put("Sec-Fetch-Dest", "empty");
        headers.put("Sec-Fetch-Mode", "cors");
        headers.put("Sec-Fetch-Site", "same-origin");
        headers.put("User-Agent", userAgent);
        // 只给 Chrome 发 sec-ch-ua-platform 而不给 sec-ch-ua，本身就是一组对不上的指纹
        headers.put("sec-ch-ua", "\"Chromium\";v=\"114\", \"Microsoft Edge\";v=\"114\", \"Not)A;Brand\";v=\"99\"");
        headers.put("sec-ch-ua-mobile", "?0");
        headers.put("sec-ch-ua-platform", "\"Windows\"");
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
     * 把消息块插到最前面。
     * 有的结果（比如签到汇总行）要等任务跑完才知道完整数据，但阅读顺序应该在最前面，
     * 这种情况下用这个方法补插，避免为了凑顺序把中间结果缓存起来。
     */
    protected void addMessageFirst(String... message) {
        MessageBlock block = new MessageBlock().addLine(message);
        if (!block.isBlank()) {
            messageBlocks.add(0, block);
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

    /**
     * 状态文件目录。容器里 /app 在重启后仍然保留，只有重建镜像或 docker compose down
     * 才会丢；本地运行时退回系统临时目录。
     */
    static Path stateDir() {
        Path app = Paths.get("/app");
        if (Files.isDirectory(app) && Files.isWritable(app)) {
            return app.resolve("state");
        }
        return Paths.get(System.getProperty("java.io.tmpdir"), "auto-sign-in");
    }

    /**
     * 账号维度的文件名后缀。多账号在同一容器里共用状态目录，
     * 用账号哈希区分可以避免不同账号的凭证互相覆盖，也不会把账号明文写进文件名。
     */
    static String accountKey(String account) {
        return sha1Hex(StrUtil.trimToEmpty(account)).substring(0, 12);
    }

    static String sha1Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) {
                hex.append(String.format("%02x", item & 0xff));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

}
