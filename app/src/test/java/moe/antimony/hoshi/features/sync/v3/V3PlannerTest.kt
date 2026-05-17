package moe.antimony.hoshi.features.sync.v3

/**
 * Pure-function tests for [V3Planner]. No transport, no Android, no temp folders.
 *
 * Required coverage — implementation agent must add at least:
 *  - empty local + empty remote → empty plan
 *  - local-only books → only push actions
 *  - remote-only books → only import/apply actions
 *  - mixed local/remote → both directions
 *  - bookmark LWW: local newer / remote newer / tie
 *  - chat set-union with overlapping content-addressable keys
 *  - tombstone wins over everything else for a syncId
 *  - local pending-deletion wins over remote state
 *  - shelf placement: remote newer applies / local newer skipped / tie behavior matches v2
 *  - pendingRemoteOnlyBooks reflects manifest-missing remote keys
 *  - action ordering matches spec § Step 3
 *  - within-bucket ordering is by syncId
 *  - app-settings LWW: local newer / remote newer / tie
 *  - syncId collision across content types: planner emits a stable action choice
 *    (see spec § Mandatory edge case 18).
 */
class V3PlannerTest {
    // Implementation agent: populate this file with @Test methods.
}
