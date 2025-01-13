package org.jayjay.autosignin.task;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.Data;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public abstract class CheckInTask {
    public static final String lineEnd = "\n";

    static final String userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36 Edg/114.0.1823.82";

    StringBuilder message = new StringBuilder();

    List<StringBuilder> listMessage = new ArrayList<>();


     abstract CheckInTask run() throws IOException, InterruptedException;


    public static Map<String, String> commonHeaders(){
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



    public void addMessage(StringBuilder message) {
        listMessage.add(message);
    }

    public void addMessage(String...message) {
        StringBuilder sb = new StringBuilder();
        for (String s : message) {
            sb.append(sb);
        }
        listMessage.add(sb);
    }


    public StringBuilder lineMsg(String message){
        return new StringBuilder(message);
    }

    public static StringBuilder splitLine(){
        return new StringBuilder("==============");
    }


    public List<StringBuilder> getMessage(){
        return listMessage;
    }

    public static void sleep(long time){
        try {
            Thread.sleep(time);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

}
