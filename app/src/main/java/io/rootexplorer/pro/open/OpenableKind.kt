package io.rootexplorer.pro.open

import io.rootexplorer.pro.core.archive.ArchiveFormat
import io.rootexplorer.pro.core.filesystem.FileEntry
import io.rootexplorer.pro.core.filesystem.FileTypeDetector

/** What opening an entry should actually do. */
enum class OpenableKind {
    /** Navigate into it. */
    DIRECTORY,

    /** Show it in the image viewer. */
    IMAGE,

    /** Show it in the archive browser. */
    ARCHIVE,

    /** Open it in the text editor. */
    TEXT,

    /** Offer to run it (the script dialog), because a shell script can be executed, not just read. */
    SCRIPT,

    /** Nothing can be done with it yet, and the UI must say so rather than doing nothing. */
    UNSUPPORTED,
}

/**
 * Extensions the text editor is offered for.
 *
 * The editor itself refuses anything that turns out to be binary — it sniffs NUL bytes and the
 * control-character ratio before decoding — so this list can afford to be generous: a mistake here
 * costs an honest "这是一个二进制文件" message, not a corrupted file.
 */
private val TEXT_EXTENSIONS = setOf(
    "txt", "text", "log", "md", "markdown", "rst",
    "json", "json5", "xml", "html", "htm", "csv", "tsv", "sql",
    "ini", "conf", "cfg", "config", "prop", "properties", "env",
    "yaml", "yml", "toml", "gradle", "pro", "gitignore", "editorconfig",
    "kt", "kts", "java", "js", "mjs", "cjs", "ts", "tsx", "jsx",
    "py", "c", "cc", "cpp", "h", "hpp", "cs", "go", "rs", "rb", "php", "lua", "pl", "swift",
    "bat", "cmd", "ps1", "srt", "vtt", "diff", "patch",
)

/**
 * Extensions offered the script dialog.
 *
 * Deliberately only the `sh` family: these are the files the dialog can actually *run*, because it
 * invokes `sh <path>` rather than relying on the executable bit or a shebang. A binary pretending
 * to be a script is not something to guess at.
 */
private val SCRIPT_EXTENSIONS = setOf("sh", "bash", "zsh", "ksh", "ash", "mksh")

/**
 * Decide what "opening" [entry] means.
 *
 * Kept pure and outside the UI so the rule can be tested without a device. A directory — or a
 * symbolic link that leads to one — is navigation; a recognised image goes to the viewer; a name
 * the archive engine can parse goes to the archive browser; a shell script gets the run dialog; a
 * text file goes to the editor; everything else is reported as unsupported, which is an outcome
 * the UI has to state out loud.
 */
fun openableKind(entry: FileEntry): OpenableKind = when {
    entry.isDirectory -> OpenableKind.DIRECTORY

    // Following a link to a directory is navigation, which is also what a file manager does.
    entry.isSymlink && entry.linkTargetIsDirectory == true -> OpenableKind.DIRECTORY

    FileTypeDetector.detect(entry).category == FileTypeDetector.Category.IMAGE ->
        OpenableKind.IMAGE

    // Checked before TEXT, because the script dialog can run these while the editor can only read
    // them — and a `.sh` is far more often something to run than something to read.
    entry.extension in SCRIPT_EXTENSIONS -> OpenableKind.SCRIPT

    // Only names the engine can actually parse qualify; `.apk` and friends fall through to
    // unsupported rather than opening a browser that could only fail.
    ArchiveFormat.fromName(entry.name) != null -> OpenableKind.ARCHIVE

    entry.extension in TEXT_EXTENSIONS -> OpenableKind.TEXT

    else -> OpenableKind.UNSUPPORTED
}
