package org.jayjay.autosignin.dashboard;

import org.junit.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Compose 里看板服务的可选性检查。
 * <p>
 * 看板是附加能力，默认 {@code docker compose up -d} 不该把它带起来。
 * 这条约束靠 compose 的 profiles 保证，很容易被误删，所以用测试钉住。
 * <p>
 * 仓库跟踪的模板里看板整段是注释状态（注释掉的 YAML 依然是契约），
 * 所以比对前先剥掉行首的注释前缀。
 */
public class ComposeProfilesTest {

    /** 仓库里跟踪的 compose 模板；本地实际的 docker-compose.yml 被 gitignore，单独检查。 */
    private static final String TEMPLATE = "docker-compose.yml.example";
    private static final String LOCAL = "docker-compose.yml";
    private static final String[] SIGNIN_SERVICES = {"auto-sign-in", "auto-sign-in-fanfan", "auto-sign-in-no1"};
    private static final String DASHBOARD = "auto-sign-in-dashboard";

    @Test
    public void 模板里看板声明了独立profile() throws Exception {
        String content = readCompose(TEMPLATE);
        assertNotNull("找不到 compose 模板：" + TEMPLATE, content);
        assertDashboardHasProfile(TEMPLATE, content);
    }

    @Test
    public void 本地compose同样声明了profile() throws Exception {
        String content = readCompose(LOCAL);
        if (content == null) {
            // 本地没有这份文件时跳过（被 gitignore，并非所有人都有）
            return;
        }
        assertDashboardHasProfile(LOCAL, content);
    }

    @Test
    public void 签到服务不受profile影响() throws Exception {
        for (String name : new String[]{TEMPLATE, LOCAL}) {
            String content = readCompose(name);
            if (content == null) {
                continue;
            }
            for (String service : SIGNIN_SERVICES) {
                String block = serviceBlock(content, service);
                if (block == null) {
                    continue;
                }
                assertFalse(name + " 的服务 " + service + " 不应带 profiles，否则默认 up 不会启动它",
                        block.contains("profiles:"));
            }
        }
    }

    @Test
    public void 看板的env_file允许缺失() throws Exception {
        for (String name : new String[]{TEMPLATE, LOCAL}) {
            String content = readCompose(name);
            if (content == null) {
                continue;
            }
            String block = serviceBlock(content, DASHBOARD);
            if (block == null) {
                continue;
            }
            // 没建口令文件时整份 compose 必须仍能生效
            assertTrue(name + " 的看板 env_file 必须标 required: false", block.contains("required: false"));
        }
    }

    private static void assertDashboardHasProfile(String name, String content) {
        String block = serviceBlock(content, DASHBOARD);
        assertNotNull(name + " 里应有 " + DASHBOARD + " 服务", block);
        assertTrue(name + " 的看板服务必须声明 profiles，否则默认 up 会把它一起启动", block.contains("profiles:"));
        assertTrue("profile 名应为 dashboard",
                block.matches("(?s).*profiles:\\s*\\[\\s*\"dashboard\"\\s*\\].*")
                        || block.matches("(?s).*profiles:\\s*\\n\\s*-\\s*dashboard.*"));
    }

    /**
     * 截取某个服务的配置块：从服务名到下一个同级服务（或文件末尾）。
     * 先把注释前缀剥掉再匹配，这样模板里被注释的服务也能被正确识别。
     */
    private static String serviceBlock(String rawContent, String service) {
        String content = stripCommentPrefix(rawContent);
        String marker = "\n  " + service + ":";
        int start = content.indexOf(marker);
        if (start < 0) {
            // 模板里可能只有一个空格缩进，放宽到不限制前导空格
            start = indexOfService(content, service);
        }
        if (start < 0) {
            return null;
        }
        int cursor = content.indexOf(':', start) + 1;
        while (true) {
            int next = content.indexOf("\n", cursor);
            if (next < 0) {
                return content.substring(start);
            }
            String line = content.substring(next + 1, Math.min(content.length(), nextLineEnd(content, next + 1)));
            // 服务定义行是「两个空格 + 非空格」，其它缩进或空行都属于当前服务
            if (line.startsWith("  ") && line.length() > 2 && line.charAt(2) != ' ') {
                return content.substring(start, next);
            }
            cursor = next + 1;
        }
    }

    /** 在文本里定位服务定义行的起点。 */
    private static int indexOfService(String content, String service) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?m)^\\s*" + java.util.regex.Pattern.quote(service) + ":\\s*$")
                .matcher(content);
        return matcher.find() ? matcher.start() : -1;
    }

    private static int nextLineEnd(String content, int from) {
        int end = content.indexOf('\n', from);
        return end < 0 ? content.length() : end;
    }

    /** 去掉每行行首的 {@code #} 及紧随的一个空格，让注释掉的 YAML 也能按结构比对。 */
    private static String stripCommentPrefix(String content) {
        StringBuilder stripped = new StringBuilder(content.length());
        for (String line : content.split("\n", -1)) {
            String text = line;
            if (text.startsWith("#")) {
                text = text.substring(1);
                if (text.startsWith(" ")) {
                    text = text.substring(1);
                }
            }
            stripped.append(text).append('\n');
        }
        return stripped.toString();
    }

    /** 先找 classpath，再按相对路径兜底（Maven 测试阶段仓库文件不在 classpath 上）。 */
    private static String readCompose(String name) {
        try (InputStream input = ComposeProfilesTest.class.getResourceAsStream("/" + name)) {
            if (input != null) {
                return new String(readAll(input), StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {
            // 落到相对路径
        }
        List<java.nio.file.Path> candidates = new ArrayList<java.nio.file.Path>();
        candidates.add(java.nio.file.Paths.get(name));
        candidates.add(java.nio.file.Paths.get("..", name));
        for (java.nio.file.Path path : candidates) {
            try {
                if (java.nio.file.Files.isRegularFile(path)) {
                    return new String(java.nio.file.Files.readAllBytes(path), StandardCharsets.UTF_8);
                }
            } catch (Exception ignored) {
                // 换下一个候选路径
            }
        }
        return null;
    }

    private static byte[] readAll(InputStream input) throws Exception {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) != -1) {
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }
}
