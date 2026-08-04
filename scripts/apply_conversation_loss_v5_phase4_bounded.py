#!/usr/bin/env python3
from pathlib import Path

p = Path('app/src/main/java/com/newoether/agora/viewmodel/ChatViewModel.kt')
s = p.read_text()
old = '''                        }
                        }
                    }
                } else {
'''
new = '''                        }
                    }
                } else {
'''
if s.count(old) != 1:
    raise SystemExit(f'ChatViewModel brace marker count={s.count(old)}')
s = s.replace(old, new)
p.write_text(s)

p = Path('app/src/main/java/com/newoether/agora/data/local/ChatDatabase.kt')
s = p.read_text()
old = '''    @Query("""
        SELECT * FROM messages
        WHERE conversationId = :conversationId
        ORDER BY timestamp DESC, id DESC
        LIMIT :limit
    """)
    suspend fun getNewestMessagesPage(conversationId: String, limit: Int): List<MessageEntity>

    @Query("""
        SELECT * FROM messages
        WHERE conversationId = :conversationId
          AND (timestamp < :beforeTimestamp OR (timestamp = :beforeTimestamp AND id < :beforeId))
        ORDER BY timestamp DESC, id DESC
        LIMIT :limit
    """)
    suspend fun getOlderMessagesPage(
        conversationId: String,
        beforeTimestamp: Long,
        beforeId: String,
        limit: Int,
    ): List<MessageEntity>
'''
projection = '''        SELECT id, conversationId, parentId,
          CASE WHEN length(text) > :maxTextChars
            THEN substr(text, 1, :maxTextChars) || '\n\n[… message preview truncated for stability]'
            ELSE text END AS text,
          images,
          CASE WHEN thoughts IS NOT NULL AND length(thoughts) > :maxThoughtChars
            THEN substr(thoughts, 1, :maxThoughtChars) || '\n\n[… thoughts preview truncated]'
            ELSE thoughts END AS thoughts,
          thoughtTitle, tokenCount, status, participant, timestamp, thoughtTimeMs, modelName,
          CASE WHEN toolCallJson IS NOT NULL AND length(toolCallJson) > :maxToolJsonChars
            THEN NULL ELSE toolCallJson END AS toolCallJson,
          CASE WHEN attachmentMeta IS NOT NULL AND length(attachmentMeta) > :maxAttachmentMetaChars
            THEN NULL ELSE attachmentMeta END AS attachmentMeta
'''
new = f'''    @Query("""
{projection}        FROM messages
        WHERE conversationId = :conversationId
        ORDER BY timestamp DESC, id DESC
        LIMIT :limit
    """)
    suspend fun getNewestMessagesPage(
        conversationId: String,
        limit: Int,
        maxTextChars: Int,
        maxThoughtChars: Int,
        maxToolJsonChars: Int,
        maxAttachmentMetaChars: Int,
    ): List<MessageEntity>

    @Query("""
{projection}        FROM messages
        WHERE conversationId = :conversationId
          AND (timestamp < :beforeTimestamp OR (timestamp = :beforeTimestamp AND id < :beforeId))
        ORDER BY timestamp DESC, id DESC
        LIMIT :limit
    """)
    suspend fun getOlderMessagesPage(
        conversationId: String,
        beforeTimestamp: Long,
        beforeId: String,
        limit: Int,
        maxTextChars: Int,
        maxThoughtChars: Int,
        maxToolJsonChars: Int,
        maxAttachmentMetaChars: Int,
    ): List<MessageEntity>
'''
if s.count(old) != 1:
    raise SystemExit(f'DAO keyset marker count={s.count(old)}')
s = s.replace(old, new)
p.write_text(s)

p = Path('app/src/main/java/com/newoether/agora/data/repository/ConversationRepository.kt')
s = p.read_text()
old = '''    suspend fun getNewestMessagesPage(conversationId: String, limit: Int = 24): List<MessageEntity> =
        chatDao.getNewestMessagesPage(conversationId, limit.coerceIn(1, 100))
            .sortedWith(compareBy<MessageEntity> { it.timestamp }.thenBy { it.id })

    suspend fun getOlderMessagesPage(
        conversationId: String,
        beforeTimestamp: Long,
        beforeId: String,
        limit: Int = 24,
    ): List<MessageEntity> = chatDao.getOlderMessagesPage(
        conversationId, beforeTimestamp, beforeId, limit.coerceIn(1, 100)
    ).sortedWith(compareBy<MessageEntity> { it.timestamp }.thenBy { it.id })
'''
new = '''    suspend fun getNewestMessagesPage(conversationId: String, limit: Int = 24): List<MessageEntity> =
        chatDao.getNewestMessagesPage(
            conversationId, limit.coerceIn(1, 100),
            maxTextChars = 65_536, maxThoughtChars = 32_768,
            maxToolJsonChars = 131_072, maxAttachmentMetaChars = 32_768,
        ).sortedWith(compareBy<MessageEntity> { it.timestamp }.thenBy { it.id })

    suspend fun getOlderMessagesPage(
        conversationId: String,
        beforeTimestamp: Long,
        beforeId: String,
        limit: Int = 24,
    ): List<MessageEntity> = chatDao.getOlderMessagesPage(
        conversationId, beforeTimestamp, beforeId, limit.coerceIn(1, 100),
        maxTextChars = 65_536, maxThoughtChars = 32_768,
        maxToolJsonChars = 131_072, maxAttachmentMetaChars = 32_768,
    ).sortedWith(compareBy<MessageEntity> { it.timestamp }.thenBy { it.id })
'''
if s.count(old) != 1:
    raise SystemExit(f'Repository keyset marker count={s.count(old)}')
s = s.replace(old, new)
p.write_text(s)
