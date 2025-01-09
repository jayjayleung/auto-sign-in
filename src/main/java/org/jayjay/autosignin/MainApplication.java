package org.jayjay.autosignin;


import org.jayjay.autosignin.util.ApiUtil;

public class MainApplication {


    public static void main(String[] args) throws Exception {
        String test = System.getenv("TEST");
        System.out.println("test:"+test);
        System.out.println("开始签到。。。");
        ApiUtil.checkInModb();
        ApiUtil.checkInTidb();
        ApiUtil.checkInYongHong();
        System.out.println("结束签到。。。");
    }
}
