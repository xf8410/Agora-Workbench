#!/usr/bin/env python3
from pathlib import Path

# 1. Remove generation-only tail spacer. It creates a large blank region after the latest reply.
p = Path('app/src/main/java/com/newoether/agora/ui/chat/MessageList.kt')
s = p.read_text()
old = '''    val extraPadding = if (!isLoading || lastUserMessageIndex == -1 || viewportHeight == 0) {
        0.dp
    } else {
        with(density) {
            val vDp = viewportHeight.toDp()
            val targetTopDp = 140.dp
            val availableSpaceDp = vDp - targetTopDp - (bottomBarHeight + 8.dp)
            var contentHeightPx = 0
            for (i in lastUserMessageIndex until messages.list.size) {
                contentHeightPx += messageHeights[messages.list[i].id] ?: 0
            }
            val contentHeightDp = contentHeightPx.toDp()
            (availableSpaceDp - contentHeightDp).coerceAtLeast(0.dp)
        }
    }
'''
new = '''    // The list already has bottom contentPadding for the composer. A viewport-filling tail
    // spacer leaves a large blank region after the newest reply and also makes "scroll to bottom"
    // stop on whitespace instead of the actual final message.
    val extraPadding = 0.dp
'''
if s.count(old) != 1:
    raise SystemExit('MessageList extraPadding block mismatch')
s = s.replace(old, new)
p.write_text(s)

# 2. Opening an existing conversation means actual list bottom, not last USER message.
p = Path('app/src/main/java/com/newoether/agora/ui/chat/ChatApp.kt')
s = p.read_text()
old = '''            val targetIndex = messages.indexOfLast { it.participant == Participant.USER }

            if (targetIndex != -1) {
                try {
                    withTimeout(4000) {
                        snapshotFlow {
                            val sum = messageHeights.values.sum()
                            Triple(messages, sum, viewportHeightPx)
                        }.collectLatest { data ->
                            val currentMsgs = data.component1()
                            val vHeight = data.component3()

                            val currentTargetIndex = currentMsgs.indexOfLast { it.participant == Participant.USER }

                            if (currentTargetIndex != -1 && vHeight > 0) {
                                with(density) {
                                    var totalHeightBeforePx = 0
                                    for (i in 0 until currentTargetIndex) {
                                        totalHeightBeforePx += messageHeights[currentMsgs[i].id] ?: 0
                                    }
                                    listState.scrollToItem(currentTargetIndex, 0)
                                }
                            }

                            delay(32)
                            this@withTimeout.cancel()
                        }
                    }
                } catch (e: Exception) {
                    // Timeout or intended cancellation
                }
            }
'''
new = '''            if (messages.isNotEmpty()) {
                try {
                    withTimeout(4000) {
                        snapshotFlow {
                            Triple(messages, listState.layoutInfo.totalItemsCount, viewportHeightPx)
                        }.filter { (currentMsgs, itemCount, height) ->
                            currentMsgs.isNotEmpty() && itemCount >= currentMsgs.size && height > 0
                        }.first()
                    }
                    // MessageList has one trailing spacer item. Scroll to it so the newest MODEL
                    // reply is fully visible immediately when an existing conversation is opened.
                    listState.scrollToItem(messages.size, 0)
                } catch (e: Exception) {
                    // Timeout or intended cancellation; release switching below.
                }
            }
'''
if s.count(old) != 1:
    raise SystemExit('ChatApp open-scroll block mismatch')
s = s.replace(old, new)
p.write_text(s)
