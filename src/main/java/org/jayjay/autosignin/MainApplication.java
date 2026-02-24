package org.jayjay.autosignin;


import org.jayjay.autosignin.entity.MessageList;
import org.jayjay.autosignin.task.MoDbCheckInTask;
import org.jayjay.autosignin.task.TiDbCheckInTask;
import org.jayjay.autosignin.task.YongHoneCheckInTask;
import org.jayjay.autosignin.util.MessageUtil;

import java.util.ArrayList;
import java.util.List;

public class MainApplication {


    public static void main(String[] args) {
        List<MessageList> messages = new ArrayList<>();
        messages.add(new MoDbCheckInTask().run().getMsg());
        messages.add(new TiDbCheckInTask().run().getMsg());
        messages.add(new YongHoneCheckInTask().run().getMsg());
        System.out.println("================================================================================================================");
        messages.stream().filter(MessageList::isSend).forEach(messageList-> {
            System.out.println(messageList.getTitle());
            messageList.getMessages().forEach(System.out::println);
        });
        new MessageUtil().sendMsg(messages);
    }
}
