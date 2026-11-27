package org.jayjay.autosignin.task;

import java.time.LocalDate;
import java.util.Random;

/**
 * 一条猹评的「形状」：发不发、多长、几段、带不带小标题和引用、加不加粗、配不配图、投什么态度、延迟几分钟发。
 * <p>
 * 参数不是拍脑袋定的，是按站点上 187 条真实猹评统计出来的分布抽样：
 * 字数 0-60 占 16%、60-120 占 14%、120-200 占 25%、200-300 占 17%、300-600 占 16%、600 以上占 12%；
 * 小标题 2%、引用 3%、加粗 14%、带图 45%、投「不推荐」15%。
 * <p>
 * 老实现把两段式、100-260 字、无图无格式写死在代码里，20 条猹评每一篇都落在真实分布最窄的那 4% 格子里，
 * 文本相似度只有 0.08 也没用——形状一模一样，打开主页一眼就看出来了。这里改成按分布抽样。
 */
final class WatchaReviewPlan {

    /**
     * 人格。真人之间风格差异极大：有人一条 30 字、97% 单段、从不配图；有人 700 字、
     * 条条配图。按「全站混合分布」抽样出来的账号，每天换一种风格，反而不像人。
     * 所以给账号定一个人格，日常只在这个人格的范围内波动。
     */
    enum Persona {
        /** 短评型：参照 10029396（33 字/97% 单段/0 图）。 */
        SHORT(20, 120, new double[]{0.95, 0.05, 0, 0, 0}, 0.02),
        /** 中等型：参照 10180481（183 字/80% 单段/0 图），最接近本账号历史。 */
        MEDIUM(70, 330, new double[]{0.55, 0.30, 0.15, 0, 0}, 0.12),
        /** 长评型：参照 10010996（774 字/0% 单段/81% 图）。 */
        LONG(350, 900, new double[]{0, 0.30, 0.30, 0.20, 0.20}, 0.70);

        final int minChars;
        final int maxChars;
        final double[] paragraphWeights;
        final double imageRate;

        Persona(int minChars, int maxChars, double[] paragraphWeights, double imageRate) {
            this.minChars = minChars;
            this.maxChars = maxChars;
            this.paragraphWeights = paragraphWeights;
            this.imageRate = imageRate;
        }

        static Persona of(String name) {
            if (name == null) {
                return MEDIUM;
            }
            String key = name.trim().toUpperCase(java.util.Locale.ROOT);
            for (Persona persona : values()) {
                if (persona.name().equals(key)) {
                    return persona;
                }
            }
            return MEDIUM;
        }
    }

    /** 投「不推荐」的比例，实测用户级中位 27%。 */
    private static final double DISLIKE_RATE = 0.2;
    /** 小标题、引用都是长评里的少见用法。 */
    private static final double HEADING_RATE = 0.02;
    private static final double QUOTE_RATE = 0.03;
    private static final double BOLD_RATE = 0.14;
    private static final int HEADING_MIN_CHARS = 160;
    private static final int QUOTE_MIN_CHARS = 200;

    private static final String[] SHAPES = {
            "从一件具体的小任务写起，顺着讲用到了什么功能、结果怎么样，展开一处最有感受的细节",
            "从最顺手或最别扭的一处操作切入，讲清它对这次任务的影响，按自己的态度自然收尾",
            "聊聊原来的做法和用它之后的差别，挑一个影响判断的步骤讲清楚，不要把功能挨个列一遍",
            "围绕一个满意或失望的结果倒回去说是怎么用的，需要提局限就落到这件事上",
            "像给朋友解释自己为什么会继续用或不再用，拿一次具体的操作和结果说明",
            "先说当时卡在哪一步，再说它是怎么被绕过去或者又卡了一遍的，别总结成清单",
            "写着写着想起另一个场景，顺便带上两句，最后落回最在意的那一点"
    };

    final boolean skip;
    final int targetChars;
    final int paragraphs;
    final boolean heading;
    final boolean quote;
    final boolean bold;
    final boolean image;
    final int voteValue;
    final int delayMinutes;
    final String shape;
    final Persona persona;
    final String style;

    private WatchaReviewPlan(boolean skip, int targetChars, int paragraphs, boolean heading, boolean quote,
                            boolean bold, boolean image, int voteValue, int delayMinutes, String shape,
                            Persona persona) {
        this.persona = persona;
        this.style = persona.name().toLowerCase(java.util.Locale.ROOT);
        this.skip = skip;
        this.targetChars = targetChars;
        this.paragraphs = paragraphs;
        this.heading = heading;
        this.quote = quote;
        this.bold = bold;
        this.image = image;
        this.voteValue = voteValue;
        this.delayMinutes = delayMinutes;
        this.shape = shape;
    }

    /**
     * 同一天同一产品得到同一个计划，重跑不会一会儿发一会儿不发；换天换产品就变。
     *
     * @param seedKey  日期和产品 ID 混出来的种子，保证可复现
     * @param skipRate 整天不发的概率，真人不会天天写
     */
    static WatchaReviewPlan plan(long seedKey, double skipRate, int maxDelayMinutes) {
        return plan(seedKey, skipRate, maxDelayMinutes, Persona.MEDIUM);
    }

    /**
     * 生产用的入口：先按「日期」判断今天是不是活跃日，再按「日期+产品」抽形状。
     * 这两步的种子必须分开——节奏只能由日期决定，混进产品 ID 的话，
     * 同一天换个产品就换了节奏，按周聚类就失效了。
     */
    static WatchaReviewPlan plan(LocalDate day, int productId, double quietWeekRate, int maxDelayMinutes,
                                 Persona persona) {
        return plan(day, productId, quietWeekRate, maxDelayMinutes, persona, 0);
    }

    /**
     * @param weekSeed 账号偏移（单位：周）。多个账号共用同一份代码时，
     *                 不加偏移会出现「三个号同一天发同一天歇」，比单看每个号都规律得多。
     */
    /** 强制今天发一条，不管节奏模型怎么判。给调试和集成测试用。 */
    static WatchaReviewPlan planForced(LocalDate day, int productId, int maxDelayMinutes, Persona persona) {
        return build(seed(day, productId), true, maxDelayMinutes, persona == null ? Persona.MEDIUM : persona);
    }

    static WatchaReviewPlan plan(LocalDate day, int productId, double quietWeekRate, int maxDelayMinutes,
                                 Persona persona, int weekSeed) {
        Persona shape = persona == null ? Persona.MEDIUM : persona;
        long epochDay = day == null ? 0L : day.toEpochDay();
        boolean active = activeOn(epochDay, quietWeekRate, weekSeed);
        return build(seed(day, productId + weekSeed * 1000), active, maxDelayMinutes, shape);
    }

    /**
     * @param quietWeekRate 整周几乎不写的概率。真人不是每天掷硬币决定写不写，而是
     *                      「连续写几天 + 空一周」这样的节奏：实测两个活跃账号各有 5 天、8 天的空档，
     *                      也有一天连发 3 条的爆发。纯独立抽样永远造不出这种形状。
     */
    static WatchaReviewPlan plan(long seedKey, double quietWeekRate, int maxDelayMinutes, Persona persona) {
        return build(seedKey, activeOn(seedKey, quietWeekRate), maxDelayMinutes, persona);
    }

    private static WatchaReviewPlan build(long seedKey, boolean active, int maxDelayMinutes, Persona persona) {
        Persona shape = persona == null ? Persona.MEDIUM : persona;
        Random random = new Random(mix(seedKey));
        if (!active) {
            return new WatchaReviewPlan(true, 0, 0, false, false, false, false, 1, 0, "", shape);
        }
        int targetChars = shape.minChars + random.nextInt(shape.maxChars - shape.minChars);
        int paragraphs = sampleParagraphs(shape, random);
        // 段数受字数约束：几十个字拆成五段不现实
        paragraphs = Math.min(paragraphs, Math.max(1, targetChars / 40));
        if (targetChars < 60) {
            paragraphs = 1;
        }
        // 三百字挤成一段是堵墙，真人到这个长度基本会分段
        if (targetChars > 260) {
            paragraphs = Math.max(2, paragraphs);
        }
        boolean heading = targetChars >= HEADING_MIN_CHARS && random.nextDouble() < HEADING_RATE;
        boolean quote = targetChars >= QUOTE_MIN_CHARS && random.nextDouble() < QUOTE_RATE;
        boolean bold = random.nextDouble() < BOLD_RATE;
        boolean image = random.nextDouble() < shape.imageRate;
        int voteValue = random.nextDouble() < DISLIKE_RATE ? 2 : 1;
        int delayMinutes = maxDelayMinutes <= 0 ? 0 : random.nextInt(maxDelayMinutes + 1);
        String note = SHAPES[random.nextInt(SHAPES.length)];
        return new WatchaReviewPlan(false, targetChars, paragraphs, heading, quote, bold, image,
                voteValue, delayMinutes, note, shape);
    }

    /**
     * 把连续的小种子打散。直接用 new Random(0)、new Random(1) 这种相邻种子时，
     * 第一个 nextDouble 是线性变化的，抽出来的形状会整批偏到同一侧，随机化等于没做。
     */
    static long mix(long value) {
        long x = value + 0x9E3779B97F4A7C15L;
        x = (x ^ (x >>> 30)) * 0xBF58476D1CE4E5B9L;
        x = (x ^ (x >>> 27)) * 0x94D049BB133111EBL;
        return x ^ (x >>> 31);
    }

    static long seed(LocalDate day, int productId) {
        long base = day == null ? 0L : day.toEpochDay();
        return base * 1000003L + productId;
    }


    /**
     * 按周分块决定今天写不写：每周先抽「这周是活跃周还是静默周」，
     * 活跃周挑 5-6 天、静默周挑 0-1 天，再在块内把这几天摊开。
     * 同一个日期永远得到同一个结果，重跑不会一会儿写一会儿不写。
     */
    static boolean activeOn(long epochDay, double quietWeekRate) {
        return activeOn(epochDay, quietWeekRate, 0);
    }

    static boolean activeOn(long epochDay, double quietWeekRate, int weekSeed) {
        long block = Math.floorDiv(epochDay, 7) + weekSeed;
        Random random = new Random(mix(block * 1000003L + 71));
        boolean quiet = random.nextDouble() < clamp(quietWeekRate);
        int activeDays = quiet ? random.nextInt(2) : 5 + random.nextInt(2);
        int today = (int) Math.floorMod(epochDay, 7);
        boolean[] picked = new boolean[7];
        int chosen = 0;
        for (int index = 0; index < 7 && chosen < activeDays; index++) {
            int slot = random.nextInt(7);
            if (!picked[slot]) {
                picked[slot] = true;
                chosen++;
            }
        }
        // 上面可能因为重复而没选满，顺序补足
        for (int index = 0; index < 7 && chosen < activeDays; index++) {
            if (!picked[index]) {
                picked[index] = true;
                chosen++;
            }
        }
        return picked[today];
    }

    private static int sampleParagraphs(Persona persona, Random random) {
        double point = random.nextDouble();
        double cumulative = 0;
        for (int index = 0; index < persona.paragraphWeights.length; index++) {
            cumulative += persona.paragraphWeights[index];
            if (point < cumulative) {
                return index + 1;
            }
        }
        return persona.paragraphWeights.length;
    }

    private static double clamp(double value) {
        if (value < 0) {
            return 0;
        }
        return value > 1 ? 1 : value;
    }

    int minChars() {
        return Math.max(12, (int) Math.round(targetChars * 0.7));
    }

    int maxChars() {
        return Math.max(minChars() + 10, (int) Math.round(targetChars * 1.3));
    }

    /** 写进提示词的长度和段数要求。 */
    String shapeHint() {
        return "写 " + paragraphs + " 段，合计约 " + targetChars + " 个字（" + minChars() + " 到 " + maxChars()
                + " 字之间）。" + shape + "。";
    }

    @Override
    public String toString() {
        if (skip) {
            return "今日不发";
        }
        return "约 " + targetChars + " 字/" + paragraphs + " 段"
                + (heading ? "/带小标题" : "") + (quote ? "/带引用" : "")
                + (bold ? "/带加粗" : "") + (image ? "/配图" : "")
                + (voteValue == 2 ? "/不推荐" : "/推荐") + "/延迟 " + delayMinutes + " 分钟";
    }
}
