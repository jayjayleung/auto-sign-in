package org.jayjay.autosignin;


import org.jayjay.autosignin.entity.NotificationSection;
import org.jayjay.autosignin.task.MoDbCheckInTask;
import org.jayjay.autosignin.task.TiDbCheckInTask;
import org.jayjay.autosignin.task.YunqiaoCheckInTask;
import org.jayjay.autosignin.task.YongHoneCheckInTask;
import org.jayjay.autosignin.util.MessageRenderer;
import org.jayjay.autosignin.util.NotificationSender;

import java.util.ArrayList;
import java.util.List;

public class MainApplication {


    public static void main(String[] args) {
        List<NotificationSection> sections = new ArrayList<>();
        // 各站点独立执行并汇总结果，单个站点失败不会阻断其他站点。
        sections.add(new MoDbCheckInTask().run().getNotificationSection());
        sections.add(new TiDbCheckInTask().run().getNotificationSection());
        sections.add(new YongHoneCheckInTask().run().getNotificationSection());
        sections.add(new YunqiaoCheckInTask().run().getNotificationSection());
        System.out.println("================================================================================================================");
        System.out.print(MessageRenderer.toPlainText(sections));
        new NotificationSender().send(sections);
    }
}
