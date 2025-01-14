package org.jayjay.autosignin.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MessageList {

    private String title;

    private List<StringBuilder> messages;
}
