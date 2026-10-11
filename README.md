# Root Explorer Pro

A professional dual-pane file manager for **rooted Android phones**, written in Kotlin with
Jetpack Compose and Material 3.

Project status: **P0–P3 implemented** — the project compiles, 304 unit tests pass, and a debug
APK is produced. The file browser reads real filesystem data; nothing in the file list is
hard-coded demo content.

**Verified on real hardware.** A rooted Xiaomi 2211133C (Android 17 / API 37, 1080×2400,
KernelSU) was connected partway through the work, which changed the picture substantially: it
exposed **three bugs that no unit test could have caught** (see §5). Those are fixed and the app
now installs, launches and runs on the device without crashing.

Still not verified on hardware: the copy/move progress UI, the recycle-bin screens, hashing and
archive dialogs, and the editor — those were exercised only by unit tests and static reasoning.
§6 lists the exact state of every feature.

---

## 1. What is implemented

### P0 — project skeleton and browsing

| Feature | Status | Notes |
|---|---|---|
| Gradle project, Kotlin, Compose, Material 3 | Done | Builds a debug APK |
| Light / dark / follow-system theme | Done | Persisted; theme cycling from the drawer |
| Two-layer top bar (path + statistics) | Done | `文件夹：N  文件：M  存储：used/total` from real data |
| Dual-pane browsing, independent per pane | Done | Own path, history, selection, storage figures |
| Single-pane mode for narrow screens | Done | Toggle from the bottom bar or overflow menu |
| Real directory reading (normal storage) | Done | `java.nio` + `android.system.Os` |
| Real directory reading via root shell | **Device-verified** | Root listing of `/` and `/data` on a KernelSU device; see §5 and §6 |
| Navigate: in, up, back, forward, path entry | Done | History with forward-truncation |
| Path navigator with clickable breadcrumbs | Done | Jump to any ancestor, copy path, type a path |
| System back button | Done | Closes a dialog, then the drawer, then selection mode, then goes up a directory; only exits from the root |
| `..` row at the top of each pane | Done | Matches the reference; the top bar's up button does the same |
| Refresh | Done | Per pane |
| Sorting: name / size / date / type, asc + desc | Done | Unknown values always sort last |
| Filtering: name, extension, kind | Done | Reports how many entries it hid |
| Hidden-file toggle | Done | Persisted; reflected in the top bar |
| Multi-select, select all, invert | Done | Long-press or tap in selection mode |
| File action menu | Done | Unimplemented entries are disabled and labelled |
| Properties dialog | Done | Reads real permissions, owner, group, uid/gid |
| Permission editor (rwx matrix + octal) | Done | Applying it is P2, now implemented |
| Create folder / create file | Done | Verified by re-reading the path |
| Rename | Done | Rejects invalid names, refuses to overwrite |
| Bookmarks, home directory | Done | Persisted |
| Swap panes | Done | Re-reads both panes afterwards |
| Root capability detection | **Device-verified** | Real `su` probe reaching KernelSU, never a file-existence check |

### P1 — real file operations and the recycle bin

| Feature | Status | Notes |
|---|---|---|
| Recursive copy | Done | File-by-file with per-chunk progress |
| Recursive move | Done | Rename when on one volume, copy+delete across volumes |
| Pre-flight plan: size, file count, conflicts, free space | Done | Refuses a doomed transfer before writing |
| Conflict handling: ask / overwrite / skip / keep-both | Done | Default is to ask; the dialog explains what each choice destroys |
| Cancel a running transfer | Done | Cooperative, at chunk boundaries; reports an accurate partial count |
| Partial-failure reporting | Done | Distinguishes full success, partial success, cancellation, refusal |
| Progress UI (current file, bytes, throughput, failures) | Done | Task strip above the bottom bar, never covers the list |
| Cross-pane copy / move | Done | Source = focused pane's selection, destination = the other pane |
| Recycle bin | Done | Index-backed, so restore knows where a file came from |
| Restore from the bin | Done | Refuses to overwrite newer data at the original path |
| Permanent delete | Done | Per item, with confirmation |
| Empty the bin (all / older than 7 days / larger than 10 MB) | Done | Items that fail to delete stay listed |
| Trash index reconciliation | Done | Drops records whose payload vanished |
| Search: current directory, or recursive | Done | Root-aware; streams results as they are found |
| Search filters: name, extension, category, hidden | Done | Reuses the file-list filter so both behave identically |
| Search cancellation and run-away guards | Done | Stops on request; caps results and says the list is incomplete |
| Unit tests | Done | 99 tests for P0 + P1 |

### P2 — root filesystem, permissions and ownership

| Feature | Status | Notes |
|---|---|---|
| `chmod` through the root shell | **Implemented, not device-verified** | Every path passes through `ShellQuote` |
| **Verification by re-reading** | **Implemented, not device-verified** | An exit code of 0 is never sufficient |
| `chown` (owner and/or group) | **Implemented, not device-verified** | Names or numeric ids |
| Recursive `-R` apply for both | **Implemented, not device-verified** | Off by default; the dialog warns about the cost |
| uid/gid to name resolution | **Implemented, not device-verified** | Via the device's own account database (`id`) |
| Read-only partition handling | Done | Reported as such; **no remount is ever attempted** |
| Sensitive-path confirmation | Done | `/system`, `/vendor`, `/data/adb`, `/metadata`, and more |
| Path and id validation before exec | Done | Relative paths, `/`, NUL bytes, shell metacharacters refused |
| Special bits (setuid/setgid/sticky) | Done | Compared on the numeric mode, not the 3-digit form |
| Unit tests | Done | 30 tests for the change rules alone |

### P3 — professional tools

| Feature | Status | Notes |
|---|---|---|
| Hashing engine (MD5 / SHA-1 / SHA-256) | Done, tested | 21 tests, verified against published test vectors |
| Single-pass multi-algorithm hashing | Done, tested | Reads the file once, not once per algorithm |
| Hash progress, cancellation, real-byte reporting | Done, tested | Chunked, so a multi-gigabyte file can be stopped |
| Checksum comparison against a pasted value | Done, tested | Case- and separator-insensitive; malformed input is distinguished from a mismatch |
| Archive engine: ZIP create + extract | Done, tested | `java.util.zip`, so no third-party code on the main extraction path |
| Archive engine: TAR / TAR.GZ / BZIP2 / XZ extract | Done, tested | Through Commons Compress |
| **Zip Slip defence** | Done, tested | 8 tests: `..`, absolute, drive letters, backslashes, NUL, canonical backstop |
| Archive conflict policy (skip / overwrite / rename) | Done, tested | Never destroys unless overwrite was explicitly chosen |
| Archive progress and cancellation | Done, tested | A cancelled compress deletes its half-written output |
| Text file engine: UTF-8 + GB18030 fallback | Done, tested | 31 tests |
| Text file engine: newline detection (LF/CRLF/CR) | Done, tested | Normalised in one pass, so CRLF is not doubled |
| Text file engine: BOM preservation | Done, tested | A plain UTF-8 file is never silently given a BOM |
| Text file engine: binary refusal | Done, tested | NUL byte, or a high control-character ratio |
| Text file engine: large-file limit with real numbers | Done, tested | Reports the size and the limit |
| Text file engine: atomic save with backup | Done, tested | Backup first, temp file then replace; no partial overwrite |
| Text file engine: search and replace | Done, tested | Literal replacement, no regex or `$1` interpretation |
| **Hash dialog in properties** | Done | Reachable from 属性 and the long-press menu; progress, stop, copy, paste-to-compare |
| **Archive dialogs (compress / extract)** | Done | Format and name for compress; target and conflict choice for extract |
| **Editor screen** | Done | Reachable from 属性 → 编辑 and the action menu; encoding/BOM/line-ending aware, search and replace, unsaved-change guard |
| **Installed apps** | Done | List with icons, search, sort, system/user filter, detail dialog, export APK |
| **Terminal emulator** | Done | Persistent `sh`/`su` session, real stdout and exit status, stop, history; framing verified on device |
| Encrypted archives (zip password) | Not supported | Detected and reported, not silently mis-extracted |
| 7z / RAR extraction | Not supported | Marked as such in the format table rather than failing late |

---

## 2. Build

### Requirements

- **JDK 17+** (JDK 21 was used here)
- **Android SDK** with a platform and build-tools installed
- Optional: a rooted device with USB debugging for the Root work

### Configure the SDK path

`local.properties` is machine-specific and git-ignored. Create it with your SDK location:

```properties
sdk.dir=C\:\\Android\\Sdk
```

On the machine this was developed on the SDK lives at `H:\Android studio SDK` (the space is
escaped). If your SDK differs, only this file changes.

### Build commands

Windows:

```powershell
.\gradlew.bat :app:assembleDebug
```

macOS / Linux:

```bash
./gradlew :app:assembleDebug
```

The APK is written to:

```
app/build/outputs/apk/debug/app-debug.apk
```

Other useful tasks:

```bash
./gradlew :app:testDebugUnitTest    # 304 unit tests
./gradlew :app:lintDebug            # Android lint
./gradlew :app:assembleRelease      # unsigned release build
```

### Repository configuration

This project was built on a machine that cannot reach `dl.google.com` or `repo1.maven.org`
directly, so `settings.gradle.kts` lists reachable mirrors first and keeps `google()` and
`mavenCentral()` as fallbacks. On a machine with normal connectivity you may delete the
mirror entries; the canonical repositories follow them.

`gradle/wrapper/gradle-wrapper.properties` points the wrapper at a mirror distribution URL.
To use the official one, change it to:

```properties
distributionUrl=https\://services.gradle.org/distributions/gradle-9.8.1-bin.zip
```

### Toolchain versions actually used

These are the versions in this checkout, and they are mutually compatible:

| Component | Version |
|---|---|
| Android Gradle Plugin | 9.4.1 |
| Gradle | 9.8.1 |
| Kotlin | 2.4.21 |
| Compose BOM | 2026.09.00 |
| compileSdk / targetSdk | 37 / 36 |
| minSdk | 26 (Android 8.0) |
| Build tools | 36.0.0 |

**Two notes specific to AGP 9**, both of which will bite anyone upgrading this project:

1. AGP 9 has **built-in Kotlin support**. Applying `org.jetbrains.kotlin.android` fails the
   build. Only `org.jetbrains.kotlin.plugin.compose` is applied.
2. `compileSdk` must match the installed platform directory. On the development machine the
   only installed platform is `platforms/android-37.0`, so `compileSdk = 37`.

The AndroidX versions pinned here (Compose BOM 2026.09.00, `core-ktx` 1.19.1, lifecycle
2.11.0) publish Gradle metadata that **refuses to build with AGP below 9.1.0**, which is why
the project is on AGP 9 rather than 8.x.

---

## 3. Architecture

```
app/src/main/java/io/rootexplorer/pro/
├── RootExplorerApplication.kt      # manual DI container
├── MainActivity.kt                 # thin shell: builds the ViewModel, hosts the screens
├── core/
│   ├── filesystem/
│   │   ├── FileEntry.kt            # a listing entry; unknown fields are null, never faked
│   │   ├── FsError.kt              # typed failures (not-found / denied / read-only / ...)
│   │   ├── FsEngine.kt             # the filesystem interface + path normalisation
│   │   ├── JavaFsEngine.kt         # this process's own credentials
│   │   ├── RootFsEngine.kt         # a root shell (ls / stat / df)
│   │   ├── SystemFileSystem.kt     # chooses the engine, exposes root status
│   │   ├── StorageVolumes.kt       # StorageManager enumeration
│   │   ├── FileSort.kt             # sorting + filtering
│   │   └── FileTypeDetector.kt     # extension -> category / MIME / description
│   ├── permissions/
│   │   ├── LinuxPermission.kt      # rwx bits <-> symbolic <-> octal
│   │   └── PermissionChanger.kt    # chmod / chown with mandatory read-back verification
│   └── root/
│       ├── RootShell.kt            # su subprocess + ShellQuote (injection safety)
│       ├── RootStatus.kt           # the six root states
│       └── RootAccess.kt           # the probe and its interpretation
├── data/repository/
│   ├── FileRepository.kt           # the UI's only entry point to the filesystem
│   └── SettingsRepository.kt       # SharedPreferences-backed settings
├── feature/
│   ├── filebrowser/                # PaneState, FileBrowserUiState, ViewModel
│   ├── properties/                 # properties dialog
│   ├── permissioneditor/           # permission matrix, owner/group editor, octal parsing
│   ├── search/                     # search engine, screen and view model
│   └── trash/                      # recycle-bin screen
├── service/fileoperation/
│   ├── FileOperationsEngine.kt     # create / rename / delete, each verified by re-reading
│   └── FileOperationEngine.kt      # recursive copy/move: plan, progress, cancel, conflicts
├── service/taskmanager/
│   └── TaskManager.kt              # the one foreground task, its progress and result
├── service/trash/
│   └── TrashRepository.kt          # the recycle bin: index, restore, purge, cleanup rules
├── ui/
│   ├── components/                 # top bar, panes, drawer, dialogs, bottom bar, task bar
│   └── theme/                      # colours, typography, ThemeMode
└── util/Formatter.kt               # sizes, capacities, dates
```

Plus `app/src/test/java/...` with the unit tests.

### Design decisions worth stating

**Failures are never rendered as an empty folder.** `FsError` distinguishes not-found,
permission-denied, root-required, not-a-directory, read-only, I/O error and symlink loop, and
the pane shows the specific message with a retry action. A failed listing clears the file
list rather than leaving the previous directory's contents on screen under a new path.

**Unknown data stays unknown.** Permission bits, uid/gid, owner names, access and creation
times and file sizes are all nullable. Android's FUSE layer over `/sdcard` does not expose
POSIX permissions, and a root `ls` listing does not carry a reliable timestamp — those render
as `未知` instead of a plausible-looking default. In particular nothing defaults to `root`.

**A transfer is planned before it writes anything.** `FileOperationEngine.planCopy` totals the
bytes, counts the files, lists every name collision and checks free space. A copy that cannot
fit, or that would recurse into itself, is refused with a reason instead of half-running. The
destination-inside-source check uses canonical paths, so it also sees through Android's
`/sdcard` to `/storage/emulated/0` symlink, which a naive string comparison would miss.

**A partial result is never reported as success.** `OperationOutcome` distinguishes
`Completed` (with a possibly non-empty failure list), `Cancelled` and `Rejected`, and the task
bar renders those differently. A cancelled copy says how much it managed to do.

**A permission change is verified by re-reading, never by an exit code.** `PermissionChanger`
runs `chmod`/`chown`, then reads the path back and compares. A command that exits 0 while the
attributes are unchanged is reported as *not applied*, because that is what actually happens
on read-only partitions and some FUSE-backed mounts. Special bits are compared on the numeric
mode, so a `chmod 4755` whose setuid bit did not stick is a reported failure, not a success.

**Read-only partitions are reported, never remounted.** The brief forbids trying to remount
system partitions, so the app says the filesystem is read-only and stops.

**The recycle bin is an index, not a directory.** `index.tsv` records where each item came
from; the trash directory alone cannot tell you that, so a bin built by listing directories
could never restore anything. File names containing tabs or newlines are escaped, because both
are legal on Linux and would otherwise corrupt every record after the offending one.

**Search says when it is incomplete.** A recursive search reports the locations it could not
read (so "not found" is never confused with "could not look"), and hitting the result cap marks
the list as truncated rather than presenting a partial answer as the whole one. Leaving the
screen cancels the walk, which matters because a root search is a privileged `find`.

**The bin sits on the data's own volume when root is available.** With root it is
`/data/media/0/.RootExplorerPro/trash`, so trashing is a rename rather than a multi-gigabyte
copy; without root it falls back to the app's private directory, where a cross-volume move is
refused rather than silently turned into a slow copy.

---

## 4. Safety measures

- **No string concatenation into shell commands.** Every dynamic value passes through
  `ShellQuote`, which uses POSIX single quoting and closes/reopens around embedded quotes.
  13 unit tests cover spaces, `$( )`, backticks, `;`, `&&`, `|`, `>`, `<`, `*`, `?`, `$VAR`,
  newlines and CJK filenames.
- **`su -c <command>` is passed as a single argument**, so nothing re-parses it on the way in.
- **Root is never probed at launch.** Probing triggers the root manager's prompt, so it only
  happens on an explicit user action; a refusal is cached instead of retried.
- **Mutations are verified.** Every create / rename / delete re-reads the path afterwards and
  reports `verified = true/false`; every `chmod`/`chown` re-reads the attributes.
- **No silent overwrite.** Rename refuses when the target exists; restoring from the recycle
  bin refuses when the original path is occupied; copy only overwrites when the user chose it.
- **Recursive delete requires explicit consent.** The checkbox is disabled for files and
  mandatory for directories; the confirm button stays disabled until it is ticked.
- **Sensitive locations need a second confirmation.** Changing anything under `/system`,
  `/vendor`, `/product`, `/system_ext`, `/data/adb`, `/data/misc`, `/metadata` or
  `/data/system` first shows the risk and the consequence.
- **Input is validated before any command runs.** Relative paths, the filesystem root, NUL
  bytes and shell metacharacters in an owner/group name are refused up front.
- **The app never remounts system partitions.**
- Log-free: no authorization tokens or command contents are written to logs.

---

## 5. Testing

### Unit tests (no device needed)

```bash
./gradlew :app:testDebugUnitTest
```

304 tests, all passing:

| Suite | Tests | Covers |
|---|---|---|
| `LinuxPermissionTest` | 17 | 644/755/600/777/000, special bits, round trips, rejection of invalid input |
| `ShellQuoteTest` | 13 | command-injection safety |
| `FsEnginePathTest` | 14 | path normalisation, `..`, `//`, unicode, newlines |
| `LsLineParsingTest` | 9 | `ls -la` parsing against the device's verbatim output |
| `RootListingIntegrationTest` | 6 | end-to-end root listing: directories present, no entry titled with a link target |
| `SortAndFilterTest` | 17 | directory-first ordering, nulls-last in both directions, filters |
| `FileOperationPlanTest` | 24 | copy/move planning, conflicts, policies, real byte-for-byte copies, moves, symlink loops |
| `TrashIndexCodecTest` | 14 | tab/newline/backslash-proof recycle-bin index round trips |
| `PermissionChangerTest` | 30 | the verification rule, special bits, name/uid equivalence, validation, sensitive paths |
| `SearchEngineTest` | 19 | recursion vs single directory, filters, hidden files, partial failures, batching, the result cap |
| `HashEngineTest` | 21 | published MD5/SHA-1/SHA-256 vectors, multi-algorithm agreement, verification |
| `ArchiveEngineTest` | 30 | round trips, Zip Slip attacks, conflict policies, corrupted input |
| `TextFileEngineTest` | 31 | encodings, BOM, newline styles, binary refusal, atomic save, replace |
| `InstalledAppLogicTest` | 22 | app filter/sort rules, export naming, split-APK handling |
| `ShellSessionFramingTest` | 20 | terminal command framing, exit-status parsing, output caps |
| `PermissionDeviceEvidenceTest` | 17 | chmod/chown values measured on the device: special bits, four-digit octal, read-only fs, bad user |

### Bugs the tests caught

Five real bugs so far, none of which were visible from reading the code:
1. **A crash on launch.** The manifest declared `.RootExplorerApp` while the class is
   `RootExplorerApplication`, so the app died immediately at startup. Found by lint, fixed,
   and re-verified by dumping the built APK's manifest.
2. **A wrong permission display.** `4644` — setuid *without* execute, shown by `ls` as
   `rwSr--r--` — was parsed with the execute bit set, so the permission editor would have
   displayed `rwxr--r--` for a file that is not executable by its owner.
3. **A wrong result count.** A completed copy reported `itemsCopied = 0` while the bytes had
   in fact been written. The increment sat inside the move-only branch, so a plain copy
   reported no work done.
4. **A false success on a security-relevant change.** Verification compared the three-digit
   octal, which *drops* setuid/setgid/sticky. A `chmod 4755` whose setuid bit silently failed
   to stick would have been reported as applied. Now compared on the numeric mode.
5. **Validation defeated by normalisation.** `changeMode` normalised the path before
   validating it, and normalisation prepends `/`, so a relative path was silently turned into
   an absolute one and the "must be absolute" rule never fired.

Two test expectations were also wrong and were corrected rather than the code:
`parentOf("/data/adb/..")` correctly answers about the normalised path, and `0xFFFFF333` has
low 12 bits `0o463`, not `0o333`.

### Bugs only the real device exposed

Connecting the phone found three more, and they are the reason this section exists: all three
passed every unit test, because the tests stopped at the shell boundary.

6. **The root listing showed nothing but symlinks, each titled with its target.**
   `line.split(Regex("\\s+"), limit = 9)` does **not** mean "eight columns plus the rest" — with
   eight metadata columns it consumes all nine slots. So the name landed in field 7, field 8 held
   a symlink's `-> target`, and directories (which have no target and therefore only eight fields)
   failed the `parts.size < 9` check and were **discarded entirely**. The visible effect: `/`
   listing only symlinks, `/storage/emulated/0` and `/data` appearing empty. The parser now
   locates the name by finding the end of the date/time columns, which handles both layouts and
   preserves spaces in file names. `RootListingIntegrationTest` replays the device's verbatim
   `ls -la /` output so this cannot regress silently.

7. **Root search returned nothing.** It used `find … -exec stat`, and this device's toybox `find`
   **rejects `-exec`, `-printf`, `-mindepth` and `-print0` at runtime even though its own `--help`
   lists them** (`find: bad arg '-exec'`). The walk is now an iterative BFS over `ls`, which is
   the one tool whose behaviour was verified end to end on the device.

8. **"Granted root" still gave permission denied until the app was restarted.** `activeEngine()`
   sampled the cached root status once per request, and at launch that status is `Unknown` because
   the app deliberately does not probe (probing triggers the authorization prompt). A request
   issued in that window ran as the app's own UID, was denied, and — because the cached status was
   never re-evaluated — **kept failing until restart**. `list`/`stat` now probe and retry once on a
   root-only path, and `scan` decides the engine before walking so no entry is emitted twice.

A fourth device-only finding, from checking the terminal's framing before trusting it: this
device's `printf` correctly reports `$?` (verified `0` and `1`), multi-line output frames
correctly, and `cd` persists across frames inside one root session — so the terminal's design was
confirmed against the real shell rather than assumed.

9. **`chmod` could never apply setuid/setgid/sticky.** `LinuxPermission` has two octal accessors:
   `octal` (four digits, keeps the special bits) and `octalSimple` (`modeBits and 0x1FF`, which
   **masks them off**). `changeMode` passed `octalSimple` to `chmod`, so ticking "setuid" in the
   permission editor ran `chmod 755` instead of `chmod 4755` and the bit never landed. The editor
   compounded it by displaying and pre-filling the three-digit value, so editing a `4755` file
   showed `755` and accepting the pre-filled text silently cleared the bits — while the checkbox
   directly above still read as setuid-on.

   Found by measuring the device rather than by reading the code: `chmod 4755` works there and
   `stat -c %a` reports **four** digits (`4755`, `2755`, `1755`). That second fact is also what
   proves the verification comparison — the one that must never produce a privilege-escalation
   false success — is comparing the right thing. Fixed by using `octal` for both the command and
   the editor; `PermissionDeviceEvidenceTest` pins every measured figure.

### Installing on a device

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n io.rootexplorer.pro.debug/io.rootexplorer.pro.MainActivity
```

Note the debug build ID has a `.debug` suffix, so the component name and the FileProvider
authority both carry it.

### Recommended manual pass

1. **Storage access** — a banner appears when "all files access" is missing; the button opens
   the right Settings screen. Confirm `/storage/emulated/0` becomes browsable afterwards.
2. **Real listing** — counts in the top bar match the directory contents. Compare against
   `adb shell ls -a /storage/emulated/0 | wc -l`.
3. **Sorting** — check each field in both directions. Entries with unknown size or date must
   stay at the bottom in both directions.
4. **Filtering** — apply a filter, confirm the "已过滤 N" counter and that clearing it restores
   the list.
5. **Hidden files** — toggle and watch the count change.
6. **Selection** — long-press to enter selection mode, select all, invert, cancel.
7. **Create / rename / delete** — create a folder, rename it, delete it. Each should report a
   verified success, and refresh should reflect the disk.
8. **Properties** — compare against `adb shell stat -c '%A %a %U %G' <path>`.
9. **Permission editor** — verify the octal and symbolic strings agree with the checkboxes for
   644, 755, 600, 777, 4755, 1775.
10. **Root** — tap the drawer's authorize entry, approve the prompt, then open `/data/adb`.
    Confirm the ROOT badge appears and the drawer status reads 已获得 Root.

#### P1 manual pass

11. **Cross-pane copy** — select a few files on the left, navigate the right pane to a
    different directory, press 复制. Watch the task strip for the current file, byte count,
    throughput and the cancel button. Confirm the files exist in the right pane afterwards by
    re-reading the directory, not just by the list refreshing.
12. **Cross-pane move** — the same, with 移动. Confirm the sources are gone and the
    destinations are present with the expected contents.
13. **Cancel mid-transfer** — copy something large and press 取消. The result must say 已取消
    and report how much was copied; it must not claim success.
14. **Conflict handling** — copy a file to a directory that already has one of the same name.
    Try each choice: 保留两者 (both exist, the new one is `name (1).ext`), 覆盖 (destination now
    has the source content), 跳过 (destination unchanged).
15. **Free-space refusal** — try to copy something larger than the free space on the target.
    It must be refused before any byte is written, with the required and available sizes.
16. **Recycle bin** — select a file, press 移入回收站, then open 回收站 from the overflow menu.
    The header shows the item count, the total size and the bin's storage location.
17. **Restore** — restore an item and confirm it reappears at its original path. Then create a
    new file at that path first and confirm the restore is *refused* rather than overwriting it.
18. **Permanent delete and cleanup** — permanently delete one item, then use 清空回收站 with
    仅 7 天前 and confirm recent items survive the purge.
19. **Index reconciliation** — delete a trashed payload through another shell
    (`adb shell rm -rf <trash>/items/<id>`), press refresh in the bin, and confirm the stale
    record is dropped and reported.
20. **Search, current directory** — open a directory, overflow menu then 搜索, untick
    包含子目录, search for part of a known file name. Only files directly in that directory may
    match, and the header must say 仅此目录.
21. **Search, recursive** — tick 包含子目录 and search again. Matches from subdirectories must
    appear, each row showing its full path, and 定位 must jump the active pane to the
    containing directory.
22. **Search filters** — narrow by extension and by category and confirm the result set matches
    `adb shell find <dir> -name '*.jpg'`.
23. **Search cancellation** — start a recursive search somewhere large (`/storage/emulated/0`
    or `/data` with root) and press 停止. The status must say 已停止 and report how many entries
    were scanned; results found so far must remain. Confirm with `adb shell ps -A | grep find`
    that no `find` process is left behind.
24. **Search without root, then with** — search `/data` before granting root: the app must
    report the unreadable locations (tap the notice) rather than returning an empty result.
    Grant root, search again, and confirm those paths become searchable.
25. **Unbounded search guard** — clear all inputs and search recursively; the app must ask for
    confirmation before walking the whole tree.

#### P2 manual pass (requires a rooted device)

26. **Root probe** — before granting, the drawer reads 未检测 or 未授权; after granting,
    已获得 Root. Confirm the probe is *not* triggered by merely launching the app.
27. **chmod on shared storage** — pick a file, 属性 then 权限, set 600, 确定. Compare against
    `adb shell stat -c '%a' <path>` — they must match, and the result must say it was verified
    by re-reading.
28. **chmod on a system file** — try the same on `/system/build.prop`. Expect either a real
    read-only error, or a successful change followed by a *verified* re-read. In neither case
    may the app report success without the re-read agreeing, and it must not remount anything.
29. **Sensitive-path confirmation** — changing anything under `/system`, `/vendor`, `/data/adb`
    or `/metadata` must first show the risk dialog naming the consequence.
30. **Special bits** — set `4755` on a copy of a binary and confirm the editor and the
    properties dialog both show setuid. Then check that the app reports failure if the setuid
    bit does not actually stick.
31. **chown** — set owner `0` and group `0`, and separately try the names `root`. Both must be
    accepted and verified. Then try a name that does not exist on the device: the dialog must
    say it cannot be resolved rather than assuming root.
32. **Recursive apply** — apply `chmod -R 700` to a directory with children and confirm every
    child changed (`adb shell stat -c '%a' dir/*`).
33. **Validation** — try a relative path and a name containing `;` or `$()`; both must be
    refused before any command runs.

---

## 6. Known limitations (stated plainly)

**Root features are now partially device-verified.** A rooted Xiaomi 2211133C (Android 17 /
API 37, KernelSU) was connected for the later part of the work, and this changed the picture in
both directions.

Verified on that device:

- the `su` probe reaching KernelSU and returning a root shell (`uid=0(root)`, `context=u:r.ksu:s0`);
- root directory listings of `/` and `/data`, including the fix for bug 6 above;
- `ls -la` parsing against the device's verbatim output, pinned by
  `RootListingIntegrationTest`, so the format is real rather than assumed;
- `chmod` including every special bit: `4755` → `-rwsr-xr-x|4755`, `2755` → `-rwxr-sr-x|2755`,
  `1755` → `-rwxr-xr-t|1755`, plus `-R` recursing over a directory tree. This also established
  that `stat -c %a` returns **four** digits, which is what makes the setuid-safe verification
  comparison valid rather than accidentally always-failing;
- `chown` by name (`shell:shell` → uid/gid 2000) and by numeric id (`0:0` → root), and that a bad
  name fails loudly (`chown: bad user '…'`, exit 1) leaving ownership unchanged;
- that a read-only partition fails with `Read-only file system`, exit 1, and leaves the mode
  unchanged — the exact case where trusting an exit code instead of re-reading would lie;
- the terminal's command framing: `printf` reporting `$?` correctly (`0` and `1` both observed),
  multi-line output framed by a single status line, and `cd` persisting across frames within one
  root session;
- that the app's own UID is correctly denied where root is required (root escaping no permission
  check is not the same as the app never being denied);
- install, launch and sustained running with no crash across many rebuilds.

Still **not** verified on hardware, and honestly unknown:

- the permission editor's **UI** wiring: the rules, the octal form and the command construction are
  now pinned to device-measured values, but no one has tapped the checkboxes;
- uid/gid **name** resolution beyond `root`/`shell` (`id -nu 0` and `id -u shell` both verified);
- the copy/move progress UI, conflict prompts and task strip;
- the recycle-bin screens (move, restore, permanent delete) and the index against a real volume;
- hashing dialogs, archive compress/extract dialogs, and the text editor's save path;
- the installed-apps screen and APK export;
- the behaviour of a **different ROM** — most of the failures above were this ROM's toybox
  quirks, and a busybox device may behave differently again.

The *rules* those paths implement are covered by 304 unit tests, but those tests stop at the shell
boundary: they assert decision logic, validation and command construction, not what a particular
ROM's tools do. §5 records exactly which assumptions the device falsified.

Everything else (compilation, 304 unit tests, APK production, the non-root browsing path, the
whole copy/move engine against a real filesystem, the recycle-bin index, and the search rules)
was verified on the build machine.

**Not yet implemented.** Nothing below is silently faked: each entry is either disabled in the
UI with a visible reason, or returns an explicit message.

| Missing | Entry point behaviour | Planned |
|---|---|---|
| Open-with / share via Intent + FileProvider | Menu entries disabled, labelled P3 | P3 |
| Settings screen | Overflow entry disabled, labelled P4 | P4 |
| Plugin management, remote management, MCP, Activity log | Drawer entries disabled, labelled | Out of scope for this brief |

**Other limitations**

- Search covers names, extensions and categories. It does **not** search file *contents*,
  which would need a different engine (and, for anything large, an index).
- A recursive search runs as a single foreground operation with no queue: starting a new search
  cancels the previous one. Running two at once would make the progress figures meaningless.
- The root search walks with `ls`, one `su` invocation per directory, because this device's
  toybox `find` rejects `-exec`/`-printf`/`-mindepth` (see §5, bug 7). Correct on every device,
  but slower than a single `find` would be on a tree with many directories; the walk stops at
  2000 directories and reports the result as incomplete rather than pretending it finished.
- Copy/move runs one task at a time on purpose. Concurrent transfers against the same storage
  would make the reported throughput and partial-failure accounting meaningless; a queue is the
  right way to add concurrency, and it is not there yet.
- Moving across filesystems (for example from internal storage to a removable SD card) is a
  copy followed by deleting the source. It is real and verified, but it is not atomic: a power
  loss mid-move leaves the copy in place and the source untouched, which is the safe direction.
- The transfer engine reuses the app's own credentials when it can, and only falls back to the
  root shell to delete a source it cannot remove itself. A copy *from* a root-only path into
  ordinary storage is therefore not accelerated through `su`.
- `chmod`/`chown` run one `su` invocation per operation. There is no persistent root shell
  session, so a recursive change over a huge tree is slower than it could be.
- The permission editor has no "apply to selection" action yet: a change applies to the one
  entry it was opened from (plus its children when recursive is ticked).
- Settings use `SharedPreferences`. The brief allows DataStore or Room; migrating is listed as
  P4 work. The recycle-bin index is a flat file rather than a database.
- UI strings are inline Chinese in Kotlin rather than in `strings.xml`. Externalising them is
  P4 accessibility/localisation work.
- `AndroidManifest.xml` requests `QUERY_ALL_PACKAGES`, which the "installed apps" tool (P3)
  will need. It is unused for now and should be removed if that tool is dropped.
- Sorting by name is case-insensitive but not locale-aware, so it is not a full collator.
- Very large directories (tens of thousands of entries) are read in one pass; the per-entry
  POSIX attribute read makes the first load of such a directory slow. Paging or a coarser
  first pass is P4 performance work.
- No UI/instrumentation tests yet. The brief asks for them; the core logic is covered by unit
  tests, but Compose tests are outstanding.

---

## 7. Environment notes for this checkout

- The SDK directory on the development machine is **not writable** by the build, so
  `buildToolsVersion` is pinned to the installed `36.0.0` instead of letting AGP install the
  version it defaults to.
- Gradle's user home, its temp directory and the Android user home are redirected into the
  project (`.gradle-home/`, `.gradle-tmp/`, `.android-home/`) because the sandbox temporary
  directory prevented Gradle's native library from loading. These directories are
  git-ignored and are not part of the project.
- `tools/` contains small Node.js scripts used to bootstrap the toolchain on this machine
  (a downloader that works where `Invoke-WebRequest` TLS does not, a mirror speed test, and a
  version query). They are development aids, not part of the app.
