package org.jayjay.autosignin.task;

import java.util.Random;

interface WatchaPause {

    WatchaPause NONE = new WatchaPause() {
        @Override
        public void betweenActions() {
        }
    };

    void betweenActions();

    static WatchaPause human() {
        return new WatchaPause() {
            private final Random random = new Random();

            @Override
            public void betweenActions() {
                int delay = 20000 + random.nextInt(70001);
                System.out.println("[观猹] 等待 " + (delay / 1000) + " 秒");
                CheckInTask.sleep(delay);
            }
        };
    }
}
