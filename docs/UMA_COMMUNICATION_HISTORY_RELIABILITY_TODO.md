# TODO: Uma Communication History Reliability

Priority: **P0**  
Status: **Open / recurring regression**  
Scope: Agora Workbench customization

## Problem

A real-device session can report:

```json
{"enabled":true,"requests":[],"responses":[]}
```

while Hachimi Edge and URA are loaded, the game is connected, localhost HTTP is running, and the home screen is performing normal network activity. Entering a training run is not a prerequisite for protocol capture.

This has regressed repeatedly. Stop treating it as a one-off hook patch; implement end-to-end health, persistence, and regression coverage.

## P0 deliverables

- [ ] Split capture state into configured, API available, hook installed, trampoline ready, callback seen, record count, last activity, and failure reason.
- [ ] Never present `enabled=true` as proof that capture is working.
- [ ] Read and surface `/api/sniff/status` and `/debug/hookdiag` automatically.
- [ ] Distinguish raw capture availability from sanitized metadata availability.
- [ ] Trace counters across SO ring, localhost endpoint, repository/view-model, and UI.
- [ ] Find and remove unintended clear/reset paths during navigation, recomposition, background restore, and soft restart.
- [ ] Detect loaded SO version/build SHA/path and warn about stale or duplicate artifacts.
- [ ] Show an actionable degraded/error state instead of an unexplained empty history.

## P1 deliverables

- [ ] Add a bounded redacted diagnostic bundle: versions, hook state, errors, counters, timestamps, direction/path/size/local IDs only.
- [ ] Add session boundaries and explicit clear audit information.
- [ ] Preserve unmatched request/response observations rather than silently dropping them.
- [ ] Add automated and device regression checks for home-screen traffic without entering training.

## Acceptance criteria

1. Normal home-screen server activity produces at least one sanitized metadata/history item within a bounded observation window.
2. If no item appears, the UI identifies the failing layer and reason; `enabled=true + []` alone is not acceptable.
3. Navigation, background restore, recomposition, and soft restart do not silently erase history.
4. Repeated cold-start and soft-restart tests pass.
5. Diagnostics never expose headers, cookies, tokens, SID values, or raw bodies.

## Relevant implementation points

- hlpatch current implementation: `hachimi_ura_plugin/src/lib.rs`
- Agora local reader/protocol capture: locate `UmaToolProvider` and `UmaProtocolCapture`
- Candidate hook chain: `CompressRequest`, `DecompressResponse`, `WWWRequest.Post`, UnityWebRequest observer, Hachimi V3 Interceptor/trampoline

Related context is archived in `xf8410/uma-ai-context/context/2026-08-01_Agora通讯历史再次空白待办.md`.
