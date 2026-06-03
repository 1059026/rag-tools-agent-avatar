package com.example.demo.service;

import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

/**
 * UI 工具：前端识别返回内容中的标记后展示值班列表面板。
 */
@Component
public class EmergencyDutyUiTools {

    /** 与前端约定的标记，须保持两边一致 */
    public static final String DUTY_LIST_MARKER = "[UI:DUTY_LIST]";

    @Tool("当用户想查看、打开或了解值班列表、值班表、排班、谁在值班等与值守排班相关的内容时调用。不要在无关闲聊中调用。")
    public String showDutyList() {
        return DUTY_LIST_MARKER;
    }
}
