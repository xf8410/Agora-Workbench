# P0 Plan: Conversation History Dual Failure

The user has confirmed both failure classes occur in production use:

- **Durability failure:** a conversation row/title remains but its stored message count is zero.
- **Display failure:** stored messages exist, but opening the conversation renders no text.

These require separate metrics, diagnostics, fixes, and acceptance tests.

## Track A — Message durability and recovery

- [ ] Add lifecycle audit events for user insert, model placeholder insert, streaming checkpoint, final commit, stop/error finalization, delete, import/replace, migration, backup, and restore.
- [ ] Identify every code path that can delete messages or leave a conversation row without children.
- [ ] Add an append-only bounded local generation journal so process death cannot erase the only copy before Room finalization.
- [ ] Ensure final text is durably checkpointed during long streaming, not only at successful completion.
- [ ] Audit `MessagePersistenceGuard` and preserve pre-clipping originals in local artifacts/archives where policy permits.
- [ ] Verify the conversation archive worker observes the full Room dataset and reports per-conversation message counts/checksums.
- [ ] Add a read-only orphan-title report: conversation exists, zero messages, last update, known audit reason, available backup/archive candidates.
- [ ] Implement preview-first recovery by conversationId; back up the current DB before merge and never fabricate missing text.
- [ ] Test process kill, OOM, cancellation, tool-call failure, app upgrade, migration, import replace/merge, auto-delete, and backup restore.

## Track B — Loading, tree resolution, and rendering

- [ ] Add an open-attempt identity token and reject stale Flow/mapping emissions.
- [ ] Report DB count, query count, mapped count, resolved path count, and LazyColumn input count.
- [ ] Isolate per-row decode errors.
- [ ] Repair or ignore stale selected-branch references.
- [ ] Make bounded windows ancestry-aware.
- [ ] If DB count > 0 but tree resolution is empty, display a safe chronological USER/MODEL fallback.
- [ ] Replace silent white screens with explicit Loading, Empty, MissingRows, DecodeError, TreeError, or RenderTimeout states.
- [ ] Test rapid switching, background restore, low-memory recreation, corrupt JSON, branching, hidden tool/result nodes, and 500+ messages.

## Independent acceptance metrics

1. **Durability:** completed messages remain stored after restart/process death/upgrade.
2. **Displayability:** any recoverable stored USER/MODEL text is visible through the tree or fallback.
3. **Recoverability:** genuine loss is diagnosed and restored from valid local/archive backups when available; otherwise reported as unrecoverable without invented content.
