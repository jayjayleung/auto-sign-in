package org.jayjay.autosignin;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MainApplication {

    private static final Logger log = LoggerFactory.getLogger(MainApplication.class);

    public static void main(String[] args) {
        String test = System.getenv("test");
        System.out.println("test"+test);
        log.info("test{}",test);
    }
}
