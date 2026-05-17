# `moe.antimony.hoshi.features.sync.v3`

Next-generation HTTP sync engine. **Not hooked up** — production sync still runs
through `..sync.http.HttpSyncReconciler`. v3 ships as parallel code with its own
unit + instrumented tests until we deliberately flip the switch.

For the design contract, see [`docs/SYNC_V3_SPEC.md`](../../../../../../../../../docs/SYNC_V3_SPEC.md).
For the motivation, see [`docs/SYNC_REDESIGN.md`](../../../../../../../../../docs/SYNC_REDESIGN.md).

## Files

- `V3SyncEngine.kt` — public entry point. One method: `syncOnce(settings, onProgress)`.
- `V3Models.kt` — all data classes (`V3LocalSnapshot`, `V3RemoteSnapshot`, `V3Plan`, `V3Action`, `V3SyncResult`, ...).
- `V3LocalState.kt` — Step 1: snapshot the local device.
- `V3RemoteState.kt` — Step 2: snapshot the remote KV store.
- `V3Planner.kt` — Step 3 (pure): compute a deterministic action list.
- `V3Executor.kt` — Step 4: apply the plan.
- `V3PushOps.kt` — single fetch-then-PUT primitive set, shared by the executor
  (and, in a later commit, by the reader hook).

## Why parallel to v2

`HttpSyncReconciler` is a five-pass algorithm whose invariants drifted over
~15 commits. v3 is a single-pass `read → list → plan → execute` design that:

- never uses the client-side cursor (full pull every sync),
- routes every write through `V3PushOps`,
- has a deterministic `V3Plan` we can inspect in tests,
- exercises every code path under an in-process `StubKvServer` in instrumentation
  tests.

The wire protocol (`docs/HTTP_SYNC_KV.md`) is unchanged. v3 reads and writes the
same KV keys, same blob shapes, against the same v2 server. Migration is a single
constructor swap in `HoshiAppContainer` when the user gives the word.
