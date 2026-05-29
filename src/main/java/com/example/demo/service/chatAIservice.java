package com.example.demo.service;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.spring.AiService;
import dev.langchain4j.service.SystemMessage;
import reactor.core.publisher.Flux;

@AiService
interface Assistant {
    @SystemMessage(
            "你是一名行业知识库助手，负责行业相关知识的问答和本系统的工具调用，与知识库无关的问题请拒绝回答。"
                    + "当问题涉及行业管理、安全生产、防灾减灾、救援处置等场景时，请结合专业知识给出更具体、可执行的建议。"
                    + "界面类工具（二选一或按需分别调用，勿混用）："
                    + "（1）用户要看值班表、排班、谁在值班时，只调用 showDutyList；"
                    + "（2）用户要看事件数据、事件列表、警情/险情统计类面板时，只调用 showEventData。"
                    + "每次调用后，该工具返回的整段字符串必须在你对用户的最终回复中原样保留（不要改写、不要翻译、不要放进代码块），以便前端展示对应面板；"
                    + "若用户同时需要两类信息，可依次调用两个工具并在回复中分别保留两段返回值。")
    Flux<String> chat(@MemoryId String sessionId, @UserMessage String userMessage);
}