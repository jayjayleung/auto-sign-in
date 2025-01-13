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

public interface CheckInTask {
    static final String lineEnd = "\n";

    static final String userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36 Edg/114.0.1823.82";

    StringBuilder message = new StringBuilder();

    List<StringBuilder> listMessage = new ArrayList<>();


    StringBuilder run() throws IOException, InterruptedException;


    default Map<String, String> commonHeaders(){
        Map<String, String> headers = new HashMap<>();
        // 填充 HTTP 头信息
        headers.put("Accept", "application/json, text/plain, */*");
        headers.put("Accept-Encoding", "gzip, deflate");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8,en-GB;q=0.7,en-US;q=0.6");
        headers.put("Connection", "keep-alive");
        headers.put("Content-Length", "2");
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
    default JSONObject toJSON(String str) {
        if (StrUtil.isBlank(str)) {
            return null;
        }
        if (!JSONUtil.isTypeJSON(str)) {
            return null;
        }
        return JSONUtil.parseObj(str);
    }



    default void message(StringBuilder message) {
        listMessage.add(message);
    }

    default void message(String...message) {
        StringBuilder sb = new StringBuilder();
        for (String s : message) {
            sb.append(sb);
        }
        listMessage.add(sb);
    }


    default StringBuilder message(String message){
        return new StringBuilder(message);
    }

    default CheckInTask splitLine(){
        message.append("==============").append(lineEnd);
        return this;
    }


    default StringBuilder getMessage(){
        return message;
    }


}
