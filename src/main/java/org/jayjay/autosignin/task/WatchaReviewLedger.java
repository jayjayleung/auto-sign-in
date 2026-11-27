package org.jayjay.autosignin.task;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 猹评台账：记下发过的每条猹评，下次运行回查它们还在不在。
 * <p>
 * 这是「不封号」里唯一真正的安全阀。内容和节奏做得再像人，只要开始被折叠，
 * 继续按同样频率发就一定会升级成删帖、禁言、封号。所以：
 * 发过 → 回查 → 少了一条就记一次违规 → 累计两次自动停写一周，等人看。
 */
final class WatchaReviewLedger {

    /** 累计几次违规就停写。 */
    private static final int STRIKE_LIMIT = 2;
    /** 停写天数。 */
    private static final int PAUSE_DAYS = 7;
    /** 发布不足一天的先不判违规，服务端审核有延迟。 */
    private static final int GRACE_HOURS = 24;
    /**
     * 只核对最近这段时间的条目。回查接口一次最多取 50 条，
     * 更早的猹评根本不在返回里，不设窗口就会把「查不到」误当成「被删」，攒够两次就误熔断。
     */
    private static final int RECONCILE_DAYS = 30;

    private final List<Entry> published = new ArrayList<Entry>();
    private int strikes;
    /** 台账文件存在但读不出来。此时按停写处理，且不覆盖原文件，等人看过再删。 */
    private boolean unreadable;

    static WatchaReviewLedger load(File file) {
        WatchaReviewLedger ledger = new WatchaReviewLedger();
        if (file == null || !file.isFile()) {
            return ledger;
        }
        try {
            JSONObject json = JSONUtil.parseObj(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            ledger.strikes = json.getInt("strikes", 0);
            JSONArray array = json.getJSONArray("published");
            if (array != null) {
                for (int index = 0; index < array.size(); index++) {
                    JSONObject item = array.getJSONObject(index);
                    if (item == null) {
                        continue;
                    }
                    ledger.published.add(new Entry(item.getLong("id", 0L), item.getInt("product_id", 0),
                            StrUtil.nullToEmpty(item.getStr("created_at")), item.getBool("gone", false)));
                }
            }
        } catch (Exception e) {
            // 读不出来就当作已经熔断：按空台账处理等于把违规次数清零，安全阀会悄悄失效。
            ledger.published.clear();
            ledger.strikes = STRIKE_LIMIT;
            ledger.unreadable = true;
            System.out.println("[观猹] 台账读取失败，按停写处理：" + StrUtil.maxLength(e.getMessage(), 100));
        }
        return ledger;
    }

    void save(File file) {
        if (unreadable) {
            return;
        }
        try {
            JSONArray array = new JSONArray();
            for (Entry entry : published) {
                array.add(JSONUtil.createObj()
                        .set("id", entry.id)
                        .set("product_id", entry.productId)
                        .set("created_at", entry.createdAt)
                        .set("gone", entry.gone));
            }
            JSONObject json = JSONUtil.createObj().set("strikes", strikes).set("published", array);
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory()) {
                parent.mkdirs();
            }
            // 先写临时文件再改名：进程在写入途中被终止时，原台账保持完整。
            Path target = file.toPath();
            Path temp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.write(temp, json.toString().getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            System.out.println("[观猹] 台账写入失败：" + StrUtil.maxLength(e.getMessage(), 100));
        }
    }

    void record(long reviewId, int productId, String createdAt) {
        published.add(new Entry(reviewId, productId, createdAt, false));
    }

    /**
     * 用「还能查到的猹评 id」核对台账，返回这次新发现的消失条数。
     * 只统计发布超过宽限期的，避免把还在审核的当成被删。
     */
    int reconcile(Set<Long> aliveIds) {
        int lost = 0;
        for (Entry entry : published) {
            if (entry.gone || aliveIds.contains(entry.id) || !olderThanGrace(entry.createdAt)
                    || !withinWindow(entry.createdAt)) {
                continue;
            }
            entry.gone = true;
            lost++;
        }
        if (lost > 0) {
            strikes += lost;
            System.out.println("[观猹] 发现 " + lost + " 条猹评不见了，累计违规 " + strikes + " 次");
        }
        return lost;
    }

    boolean paused(LocalDate today) {
        return strikes >= STRIKE_LIMIT;
    }

    int strikes() {
        return strikes;
    }

    boolean unreadable() {
        return unreadable;
    }

    /** 停写结束日期，给通知用。 */
    String pausedUntilText(LocalDate today) {
        return today.plusDays(PAUSE_DAYS).toString();
    }

    int publishedCount() {
        return published.size();
    }

    /** 发布是否在核对窗口内。 */
    static boolean withinWindow(String createdAt) {
        if (StrUtil.isBlank(createdAt)) {
            return false;
        }
        try {
            java.time.Instant instant = java.time.Instant.parse(createdAt.trim());
            return instant.isAfter(java.time.Instant.now().minusSeconds(RECONCILE_DAYS * 86400L));
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean olderThanGrace(String createdAt) {
        if (StrUtil.isBlank(createdAt)) {
            return true;
        }
        try {
            java.time.Instant instant = java.time.Instant.parse(createdAt.trim());
            return instant.plusSeconds(GRACE_HOURS * 3600L).isBefore(java.time.Instant.now());
        } catch (Exception e) {
            return true;
        }
    }

    /** 台账里的一条。 */
    static final class Entry {
        final long id;
        final int productId;
        final String createdAt;
        boolean gone;

        Entry(long id, int productId, String createdAt, boolean gone) {
            this.id = id;
            this.productId = productId;
            this.createdAt = createdAt;
            this.gone = gone;
        }
    }
}
