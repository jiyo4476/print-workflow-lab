# PRD-06 — Multiple input devices

| Phase | Size | Depends on | Branch |
|---|---|---|---|
| AFP | S | 05 | `feature/06-input-devices` |

## Summary

Configure several hot folders, each feeding its own workflow (PDF statements in one folder, AFP
statements in another), in a single `config/input-devices.yaml`.

## Why

Production systems receive work from many sources at once, and each input device is configured
with the workflow it feeds. With both PDF and AFP workflows in place, a single hard-coded hot
folder is the obvious limitation. This PRD also backfills tests for `HotFolderWatcher`, which the
skeleton shipped without.

## Users and scenarios

- **Workflow designer** adds a device `{ name: afp-statements, folder: hotfolder/afp, glob:
  "*.afp", workflow: workflows/statements-afp.yaml }`.
- **Operator** drops a PDF and an AFP file into their folders; each runs the right workflow.

## Goals

- `config/input-devices.yaml` listing `{ name, folder, glob, workflow }`.
- One watcher per device, sharing one job executor.
- Startup validation of the device list.
- Proper tests for `HotFolderWatcher`.

## Non-goals

- Network inputs (LPD, FTP/SFTP, REST upload).
- Changing devices while the app is running (restart to reload).
- Per-device concurrency limits.

## Requirements

**Functional**
- **FR-1** Devices load from YAML; each references an existing workflow file.
- **FR-2** Startup fails on: a missing workflow file, duplicate device names, or two devices with
  the same folder and glob.
- **FR-3** Each device runs its own `HotFolderWatcher`; all submit jobs to one shared executor.
- **FR-4** A file that matches no device's glob is left in place and logged once (not on every
  scan).
- **FR-5** The job records which device it came from (job property `inputDevice`).

**Non-functional**
- **NFR-1** Watcher tests wait with a timeout and never use fixed sleeps.

## Design notes

- `InputDeviceConfig` (Jackson YAML) → `List<InputDevice>`.
- `App run` with no argument loads `config/input-devices.yaml`; `App run <workflow.yaml>` keeps
  working as a single-device shortcut.
- PRD-14 starts the same devices from `WorkflowContextListener` inside Tomcat.

## Acceptance criteria

| # | Criterion | Verified by |
|---|---|---|
| AC-1 | Valid config loads; invalid config fails at startup with a clear message | `InputDeviceConfigTest.*` |
| AC-2 | Watcher picks up existing and new files, waits for writes to finish, ignores other globs | `HotFolderWatcherTest.*` |
| AC-3 | PDF and AFP files in their folders run their own workflows | `InputDevicesTest.pdfAndAfpDroppedInTheirFoldersRunTheirWorkflows` |

## Test plan

- Unit — `InputDeviceConfigTest`: `loadsDevicesFromYaml`, `missingWorkflowFileFailsAtStartup`,
  `duplicateDeviceNamesFail`, `twoDevicesOnSameFolderAndGlobFail`
- Unit — `HotFolderWatcherTest`: `picksUpFilesPresentAtStartup`, `picksUpNewFile`,
  `waitsUntilFileStopsGrowing`, `ignoresFilesNotMatchingGlob`
- Integration — `InputDevicesTest.pdfAndAfpDroppedInTheirFoldersRunTheirWorkflows`

## Risks and open questions

- `WatchService` behavior differs between Windows and Linux. CI covers Linux; run the watcher
  tests on Windows by hand once.

## Definition of done

- Shared DoD, plus: README documents `config/input-devices.yaml`, and the concept map updates
  "Input device / hot folder".
