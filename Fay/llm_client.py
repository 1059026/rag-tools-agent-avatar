from __future__ import annotations

import json
from typing import AsyncIterator

import httpx
from config import config

# DashScope Qwen 使用 OpenAI 兼容模式，方便切换 LM Studio
DASHSCOPE_BASE = "https://dashscope.aliyuncs.com/compatible-mode/v1"

SYSTEM_PROMPT = """你是一名行业知识库助手，负责行业相关知识的问答，与知识库无关的问题请拒绝回答。
当问题涉及行业管理、安全生产、防灾减灾、救援处置等场景时，请结合专业知识给出更具体、可执行的建议。

重要：你拥有工具可以展示值班表和事件数据面板，但你必须严格判断用户意图：
- 只有当用户明确要求查看"值班表"、"排班"、"谁在值班"、"值班人员"时，才调用 showDutyList
- 只有当用户明确要求查看"事件数据"、"事件列表"、"警情"、"险情"时，才调用 showEventData
- 对于一般闲聊、知识问答、计算题等无关问题，绝对不要调用任何工具
- 调用工具后，工具返回的整段字符串必须原样保留在你的回复中，不要改写、翻译或放进代码块"""

TOOLS = [
    {
        "type": "function",
        "function": {
            "name": "showDutyList",
            "description": "显示值班列表，包含值班人员、岗位、电话、班次信息",
            "parameters": {"type": "object", "properties": {}, "required": []},
        },
    },
    {
        "type": "function",
        "function": {
            "name": "showEventData",
            "description": "显示事件数据面板，包含事件编号、类别、等级、状态、更新时间",
            "parameters": {"type": "object", "properties": {}, "required": []},
        },
    },
]

# 工具触发关键词
TOOL_KEYWORDS_DUTY = ["值班", "排班", "值班表", "谁在值班", "值班人员", "在岗"]
TOOL_KEYWORDS_EVENT = ["事件数据", "事件列表", "警情", "险情", "事件", "灾情", "事故"]


def _build_client() -> httpx.AsyncClient:
    if config.LLM_PROVIDER == "dashscope":
        base = DASHSCOPE_BASE
        key = config.DASHSCOPE_API_KEY
    else:
        base = config.LM_STUDIO_BASE_URL
        key = "lm-studio"

    return httpx.AsyncClient(
        base_url=base,
        headers={
            "Authorization": f"Bearer {key}",
            "Content-Type": "application/json",
        },
        timeout=httpx.Timeout(60.0),
    )


def _should_enable_tools(messages: list[dict[str, str]]) -> bool:
    """检查用户最近消息是否包含工具触发关键词。"""
    for msg in reversed(messages):
        if msg["role"] == "user":
            text = msg.get("content", "")
            for kw in TOOL_KEYWORDS_DUTY + TOOL_KEYWORDS_EVENT:
                if kw in text:
                    return True
            return False
    return False


async def chat_stream(
    messages: list[dict[str, str]],
    session_id: str = "",
) -> AsyncIterator[str]:
    """流式 LLM 对话，yield 每段 delta 文本。关键词触发工具调用。"""
    model = config.LLM_MODEL if config.LLM_PROVIDER == "dashscope" else config.LM_STUDIO_MODEL
    enable_tools = _should_enable_tools(messages)

    body = {
        "model": model,
        "messages": [{"role": "system", "content": SYSTEM_PROMPT}] + messages,
        "stream": True,
    }
    if enable_tools:
        body["tools"] = TOOLS
        body["tool_choice"] = "auto"

    async with _build_client() as client:
        tool_calls_acc: dict[int, dict] = {}
        current_tool_idx: int | None = None
        finish_reason: str | None = None

        async with client.stream("POST", "/chat/completions", json=body) as resp:
            resp.raise_for_status()
            async for line in resp.aiter_lines():
                if not line.startswith("data: "):
                    continue
                data_str = line[6:]
                if data_str.strip() == "[DONE]":
                    break
                try:
                    chunk = json.loads(data_str)
                except json.JSONDecodeError:
                    continue

                choice = chunk.get("choices", [{}])[0] or {}
                delta = choice.get("delta", {}) or {}
                finish_reason = choice.get("finish_reason") or finish_reason

                # 累积 tool_calls
                tc_list = delta.get("tool_calls", [])
                for tc in tc_list:
                    idx = tc.get("index", 0)
                    if idx not in tool_calls_acc:
                        tool_calls_acc[idx] = {"id": tc.get("id", ""), "name": "", "arguments": ""}
                    if "id" in tc:
                        tool_calls_acc[idx]["id"] = tc["id"]
                    func = tc.get("function", {}) or {}
                    if func.get("name"):
                        tool_calls_acc[idx]["name"] += func["name"]
                    if func.get("arguments"):
                        tool_calls_acc[idx]["arguments"] += func["arguments"]

                # 文本内容
                content = delta.get("content", "")
                if content:
                    yield content

        # 如果 LLM 要求调用工具
        if finish_reason == "tool_calls" and tool_calls_acc:
            from tool_system import execute_tool
            tool_msgs = messages[:]
            assistant_tool_calls = []
            ui_markers: list[str] = []

            for idx in sorted(tool_calls_acc.keys()):
                tc = tool_calls_acc[idx]
                assistant_tool_calls.append({
                    "id": tc["id"],
                    "type": "function",
                    "function": {"name": tc["name"], "arguments": tc["arguments"]},
                })

            tool_msgs.append({"role": "assistant", "tool_calls": assistant_tool_calls, "content": None})

            for tc in assistant_tool_calls:
                result = execute_tool(tc["function"]["name"], {})
                tool_msgs.append({
                    "role": "tool",
                    "tool_call_id": tc["id"],
                    "content": result.content,
                })
                if result.ui_marker:
                    ui_markers.append(result.ui_marker)

            # 第二次流式调用（纯文本模式，不再调用工具）
            body2 = {
                "model": model,
                "messages": [{"role": "system", "content": SYSTEM_PROMPT}] + tool_msgs,
                "stream": True,
            }

            # 先 yield UI 标记，确保前端能解析
            for marker in ui_markers:
                yield marker
            async with _build_client() as client2:
                async with client2.stream("POST", "/chat/completions", json=body2) as resp2:
                    resp2.raise_for_status()
                    async for line in resp2.aiter_lines():
                        if not line.startswith("data: "):
                            continue
                        data_str = line[6:]
                        if data_str.strip() == "[DONE]":
                            break
                        try:
                            chunk = json.loads(data_str)
                        except json.JSONDecodeError:
                            continue
                        choice = chunk.get("choices", [{}])[0] or {}
                        delta = choice.get("delta", {}) or {}
                        content = delta.get("content", "")
                        if content:
                            yield content
