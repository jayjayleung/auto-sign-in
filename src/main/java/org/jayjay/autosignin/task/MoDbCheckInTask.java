package org.jayjay.autosignin.task;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.jayjay.autosignin.entity.MessageList;
import org.jayjay.autosignin.util.MessageUtil;
import org.junit.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.HttpCookie;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Random;

@Data
@EqualsAndHashCode(callSuper = true)
public class MoDbCheckInTask extends CheckInTask {
    String loginUrl = "https://www.modb.pro/api/login";
    //废弃
//    String checkInUrl = "https://www.modb.pro/api/user/checkIn";
    //新签到接口
    String checkInUrl = "https://www.modb.pro/api/user/dailyCheck";
    String clockUrl = "https://www.modb.pro/api/env/clock";
    String userUrl = "https://www.modb.pro/api/user/detail";
    // 硬编码密钥（与原JS一致）
    private static final String AES_KEY = "emcs-app-request";
    private static final String AES_IV = "xqgb1vda11s0e94g"; // 可能需要根据实际加密模式调整


    @Override
    public MessageList messageList() {
        return new MessageList("Modb 签到", listMessage);
    }

    @Test()
    public void testRun() {
        run();
    }

    @Override
    public CheckInTask run() {
        try {


            System.out.println("Modb 签到任务开始");
            String modbUsername = System.getenv("MODB_USERNAME");
            String modbPassword = System.getenv("MODB_PASSWORD");
            if (StrUtil.isBlank(modbUsername) || StrUtil.isBlank(modbPassword)) {
                System.out.println("Modb 账号密码未配置，跳过签到");
                isRun = !isRun;
                return this;
            }
            System.out.println("开始登录...");
            JSONObject bodyJson = JSONUtil.createObj();
            bodyJson.set("phoneNum", modbUsername);
            bodyJson.set("password", modbPassword);
            HttpResponse loginRes = HttpRequest.post(loginUrl).headerMap(headers(), true)
                    .body(bodyJson.toString())
                    .execute();

            if (!loginRes.isOk()) {
                System.out.println("Modb 登录失败！");
                System.out.println(loginRes.body());
                addMessage("Modb 签到失败！请检查日志!!!");
                return this;
            }
            JSONObject loginBody = toJSON(loginRes.body());
            System.out.println(loginBody.getStr("operateMessage"));
            List<HttpCookie> cookies = loginRes.getCookies();
            loginRes.getCookies().forEach(System.out::println);
            Map<String, String> headers = headers();
            headers.put("Authorization", loginRes.header("Authorization"));
            //查询用户信息
            HttpResponse userRes = HttpRequest.get(userUrl)
                    .cookie(cookies)
                    .headerMap(userHeaders(loginRes.header("Authorization")), true).execute();
            if (userRes.isOk()) {
                JSONObject userBody = toJSON(userRes.body());
                System.out.println(userBody);
                StringBuilder account = lineMsg("用户名：").append(userBody.getStr("account"));
                addMessage(account);
                addMessage("墨值：", userBody.getStr("point"));
            }
            //获取aes加密时间戳
            HttpResponse clockRes = HttpRequest.get(clockUrl)
                    .cookie(cookies)
                    .headerMap(userHeaders(loginRes.header("Authorization")), true).execute();
            String reqKey = "";
            if (clockRes.isOk()) {
                JSONObject body = toJSON(clockRes.body());
                System.out.println(body);
                if (body.containsKey("operateCallBackObj")) {
                    String operateCallBackObj = body.getStr("operateCallBackObj");
                    try {
                        reqKey = encryptRequestKey(operateCallBackObj);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            }
            //签到
            System.out.println("开始签到...");
            HttpResponse checkInRes = HttpRequest.post(checkInUrl)
                    .cookie(cookies)
                    .body("{\"reqKey\":\"" + reqKey + "\"}")
                    .headerMap(headers, true).execute();
            String body = checkInRes.body();
            if (!checkInRes.isOk()) {
                System.out.println(body);
                System.out.println("Modb 签到失败！");
                addMessage("Modb 签到失败！请检查日志!!!");
                return this;
            }
            System.out.println(body);
            JSONObject checkInBody = toJSON(body);
            String str = checkInBody.getStr("operateMessage");
            StringBuilder checkInMsg = lineMsg(str);

//        if(checkInBody.containsKey("operateCallBackObj")){
//            JSONObject operateCallBackObj = checkInBody.getJSONObject("operateCallBackObj");
//            if(operateCallBackObj!=null && operateCallBackObj.containsKey("point")){
//                checkInMsg.append("当前墨值：").append(operateCallBackObj.getStr("point")).append(MessageUtil.lineEnd);
//            }
//        }
            addMessage(checkInMsg);
            System.out.println("Modb 签到任务结束...");
        } catch (Exception e) {
            e.printStackTrace();
            addMessage("Modb 签到失败！请检查日志!!!");
        }
        return this;
    }

    public Map<String, String> headers() {
        Map<String, String> headers = commonHeaders();
        headers.put("Host", "www.modb.pro");
        headers.put("Origin", "https://www.modb.pro");
        headers.put("Referer", "https://www.modb.pro/login?redirect=%2ForderList");
        return headers;
    }

    public Map<String, String> userHeaders(String token) {
        Map<String, String> headers = commonHeaders();
        headers.put("Host", "www.modb.pro");
        headers.put("Origin", "https://www.modb.pro");
        headers.put("Authorization", token);
//        headers.put("Referer", "https://www.modb.pro/login?redirect=%2ForderList");
        return headers;
    }


    // 生成类似原JS的UUID
    private static String generateCustomUUID() {
        SecureRandom random = new SecureRandom();
        char[] chars = "0123456789abcdefghijklmnopqrstuvwxyz".toCharArray();
        char[] uuid = new char[36];

        for (int i = 0; i < 36; i++) {
            uuid[i] = chars[random.nextInt(16)];
        }

        // 强制设置特定位置的值
        uuid[14] = '4';
        uuid[19] = Character.forDigit((uuid[19] & 0x3) | 0x8, 16); // 保持第三位为特定值

        // 插入分隔符
        uuid[8] = uuid[13] = uuid[18] = uuid[23] = '-';
        return new String(uuid);
    }

    // AES加密实现
    public static String encryptRequestKey(String callbackData) throws Exception {
        String uuid = generateCustomUUID();
        String plainText = uuid + ":" + callbackData;

        // 密钥处理（注意：这里可能需要根据实际算法调整）
        SecretKeySpec keySpec = new SecretKeySpec(AES_KEY.getBytes("UTF-8"), "AES");
        IvParameterSpec ivSpec = new IvParameterSpec(AES_IV.getBytes("UTF-8"));

        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec);

        byte[] encrypted = cipher.doFinal(plainText.getBytes("UTF-8"));
        return Base64.getEncoder().encodeToString(encrypted);
    }

}
