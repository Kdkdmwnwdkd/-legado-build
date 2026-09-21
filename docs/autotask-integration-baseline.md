# AutoTask Integration Baseline

This document records the boundary for the incremental AutoTask integration.

## Source and target

- Target application repository: `legado-private-armv8-release`
- Reference repository: `https://github.com/skybbk1001/legadoT.git`
- Reference revision reviewed: `d6e28c90a67b0e9b4976c92107f741ebe42d2925`
- Target application database version at baseline: `112`
- Reference application database version: `91`

## Integration rules

1. Do not cherry-pick the reference repository or copy its old UI and database history.
2. Adapt the task domain to the target repository APIs and current Compose UI system.
3. Add a new forward Room migration from the target baseline; do not reuse the reference version number.
4. Keep task execution cancellable and serialize operations that mutate the same book.
5. Only task-related files may be staged in task commits. Existing unrelated worktree changes remain untouched.

## Reviewed reference areas

- `model/AutoTask.kt`
- `model/AutoTaskRule.kt`
- `model/AutoTaskProtocol.kt`
- `service/AutoTaskService.kt`
- `utils/CronSchedule.kt`
- `data/dao/AutoTaskRuleDao.kt`
- `ui/autoTask/*`
- database, manifest, backup/restore, book detail and bookshelf entry points

## Target UI reuse boundary

The management UI will reuse the target repository's:

- `AppManagementScaffold`
- `AppManagementCard`
- `AppManagementListRow`
- `AppManagementPalette` and `rememberAppManagementPalette`
- existing Compose dialogs, icon actions and state-flow/ViewModel patterns

The reference XML task screens are treated as behavior references only.
