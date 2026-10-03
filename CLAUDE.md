# Claude Repository Instructions

Follow all repository instructions in `AGENTS.md`.

## Branches

- One branch only: `main`. Commit and push all work directly on `main`.
- Never create feature, `codex/`, `fix/`, `feat/` or release branches, and never open a PR
  from a new branch of this repository.
- If a tool forces a temporary branch or worktree (for example `isolation: "worktree"` agents),
  bring its commits onto `main` and delete the branch, locally and on `origin`, before the task
  is finished.
- Releases are cut from `main` with `./release.py`.

## Releases

- Unless the user explicitly requests a minor, major, or exact version, “do/make a release”
  means the smallest patch release: increment only the last digit (`x.y.Z`).
