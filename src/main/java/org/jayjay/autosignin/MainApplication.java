package org.jayjay.autosignin;


import org.jayjay.autosignin.entity.MessageList;
import org.jayjay.autosignin.task.MoDbCheckInTask;
import org.jayjay.autosignin.task.QuyaCheckInTask;
import org.jayjay.autosignin.task.TiDbCheckInTask;
import org.jayjay.autosignin.task.YongHoneCheckInTask;
import org.jayjay.autosignin.util.MessageUtil;
import org.jayjay.autosignin.util.RunLock;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class MainApplication {


    public static void main(String[] args) {
        Path lockPath = RunLock.pathForApplication();
        try (RunLock runLock = RunLock.tryAcquire(lockPath)) {
            if (runLock == null) {
                System.out.println("检测到当前应用已有签到任务运行，本次执行退出");
                return;
            }
            runTasks();
        } catch (IOException e) {
            throw new IllegalStateException("签到任务运行锁处理失败", e);
        }
    }

    private static void runTasks() {
        List<MessageList> messages = new ArrayList<>();
        messages.add(new MoDbCheckInTask().run().getMsg());
        messages.add(new TiDbCheckInTask().run().getMsg());
        messages.add(new QuyaCheckInTask().run().getMsg());
        messages.add(new YongHoneCheckInTask().run().getMsg());
        System.out.println("================================================================================================================");
        messages.stream().filter(MessageList::isSend).forEach(messageList-> {
            System.out.println(messageList.getTitle());
            messageList.getMessages().forEach(System.out::println);
        });
        new MessageUtil().sendMsg(messages);
    }
}
