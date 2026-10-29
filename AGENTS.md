# AGENTS.md

## Agent skills

### Issue tracker

GitHub Issues on the fork `giwankim/loop-pack-be-l2-vol5-kotlin` via the `gh` CLI. Never on upstream. A commit message names a ticket as `issue 13`; `.githooks/commit-msg` rejects a bare `#13`. See `docs/agents/issue-tracker.md`.

### Triage labels

Default vocabulary: `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: `CONTEXT.md` and `docs/adr/` at the repo root. See `docs/agents/domain.md`.
