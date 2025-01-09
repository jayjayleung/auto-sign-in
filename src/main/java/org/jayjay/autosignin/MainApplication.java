package org.jayjay.autosignin;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class MainApplication {

    public static void main(String[] args) {
        String test = System.getenv("test");
        System.out.println("test"+test);
        log.info("test{}",test);
    }
}
