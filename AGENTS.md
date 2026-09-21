# Agent Operating Instructions

These instructions are permanent guidelines for all future development turns on this project.

## 1. Never Assume a Previous Turn Finished
- Always inspect actual project state, source files, and `git diff` before continuing any work.
- Never rely on conversational memory or assumptions about what previous turns accomplished.

## 2. Chunked Work
- Perform only one logical improvement phase per turn.
- Within a phase, divide work into small meaningful actions.
- Do not continue into the next phase automatically.

## 3. Transactional Checkpoint Protocol
Before every meaningful source/config/test edit, immediately update `IMPLEMENTATION_STATUS.md` with:
- `State: IN_PROGRESS`
- Action name
- Exact files expected to change
- What the action is intended to accomplish

Then perform that ONE action.

Immediately after the edit is saved, update the status in `IMPLEMENTATION_STATUS.md` to:
- `State: EDIT_SAVED`
- Exact files actually changed
- Note that verification has not yet completed

Run the smallest relevant verification (e.g., targeted unit tests or compile check).

Then immediately update the status in `IMPLEMENTATION_STATUS.md` to:
- `State: VERIFIED` if successful
- Or `State: VERIFICATION_FAILED` with the actual failure details

Only after verification succeeds may the next meaningful action begin.
If verification fails, remain on the current action and fix only that action.

## 4. Never Fabricate Progress
- Do not claim compilation, tests, or fixes succeeded unless the command actually ran and its result was observed directly.
- Always include real command output or verifiable results.

## 5. Git Diff is Ground Truth
At recovery time, compare:
- Checkpoint state in `IMPLEMENTATION_STATUS.md`
- Actual source files
- `git diff` output
- Test/build output

Never blindly trust the checkpoint if the source code or git diff contradicts it.

## 6. Timeout Safety
- If execution is interrupted at any point, leave the checkpoint showing the current action/state whenever possible.
- Never rush or start additional work merely to "finish before timeout."

## 7. Scope Control
- Do not modify or redesign unrelated files or features.
- Adhere strictly to the requested phase and tasks.

## 8. Recovery Protocol
When asked to recover or continue:
1. Read `AGENTS.md`.
2. Read `IMPLEMENTATION_STATUS.md`.
3. Inspect actual source and `git diff`.
4. Reconstruct the interrupted action.
5. Repair incomplete work.
6. Verify it with the appropriate test or build check.
7. Update the checkpoint.
8. Stop and report status.
