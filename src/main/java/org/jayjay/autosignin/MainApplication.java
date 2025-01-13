package org.jayjay.autosignin;


import org.jayjay.autosignin.task.CheckInTask;
import org.jayjay.autosignin.task.MoDbCheckInTask;
import org.jayjay.autosignin.task.TiDbCheckInTask;
import org.jayjay.autosignin.task.YongHoneCheckInTask;
import org.jayjay.autosignin.util.ApiUtil;
import org.jayjay.autosignin.util.MessageUtil;

import java.util.List;

public class MainApplication {


    public static void main(String[] args) throws Exception {
        List<StringBuilder> message = new MoDbCheckInTask().run().getMessage();
        message.add(CheckInTask.splitLine());
        message.addAll(new TiDbCheckInTask().run().getMessage());
        message.add(CheckInTask.splitLine());
        message.addAll(new YongHoneCheckInTask().run().getMessage());
        System.out.println("================================================================================================================");
        message.forEach(System.out::println);
        new MessageUtil().sendEmail(message);
    }
}
