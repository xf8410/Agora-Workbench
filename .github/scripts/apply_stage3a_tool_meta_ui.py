#!/usr/bin/env python3
from pathlib import Path

path = Path('app/src/main/java/com/newoether/agora/ui/chat/message/MessageItemTimeline.kt')
text = path.read_text()

compact_old = '''                                Text(
                                    text = toolSummary(seg),
                                    style = ChatType.metaNormal,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
'''
compact_new = compact_old + '''                                ToolExecutionMetaLine(seg, message.status)
'''
if 'ToolExecutionMetaLine(seg, message.status)' not in text:
    if text.count(compact_old) != 1:
        raise SystemExit('compact tool summary anchor changed')
    text = text.replace(compact_old, compact_new)

timeline_old = '''                if (summary.isNotBlank()) {
                    Text(
                        text = summary,
                        style = ChatType.metaNormal,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
'''
timeline_new = timeline_old + '''                if (seg.type == "tool") {
                    ToolExecutionMetaLine(
                        segment = seg,
                        messageStatus = if (isStreaming) MessageStatus.TOOL_CALLING else MessageStatus.SUCCESS,
                    )
                }
'''
if 'messageStatus = if (isStreaming) MessageStatus.TOOL_CALLING' not in text:
    if text.count(timeline_old) != 1:
        raise SystemExit('timeline summary anchor changed')
    text = text.replace(timeline_old, timeline_new)

path.write_text(text)
