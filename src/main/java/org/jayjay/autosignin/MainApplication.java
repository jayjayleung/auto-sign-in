package org.jayjay.autosignin;


import org.jayjay.autosignin.entity.MessageList;
import org.jayjay.autosignin.task.MoDbCheckInTask;
import org.jayjay.autosignin.task.TiDbCheckInTask;
import org.jayjay.autosignin.task.YongHoneCheckInTask;
import org.jayjay.autosignin.util.MessageUtil;

import java.util.ArrayList;
import java.util.List;

public class MainApplication {


    public static void main(String[] args) throws Exception {
        List<MessageList> messages = new ArrayList<>();
        messages.add(new MoDbCheckInTask().run().messageList());
        messages.add(new TiDbCheckInTask().run().messageList());
        messages.add(new YongHoneCheckInTask().run().messageList());
        System.out.println("================================================================================================================");
        messages.forEach(messageList-> messageList.getMessages().forEach(System.out::println));
        new MessageUtil().sendMsg(messages);
    }
}
