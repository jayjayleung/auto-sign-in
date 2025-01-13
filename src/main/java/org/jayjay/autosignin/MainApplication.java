package org.jayjay.autosignin;


import org.jayjay.autosignin.task.MoDbCheckInTask;
import org.jayjay.autosignin.task.TiDbCheckInTask;
import org.jayjay.autosignin.task.YongHoneCheckInTask;
import org.jayjay.autosignin.util.ApiUtil;

public class MainApplication {


    public static void main(String[] args) throws Exception {
//        String test = System.getenv("TEST");
//        System.out.println("test:"+test);
//        System.out.println("开始签到。。。");
//        ApiUtil.checkInModb();
//        ApiUtil.checkInTidb();
//        System.out.println("永洪签到start");
//        ApiUtil.checkInYongHong();
//        System.out.println("永洪签到end");
//        System.out.println("结束签到。。。");
        MoDbCheckInTask moDbCheckInTask = new MoDbCheckInTask();
        moDbCheckInTask.run();
        StringBuilder modbMsg = moDbCheckInTask.splitLine().getMessage();
        TiDbCheckInTask tiDbCheckInTask = new TiDbCheckInTask();
        tiDbCheckInTask.run();
        StringBuilder tidbMsg = tiDbCheckInTask.splitLine().getMessage();
        YongHoneCheckInTask yongHoneCheckInTask = new YongHoneCheckInTask();
        yongHoneCheckInTask.run();
        StringBuilder yonghongMsg = yongHoneCheckInTask.splitLine().getMessage();
        System.out.println("Modb 签到结果：" + modbMsg);
        System.out.println("Tidb 签到结果：" + tidbMsg);
        System.out.println("永洪签到结果：" + yonghongMsg);
    }
}
