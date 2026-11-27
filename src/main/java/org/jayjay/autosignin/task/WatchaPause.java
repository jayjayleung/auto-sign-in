package org.jayjay.autosignin.task;

import java.util.Random;

interface WatchaPause {

    WatchaPause NONE = new WatchaPause() {
        @Override
        public void betweenActions() {
        }
    };

    void betweenActions();

    /** 同一页面里连续点击之间的短间隔，人点按钮也不会一秒领五个奖励。 */
    default void quick() {
    }

    /** 开跑前的随机延后。默认什么都不做，只有真人节奏的实现才会真的等。 */
    default void startJitter(int maxMinutes) {
    }

    static WatchaPause human() {
        return new WatchaPause() {
            private final Random random = new Random();

            @Override
            public void betweenActions() {
                int delay = 20000 + random.nextInt(70001);
                System.out.println("[观猹] 等待 " + (delay / 1000) + " 秒");
                CheckInTask.sleep(delay);
            }

            @Override
            public void quick() {
                CheckInTask.sleep(800 + random.nextInt(2200));
            }

            @Override
            public void startJitter(int maxMinutes) {
                if (maxMinutes <= 0) {
                    return;
                }
                int minutes = random.nextInt(maxMinutes + 1);
                if (minutes <= 0) {
                    return;
                }
                System.out.println("[观猹] 随机延后 " + minutes + " 分钟再开始");
                CheckInTask.sleep(minutes * 60000L);
            }
        };
    }
}
