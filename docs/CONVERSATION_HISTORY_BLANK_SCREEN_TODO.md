# P0 TODO: Conversation History Blank Screen

Status: Open, recurring regression

## Correct scope

This task concerns Agora chat history: conversation titles remain visible in the drawer, but opening a historical conversation can show a blank message area with no text. It is unrelated to URA SO protocol capture.

## Required diagnostics

For every conversation-open attempt, track:

- conversation row exists
- DB message count
- bounded query result count
- mapped message count and per-row decode failures
- resolved visible-path count
- selected branch validity
- current conversation identity/open token
- switching-overlay state
- LazyColumn input count

Never collapse these failures into an unexplained blank screen.

## P0 implementation

- [ ] Add a per-open identity token; stale collectors/mapping results cannot update the newly opened conversation.
- [ ] Model explicit states: Loading, Loaded, EmptyConversation, MissingRows, DecodeError, TreeResolutionError, RenderTimeout.
- [ ] End the switching state only after the target conversation's first identity-consistent snapshot is installed.
- [ ] Make row mapping fault-isolated; one corrupt tool/attachment JSON row must not blank the whole conversation.
- [ ] If DB count is positive but tree resolution returns zero, show a safe linear USER/MODEL fallback and report tree corruption.
- [ ] Validate/repair stale selected branch references.
- [ ] Ensure bounded pagination retains or reconstructs enough ancestry to enter a long branch.
- [ ] Add a read-only conversation health report; do not delete messages during diagnosis.
- [ ] Distinguish true `messageCount=0` data loss from `messageCount>0` UI/render failure.

## Regression matrix

- 0, 1, 40, 41, 100, and 500+ messages
- bounded window beginning mid-chain
- branching and stale branch selection
- hidden tool/result nodes
- corrupt toolCallJson or attachmentMeta
- rapid A → B → C switching
- switching during generation and stop
- process recreation/background/low-memory restore

## Acceptance

Opening a conversation always reaches Loaded, Empty, or an actionable Error state. If stored messages exist, Agora must display recoverable USER/MODEL text via the normal tree or safe fallback; permanent silent white screens are not acceptable.
