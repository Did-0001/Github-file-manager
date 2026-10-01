# Master Project Roadmap & Requirements

This document is the permanent source of truth for all requirements that must be implemented and verified across project phases.

## 1. Core Transfer Reliability
- WorkManager is the only transfer executor.
- Transfers are durably persisted in Room database.
- Startup recovery reconciles unfinished active work.
- Uploads never commit when any required item failed.
- Branch reviewed-SHA protection cannot be bypassed by retry/resume.
- No accidental force-update for normal uploads.
- Retryable and permanent errors are distinguished.
- Download integrity is verified before SUCCESS.
- Process-death recovery is idempotent.
- SAF permissions required by background work are persisted.

## 2. Upload
- Exact GitHub destination path specification and handling.
- GitHub path picker for selecting target folder.
- Skip unchanged option.
- Correct remote SHA comparison.
- Wipe-before-upload modes (none, destination folder, changed/uploaded folders, entire branch).
- Preflight/diff calculation before committing changes.
- Safe duplicate-path detection.

## 3. Download
- Repository / folder / single file / selected-item scopes.
- Exact selected-path persistence.
- Collision policies: overwrite / skip existing / keep both.
- ZIP archive creation for every supported scope.
- Correct byte-level and item-level progress reporting.
- Corruption detection and recovery.
- Crash and process-death recovery.
- Destination collision detection.

## 4. GitHub Browsing
- Live repository contents from GitHub REST API.
- Recursive browsing through directory trees.
- Path picker popup with scope filtering.
- Breadcrumb and parent navigation.
- Refresh and error retry states.
- Large-tree handling without silent truncation.

## 5. Repository and Branch Management
Inside the selected repository:
- Branch selector.
- Create branch from current or default HEAD.
- Rename branch.
- Delete branch with safety guards.
- Set default branch where permitted.
- Refresh branch metadata.
- Branch details view (latest commit, author, message).
- Protected / default branch status indicators.
- Safe branch switching preventing transfer inconsistency.
- Branch management accessible from Repo Browser overflow as a shortcut.

## 6. Wipe Modes
Normal wipe options:
- No wipe
- Wipe selected folder
- Wipe changed/uploaded folders
- Wipe entire branch
When wipe is enabled, show the appropriate selector and destructive preflight.

## 7. History Clear
Separate option below normal wipe:
- "Also clear Git history" with explicit history-wipe modes (orphan root commit).
- History rewriting must never happen during an ordinary upload.

## 8. Cache Architecture
- Repository metadata cache.
- Branch metadata cache.
- File/directory metadata cache.
- Optionally bounded file-content cache.
- Configurable expiration policy.
- Refresh bypass.
- Clear cache action in settings.
- Clear private cached content on sign-out.
- Never trust cache for destructive / preflight / branch-integrity operations.
- Clear warning explaining stale data and private local storage.

## 9. Authentication
- Successful authentication closes the dialog.
- Storage failure is surfaced to the user.
- Hardware-backed Android KeyStore encryption (AES-256-GCM).
- No credential logging or leaking.
- Cached private content is cleared when appropriate on sign-out.

## 10. UI & User Experience
- Remove Quick Actions from HomeScreen to streamline interface.
- Keep bottom navigation bar coherent across tabs (Home, Upload, Download, Explorer, Transfers, Settings).
- Reuse shared components rather than duplicate implementations.
- Coherent, responsive layouts meeting Material 3 design guidelines.

## 11. Quality & Verification Standards
- Production code must be tested, not merely simulated.
- Use real production engine/repository paths in important tests.
- Use MockWebServer for HTTP behavior.
- Use WorkManager test support where appropriate.
- Room migration tests.
- UI tests for critical flows under Robolectric.
- No false "100% verified" claims without executing verification commands.
- Clean compilation (`compile_applet`) and test runs (`testDebugUnitTest`).

## 12. Execution Protocol
All future phases must:
1. Read "AGENTS.md"
2. Read "IMPLEMENTATION_STATUS.md"
3. Read ".ai/ACTIVE_TASK/MASTER_PROMPT.md"
4. Read ".ai/ACTIVE_TASK/NEXT_PROMPT.md"
5. Inspect the actual source/diff
6. Never trust chat history over the repository

For every meaningful implementation action:
1. Update "IMPLEMENTATION_STATUS.md" to "IN_PROGRESS" with the exact action and files.
2. Perform only that action.
3. Update status to "EDIT_SAVED".
4. Run the smallest relevant verification.
5. Update status to "VERIFIED" or "VERIFICATION_FAILED".
6. Only then start another meaningful action.
