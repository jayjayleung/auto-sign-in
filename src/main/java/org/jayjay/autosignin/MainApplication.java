package org.jayjay.autosignin;


import org.jayjay.autosignin.util.ApiUtil;

public class MainApplication {


    public static void main(String[] args) throws Exception {
        String test = System.getenv("TEST");
        System.out.println("test:"+test);
        System.out.println("开始签到。。。");
        ApiUtil.checkInModb();
        ApiUtil.checkInTidb();
        System.out.println("永洪签到start");
        ApiUtil.checkInYongHong();
        System.out.println("永洪签到end");
        System.out.println("结束签到。。。");
    }
}
