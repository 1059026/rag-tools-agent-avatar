package com.example.demo.service;

import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

/**
 * 第二个 UI 工具：与 {@link EmergencyDutyUiTools} 并存，便于测试多工具并存时的调用与前端解析是否互相干扰。
 */
@Component
public class EmergencyEventUiTools {

    public static final String EVENT_DATA_MARKER = "[UI:EVENT_DATA]";

    @Tool("当用户想查看、打开事件数据、事件列表、突发事件统计、警情/火情/险情数据等与事件案例、事件指标数据浏览相关的内容时调用。"
            + "不要用于排班、值班表场景；与 showDutyList 区分，仅在与事件数据相关时调用本工具。")
    public String showEventData() {
        return EVENT_DATA_MARKER;
    }
}
