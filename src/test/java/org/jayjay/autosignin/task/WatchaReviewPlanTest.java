package org.jayjay.autosignin.task;

import org.junit.Test;

import java.time.LocalDate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 形状计划的抽样必须落回真实分布，否则「随机化」只是换了个模子。
 */
public class WatchaReviewPlanTest {

    @Test
    public void sameSeedGivesSamePlan() {
        WatchaReviewPlan first = WatchaReviewPlan.plan(WatchaReviewPlan.seed(LocalDate.of(2026, 11, 27), 42), 0.35, 25);
        WatchaReviewPlan second = WatchaReviewPlan.plan(WatchaReviewPlan.seed(LocalDate.of(2026, 11, 27), 42), 0.35, 25);
        assertEquals(first.toString(), second.toString());
        assertEquals(first.targetChars, second.targetChars);
        assertEquals(first.paragraphs, second.paragraphs);
        assertEquals(first.delayMinutes, second.delayMinutes);
    }

    @Test
    public void differentDaysGiveDifferentPlans() {
        WatchaReviewPlan monday = WatchaReviewPlan.plan(WatchaReviewPlan.seed(LocalDate.of(2026, 11, 23), 42), 0, 25);
        WatchaReviewPlan tuesday = WatchaReviewPlan.plan(WatchaReviewPlan.seed(LocalDate.of(2026, 11, 24), 42), 0, 25);
        assertFalse("相邻两天不该抽出完全一样的形状",
                monday.targetChars == tuesday.targetChars && monday.paragraphs == tuesday.paragraphs
                        && monday.delayMinutes == tuesday.delayMinutes);
    }

    @Test
    public void skipRateIsRespected() {
        assertTrue("skipRate=1 时一条都不发", WatchaReviewPlan.plan(1L, 1, 0).skip);
        assertFalse("skipRate=0 时一定发", WatchaReviewPlan.plan(1L, 0, 0).skip);
    }

    @Test
    public void samplesFollowMeasuredDistribution() {
        int total = 4000;
        int skipped = 0;
        int dislike = 0;
        int withImage = 0;
        int withHeading = 0;
        for (int seed = 0; seed < total; seed++) {
            WatchaReviewPlan plan = WatchaReviewPlan.plan(seed, 0.35, 25);
            if (plan.skip) {
                skipped++;
                continue;
            }
            if (plan.voteValue == 2) {
                dislike++;
            }
            if (plan.image) {
                withImage++;
            }
            if (plan.heading) {
                withHeading++;
            }
            assertTrue("字数越界：" + plan, plan.targetChars >= 16 && plan.targetChars <= 1400);
            assertTrue("段数越界：" + plan, plan.paragraphs >= 1 && plan.paragraphs <= 8);
            assertTrue(plan.minChars() < plan.maxChars());
            assertTrue("延迟越界：" + plan, plan.delayMinutes >= 0 && plan.delayMinutes <= 25);
        }
        int published = total - skipped;
        // 节奏从「每天掷硬币」改成了「按周聚类」，跳过率由静默周概率决定，实测 40%-50%
        assertTrue("跳过率应在合理区间，实际 " + skipped * 100.0 / total,
                skipped > total * 0.35 && skipped < total * 0.55);
        assertTrue("不推荐比例应接近 20%，实际 " + dislike * 100.0 / published,
                dislike > published * 0.15 && dislike < published * 0.25);
        assertTrue("中等人格配图率应接近 12%，实际 " + withImage * 100.0 / published,
                withImage > published * 0.07 && withImage < published * 0.18);
        assertTrue("小标题比例应接近 2%，实际 " + withHeading * 100.0 / published, withHeading < published * 0.05);
    }

    @Test
    public void singleParagraphReviewsStayUnadorned() {
        boolean sawShort = false;
        for (int seed = 0; seed < 6000; seed++) {
            WatchaReviewPlan plan = WatchaReviewPlan.plan(seed, 0, 0);
            if (plan.skip || plan.paragraphs != 1 || plan.targetChars > 120) {
                continue;
            }
            sawShort = true;
            assertFalse("短评不挂小标题", plan.heading);
            assertFalse("短评不加引用", plan.quote);
        }
        assertTrue("样本里应该出现短单段评", sawShort);
    }

    @Test
    public void personasKeepTheirOwnStyle() {
        int shortWithImage = 0;
        int shortTotal = 0;
        int longWithImage = 0;
        int longTotal = 0;
        int shortOneParagraph = 0;
        for (int seed = 0; seed < 20000; seed++) {
            WatchaReviewPlan shortPlan = WatchaReviewPlan.plan(seed, 0, 0, WatchaReviewPlan.Persona.SHORT);
            if (!shortPlan.skip) {
                shortTotal++;
                if (shortPlan.image) {
                    shortWithImage++;
                }
                if (shortPlan.paragraphs == 1) {
                    shortOneParagraph++;
                }
                assertTrue("短评人格不该写出长文：" + shortPlan.targetChars, shortPlan.targetChars <= 120);
            }
            WatchaReviewPlan longPlan = WatchaReviewPlan.plan(seed, 0, 0, WatchaReviewPlan.Persona.LONG);
            if (!longPlan.skip) {
                longTotal++;
                if (longPlan.image) {
                    longWithImage++;
                }
                assertTrue("长评人格不该写出短评：" + longPlan.targetChars, longPlan.targetChars >= 350);
            }
        }
        double shortImageRate = shortWithImage * 1.0 / shortTotal;
        double longImageRate = longWithImage * 1.0 / longTotal;
        double shortSingleParagraph = shortOneParagraph * 1.0 / shortTotal;
        assertTrue("短评型几乎不配图，实际 " + shortImageRate, shortImageRate < 0.05);
        assertTrue("长评型经常配图，实际 " + longImageRate, longImageRate > 0.60);
        assertTrue("短评型以单段为主，实际 " + shortSingleParagraph, shortSingleParagraph > 0.85);
    }

    @Test
    public void mediumPersonaMatchesAccountHistory() {
        int total = 0;
        int oneParagraph = 0;
        int tooLong = 0;
        for (int seed = 0; seed < 20000; seed++) {
            WatchaReviewPlan plan = WatchaReviewPlan.plan(seed, 0, 0, WatchaReviewPlan.Persona.MEDIUM);
            if (plan.skip) {
                continue;
            }
            total++;
            if (plan.paragraphs == 1) {
                oneParagraph++;
            }
            if (plan.targetChars > 400) {
                tooLong++;
            }
        }
        double single = oneParagraph * 1.0 / total;
        // 超过 260 字会强制分段，所以单段占比落在 40% 上下，和站点实测的 42% 一致
        assertTrue("单段占比应接近实测的 42%，实际 " + single, single > 0.33 && single < 0.52);
        assertTrue("中等人格不该经常写超长文", tooLong * 1.0 / total < 0.05);
    }

    @Test
    public void rhythmProducesBurstsAndGaps() {
        int active = 0;
        int longestGap = 0;
        int longestRun = 0;
        int gap = 0;
        int run = 0;
        java.time.LocalDate start = java.time.LocalDate.of(2026, 1, 1);
        for (int index = 0; index < 730; index++) {
            boolean on = WatchaReviewPlan.activeOn(start.plusDays(index).toEpochDay(), 0.3);
            if (on) {
                active++;
                if (gap > longestGap) {
                    longestGap = gap;
                }
                gap = 0;
                run++;
                if (run > longestRun) {
                    longestRun = run;
                }
            } else {
                gap++;
                run = 0;
            }
        }
        double perWeek = active / (730 / 7.0);
        assertTrue("每周应在 3-5 条之间，实际 " + perWeek, perWeek > 3 && perWeek < 5);
        assertTrue("真人会有连续写的日子，实际最长 " + longestRun + " 天", longestRun >= 4);
        assertTrue("真人会有整段空档，实际最长 " + longestGap + " 天", longestGap >= 5);
    }

    @Test
    public void delayStaysZeroWhenDisabled() {
        for (int seed = 0; seed < 200; seed++) {
            assertEquals(0, WatchaReviewPlan.plan(seed, 0, 0).delayMinutes);
        }
    }
}
