from __future__ import annotations

from models import ToolResult

DUTY_LIST_DATA = """[UI:DUTY_LIST]
| 姓名 | 岗位 | 联系电话 | 班次 |
|------|------|----------|------|
| 张伟 | 带班负责人 | 138****1001 | 5月4日 08:00–20:00 |
| 李娜 | 值班员 | 139****2002 | 5月4日 08:00–20:00 |
| 王强 | 联络员 | 137****3003 | 5月4日 20:00–次日08:00 |"""

EVENT_DATA = """[UI:EVENT_DATA]
| 事件编号 | 类别 | 等级 | 状态 | 更新时间 |
|----------|------|------|------|----------|
| EVT-2026-0504-01 | 危化泄漏 | III 级 | 处置中 | 2026-05-04 09:42 |
| EVT-2026-0503-12 | 森林火情 | IV 级 | 已控制 | 2026-05-03 18:10 |
| EVT-2026-0502-03 | 城市内涝 | IV 级 | 已结束 | 2026-05-02 11:05 |"""

TOOL_MAP = {
    "showDutyList": ToolResult(
        name="showDutyList",
        content=DUTY_LIST_DATA,
        ui_marker="[UI:DUTY_LIST]",
    ),
    "showEventData": ToolResult(
        name="showEventData",
        content=EVENT_DATA,
        ui_marker="[UI:EVENT_DATA]",
    ),
}


def execute_tool(name: str, _args: dict) -> ToolResult:
    tool = TOOL_MAP.get(name)
    if tool is None:
        return ToolResult(name=name, content=f"Unknown tool: {name}")
    return tool
