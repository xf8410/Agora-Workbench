from pathlib import Path

path = Path("app/src/main/java/com/newoether/agora/viewmodel/MessageGenerationController.kt")
text = path.read_text()
old = '''            val placeholder = ChatMessage(
                id = modelMessageId, parentId = userMessageId, text = "", participant = Participant.MODEL,
                status = MessageStatus.SENDING, timestamp = startTime, modelName = modelId
            )
            state.streamUpdate(myUiToken, placeholder)
            ifOpenOn(genId) { allMessages.update { it.filter { m -> m.id != modelMessageId } + placeholder } }
'''
new = '''            val userMessage = ChatMessage(
                id = userMessageId, parentId = lastMessageId, text = text,
                images = allImages, participant = Participant.USER,
                status = MessageStatus.SUCCESS, timestamp = startTime - 1,
                attachmentMeta = attachmentMeta
            )
            val placeholder = ChatMessage(
                id = modelMessageId, parentId = userMessageId, text = "", participant = Participant.MODEL,
                status = MessageStatus.SENDING, timestamp = startTime, modelName = modelId
            )
            state.streamUpdate(myUiToken, placeholder)
            ifOpenOn(genId) {
                allMessages.update {
                    ConversationTurnAppend.append(it, userMessage, placeholder)
                }
            }
'''
if old in text:
    path.write_text(text.replace(old, new, 1))
elif new not in text:
    raise SystemExit("target send block not found")
