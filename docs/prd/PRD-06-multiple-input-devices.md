# PRD-06 — Multiple input devices

| Plan phase | Size | Depends on | Branch |
|---|---|---|---|
| AFP | S | 05 | `feature/06-input-devices` |

## Summary

Configure several hot folders, each feeding its own workflow (PDF statements in one folder, AFP
statements in another), in a single `config/input-devices.yaml`.

## Why

Production systems receive work from many sources at once, and each input device is configured
with the workflow it feeds. With both PDF and AFP workflows in place, a single hard-coded hot
folder is the obvious limitation. (The `HotFolderWatcher` tests this PRD first asked for landed
with the review fixes, GitHub #17; this PR adds the multi-device ones.)

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
- **FR-2** Startup fails on: a missing or invalid workflow file, duplicate device names, two
  devices with the same folder (whatever their globs), an invalid glob, a missing field, or an
  unknown key.
- **FR-3** Each device runs its own `HotFolderWatcher`; all submit jobs to one shared executor.
- **FR-4** A file in a device's folder that doesn't match its glob is left in place and logged
  once (not on every scan). Job tickets are never inputs and never logged as unmatched.
- **FR-5** The job records which device it came from (job property `inputDevice`).
- **FR-6** Job tickets work the same on every device: `<input>.jobprops` in the device's folder
  moves with its input (`JobIntake`), and the device's workflow reads it with
  `SetJobPropsFromTextFile` in its receive phase. A ticket never matches a device's glob, so it's
  never started as a job of its own. `inputDevice` is set before the receive phase, so a
  ticket line can't override it.
- **FR-7** A device whose watcher fails (its folder deleted, unreadable or not creatable) is
  restarted after the rescan interval, not left stopped. A file claimed while the app is
  stopping goes back to its folder, so nothing is stranded in `work/`.

**Non-functional**
- **NFR-1** Watcher tests wait with a timeout and never use fixed sleeps.

## Design notes

**Decided while building it:** one watcher per device, and no two devices may share a folder
(even with different globs). Every file in a folder then belongs to that folder's device, so
"unmatched" is simple to decide and report. Folders are compared normalized (and
case-insensitively on Windows); nested folders are allowed because watching isn't recursive.

- `InputDeviceConfig` (Jackson YAML) → `List<InputDevice>`.
- `App run` with no argument loads `config/input-devices.yaml`; `App run <workflow.yaml>` keeps
  working as a single-device shortcut.
- PRD-14 starts the same devices from `WorkflowContextListener` inside Tomcat.

## Acceptance criteria

| # | Criterion | Verified by |
|---|---|---|
| AC-1 | Valid config loads; invalid config fails at startup with a clear message | `InputDeviceConfigTest.*`, `InputDevicesTest.badWorkflowFailsAtStartupNamingTheDevice`, `devicesSharingAFolderFailAtStartup` |
| AC-2 | Watcher picks up existing and new files, waits for writes to finish, ignores other globs and tickets, logs unmatched files once | `HotFolderWatcherTest.*` |
| AC-3 | PDF and AFP files in their folders run their own workflows, recording their device and carrying their tickets | `InputDevicesTest.*` |
| AC-4 | `run` with no arguments uses the devices file; `run <workflow>` is one default device | `AppDevicesTest.*` |

## Test plan

- Unit — `InputDeviceConfigTest`: `loadsDevicesFromYaml`, `missingWorkflowFileFailsAtStartup`,
  `duplicateDeviceNamesFail`, `twoDevicesOnTheSameFolderFail`, `nestedFoldersAreDifferentFolders`,
  `invalidGlobFails`, `missingFieldFailsNamingIt`, `emptyDeviceListFails`, `unknownKeyFails`
- Unit — `HotFolderWatcherTest` (the basics landed in GitHub #17): new
  `jobTicketsAreNeverHandedOffEvenWhenTheGlobMatches`, `unmatchedFileIsLoggedOnceWhileItStays`
- Unit — `JobIntakeTest.claimForADeviceRecordsTheDevice`,
  `SetJobPropsFromTextFileStepTest.ticketCannotChangeWhatTheAppSetsAtIntake`, `AppDevicesTest.*`
- Integration — `InputDevicesTest`: `pdfAndAfpDroppedInTheirFoldersRunTheirWorkflows`,
  `jobRecordsItsInputDevice`, `jobTicketTravelsWithItsInputOnAnyDevice`,
  `badWorkflowFailsAtStartupNamingTheDevice`, `devicesSharingAFolderFailAtStartup`

## Risks and open questions

- `WatchService` behavior differs between Windows and Linux. CI covers Linux; run the watcher
  tests on Windows by hand once.

## Definition of done

- Shared DoD, plus: README documents `config/input-devices.yaml`, and the concept map updates
  "Input device / hot folder".
