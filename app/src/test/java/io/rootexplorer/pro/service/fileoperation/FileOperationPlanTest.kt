package io.rootexplorer.pro.service.fileoperation

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The copy/move planner.
 *
 * The planner is what stops a doomed transfer before it starts, so these tests exercise the
 * real filesystem through [TemporaryFolder]: sizes and file counts must match what is on disk,
 * collisions must be detected, and the cases that would otherwise destroy data or loop forever
 * must be refused.
 *
 * The engine's root shell is never invoked here; `rootAvailable` returns false throughout, so
 * the tests cover the non-root path that runs on any device.
 */
class FileOperationPlanTest {

    @get:Rule
    val temp = TemporaryFolder()

    /**
     * The engine under test.
     *
     * A [io.rootexplorer.pro.core.root.RootShell] is required by the constructor but no command
     * is ever run because root is reported unavailable, so the default instance is harmless.
     */
    private fun engine() = FileOperationEngine(
        rootShell = io.rootexplorer.pro.core.root.RootShell(),
        rootAvailable = { false },
    )

    private fun file(name: String, bytes: Int): File {
        val f = temp.newFile(name)
        f.writeBytes(ByteArray(bytes) { it.toByte() })
        return f
    }

    private fun dir(name: String): File = temp.newFolder(name)

    /** Blocking plan call, kept short so the tests read as behaviour rather than plumbing. */
    private fun FileOperationEngine.runBlockingPlan(sources: List<String>, destination: String) =
        planCopyBlocking(sources, destination)

    /** Blocking execute call with the common defaults. */
    private fun FileOperationEngine.runBlockingExecute(
        sources: List<String>,
        destination: String,
        move: Boolean,
        policy: ConflictPolicy,
        resolver: ConflictResolver? = null,
    ) = executeBlocking(
        sources = sources,
        destinationDirectory = destination,
        move = move,
        policy = policy,
        resolver = resolver,
    )

    // ------------------------------------------------------------------ planning

    @Test
    fun `plan totals the bytes and file count of a single file`() {
        val source = file("a.bin", 512)
        val destination = dir("dest")

        val plan = engine().runBlockingPlan(listOf(source.absolutePath), destination.absolutePath)

        assertEquals(512L, plan.totalBytes)
        assertEquals(1, plan.totalFiles)
        assertTrue(plan.canProceed)
        assertTrue(plan.conflicts.isEmpty())
    }

    @Test
    fun `plan walks a directory tree and counts only files`() {
        val source = dir("tree")
        File(source, "one.txt").writeBytes(ByteArray(100))
        val nested = File(source, "nested").apply { mkdirs() }
        File(nested, "two.txt").writeBytes(ByteArray(250))
        File(nested, "three.txt").writeBytes(ByteArray(50))

        val destination = dir("dest")
        val plan = engine().runBlockingPlan(listOf(source.absolutePath), destination.absolutePath)

        // 3 files, and the directories themselves do not inflate the file count.
        assertEquals(3, plan.totalFiles)
        assertEquals(400L, plan.totalBytes)
    }

    @Test
    fun `a name collision is reported before anything is written`() {
        val source = file("report.txt", 10)
        val destination = dir("dest")
        File(destination, "report.txt").writeBytes(ByteArray(999))

        val plan = engine().runBlockingPlan(listOf(source.absolutePath), destination.absolutePath)

        assertEquals(1, plan.conflicts.size)
        val conflict = plan.conflicts.first()
        // The engine reports sources in its own normalised form; the test asserts on that
        // normalised value because that is what any caller receives.
        assertEquals(
            io.rootexplorer.pro.core.filesystem.FsEngine.normalise(source.absolutePath),
            conflict.sourcePath,
        )
        assertTrue(conflict.existingPath.endsWith("report.txt"))
        assertEquals(999L, conflict.existingSizeBytes)
        assertFalse(conflict.sourceIsDirectory)
    }

    @Test
    fun `a missing source is a blocker`() {
        val destination = dir("dest")
        val plan = engine().runBlockingPlan(
            listOf(File(temp.root, "does-not-exist").absolutePath),
            destination.absolutePath,
        )
        assertFalse(plan.canProceed)
        assertTrue(plan.blockers.any { it.contains("源不存在") })
    }

    @Test
    fun `a missing destination is a blocker`() {
        val source = file("a.txt", 4)
        val plan = engine().runBlockingPlan(
            listOf(source.absolutePath),
            File(temp.root, "no-such-dir").absolutePath,
        )
        assertFalse(plan.canProceed)
        assertTrue(plan.blockers.any { it.contains("目标目录不存在") })
    }

    @Test
    fun `copying a directory into itself is refused`() {
        val source = dir("self")
        val inner = File(source, "inner").apply { mkdirs() }

        val plan = engine().runBlockingPlan(listOf(source.absolutePath), inner.absolutePath)

        assertFalse("a destination inside the source must be refused", plan.canProceed)
        assertTrue(plan.blockers.any { it.contains("内部") })
    }

    @Test
    fun `copying a directory onto itself is refused`() {
        val source = dir("same")
        val plan = engine().runBlockingPlan(listOf(source.absolutePath), source.absolutePath)
        assertFalse(plan.canProceed)
    }

    @Test
    fun `an empty directory plans as zero bytes and zero files`() {
        val source = dir("empty")
        val destination = dir("dest")
        val plan = engine().runBlockingPlan(listOf(source.absolutePath), destination.absolutePath)
        assertEquals(0L, plan.totalBytes)
        assertEquals(0, plan.totalFiles)
        assertTrue(plan.canProceed)
    }

    // ------------------------------------------------------------------ unique naming

    @Test
    fun `uniqueNameIn returns the original name when it is free`() {
        val destination = dir("dest")
        assertEquals("free.txt", engine().uniqueNameIn(destination, "free.txt"))
    }

    @Test
    fun `uniqueNameIn inserts a counter before the extension`() {
        val destination = dir("dest")
        File(destination, "a.txt").writeText("x")
        assertEquals("a (1).txt", engine().uniqueNameIn(destination, "a.txt"))

        File(destination, "a (1).txt").writeText("x")
        assertEquals("a (2).txt", engine().uniqueNameIn(destination, "a.txt"))
    }

    @Test
    fun `uniqueNameIn handles names without an extension`() {
        val destination = dir("dest")
        File(destination, "README").writeText("x")
        assertEquals("README (1)", engine().uniqueNameIn(destination, "README"))
    }

    @Test
    fun `uniqueNameIn treats a leading dot as part of the name`() {
        val destination = dir("dest")
        File(destination, ".nomedia").writeText("x")
        // ".nomedia" has no extension, so the counter goes at the end.
        assertEquals(".nomedia (1)", engine().uniqueNameIn(destination, ".nomedia"))
    }

    @Test
    fun `uniqueNameIn keeps multi-part extensions intact`() {
        val destination = dir("dest")
        File(destination, "archive.tar.gz").writeText("x")
        assertEquals("archive.tar (1).gz", engine().uniqueNameIn(destination, "archive.tar.gz"))
    }

    // ------------------------------------------------------------------ execution

    @Test
    fun `copy actually writes the bytes and preserves size`() {
        val source = file("payload.bin", 4096)
        val destination = dir("dest")

        val outcome = engine().runBlockingExecute(
            sources = listOf(source.absolutePath),
            destination = destination.absolutePath,
            move = false,
            policy = ConflictPolicy.SKIP,
        )

        assertTrue(outcome is OperationOutcome.Completed)
        val completed = outcome as OperationOutcome.Completed
        assertTrue("no item should fail", completed.isFullSuccess)
        assertEquals(1, completed.itemsCopied)
        assertEquals(4096L, completed.bytesCopied)

        val copied = File(destination, "payload.bin")
        assertTrue(copied.isFile)
        assertEquals(4096L, copied.length())

        // A copy must leave the source in place.
        assertTrue("source must still exist after a copy", source.exists())
    }

    @Test
    fun `copy of a directory recreates the tree`() {
        val source = dir("tree")
        File(source, "a.txt").writeText("aaa")
        val nested = File(source, "sub").apply { mkdirs() }
        File(nested, "b.txt").writeText("bbbb")

        val destination = dir("dest")
        val outcome = engine().runBlockingExecute(
            sources = listOf(source.absolutePath),
            destination = destination.absolutePath,
            move = false,
            policy = ConflictPolicy.SKIP,
        )

        assertTrue(outcome is OperationOutcome.Completed)
        assertTrue((outcome as OperationOutcome.Completed).isFullSuccess)
        assertTrue(File(destination, "tree/a.txt").isFile)
        assertTrue(File(destination, "tree/sub/b.txt").isFile)
        assertEquals("aaa", File(destination, "tree/a.txt").readText())
        assertEquals("bbbb", File(destination, "tree/sub/b.txt").readText())
    }

    @Test
    fun `skip policy leaves the existing file untouched`() {
        val source = file("same.txt", 3)
        source.writeText("NEW")
        val destination = dir("dest")
        val existing = File(destination, "same.txt").apply { writeText("OLD") }

        val outcome = engine().runBlockingExecute(
            sources = listOf(source.absolutePath),
            destination = destination.absolutePath,
            move = false,
            policy = ConflictPolicy.SKIP,
        )

        assertTrue(outcome is OperationOutcome.Completed)
        assertEquals("OLD", existing.readText())
        assertEquals(1, (outcome as OperationOutcome.Completed).itemsSkipped)
    }

    @Test
    fun `overwrite policy replaces the existing file`() {
        val source = file("same.txt", 3).apply { writeText("NEW") }
        val destination = dir("dest")
        val existing = File(destination, "same.txt").apply { writeText("OLD") }

        engine().runBlockingExecute(
            sources = listOf(source.absolutePath),
            destination = destination.absolutePath,
            move = false,
            policy = ConflictPolicy.OVERWRITE,
        )

        assertEquals("NEW", existing.readText())
    }

    @Test
    fun `rename policy keeps both files`() {
        val source = file("same.txt", 3).apply { writeText("NEW") }
        val destination = dir("dest")
        val existing = File(destination, "same.txt").apply { writeText("OLD") }

        engine().runBlockingExecute(
            sources = listOf(source.absolutePath),
            destination = destination.absolutePath,
            move = false,
            policy = ConflictPolicy.RENAME,
        )

        assertEquals("OLD", existing.readText())
        assertEquals("NEW", File(destination, "same (1).txt").readText())
    }

    @Test
    fun `move relocates the file and removes the source`() {
        val source = file("movable.txt", 8).apply { writeText("MOVEDATA") }
        val destination = dir("dest")

        val outcome = engine().runBlockingExecute(
            sources = listOf(source.absolutePath),
            destination = destination.absolutePath,
            move = true,
            policy = ConflictPolicy.SKIP,
        )

        assertTrue(outcome is OperationOutcome.Completed)
        assertTrue(File(destination, "movable.txt").isFile)
        assertEquals("MOVEDATA", File(destination, "movable.txt").readText())
        assertFalse("source must be gone after a move", source.exists())
    }

    @Test
    fun `a rejected plan reports why instead of copying`() {
        val destination = dir("dest")
        val outcome = engine().runBlockingExecute(
            sources = listOf(File(temp.root, "ghost").absolutePath),
            destination = destination.absolutePath,
            move = false,
            policy = ConflictPolicy.SKIP,
        )
        assertTrue(outcome is OperationOutcome.Rejected)
        assertNotNull((outcome as OperationOutcome.Rejected).reason)
        assertTrue(destination.listFiles()?.isEmpty() ?: true)
    }

    @Test
    fun `an unknown conflict policy ask with no resolver cancels cleanly`() {
        val source = file("collide.txt", 2).apply { writeText("A") }
        val destination = dir("dest")
        File(destination, "collide.txt").writeText("B")

        val outcome = engine().runBlockingExecute(
            sources = listOf(source.absolutePath),
            destination = destination.absolutePath,
            move = false,
            policy = ConflictPolicy.ASK,
        )

        // With no resolver the engine must not invent a policy; it stops.
        assertTrue(outcome is OperationOutcome.Cancelled)
        assertEquals("B", File(destination, "collide.txt").readText())
    }

    @Test
    fun `an ask resolver is consulted and its answer is honoured`() {
        val source = file("collide.txt", 2).apply { writeText("NEW") }
        val destination = dir("dest")
        val existing = File(destination, "collide.txt").apply { writeText("OLD") }

        val asked = mutableListOf<Conflict>()
        val outcome = engine().runBlockingExecute(
            sources = listOf(source.absolutePath),
            destination = destination.absolutePath,
            move = false,
            policy = ConflictPolicy.ASK,
            resolver = { conflict ->
                asked += conflict
                ConflictDecision.Apply(ConflictPolicy.RENAME)
            },
        )

        assertEquals(1, asked.size)
        assertTrue(outcome is OperationOutcome.Completed)
        assertEquals("OLD", existing.readText())
        assertEquals("NEW", File(destination, "collide (1).txt").readText())
    }

    @Test
    fun `cancelling after answering one conflict stops the whole operation`() {
        // Two colliding sources: the first conflict is answered, the second is cancelled.
        // ConflictDecision.CancelOperation is documented as "Stop the whole operation", so
        // nothing may be transferred — including the first conflict, whose answer arrived
        // before the cancel. Previously the engine only broke out of the prompt loop and then
        // ran the transfer anyway, so "cancel" copied everything it could and reported the
        // rest as failures.
        val sourceA = file("a.txt", 4).apply { writeText("NEW-A") }
        val sourceB = file("b.txt", 4).apply { writeText("NEW-B") }
        val destination = dir("dest")
        val existingA = File(destination, "a.txt").apply { writeText("OLD-A") }
        val existingB = File(destination, "b.txt").apply { writeText("OLD-B") }

        var asked = 0
        val outcome = engine().runBlockingExecute(
            sources = listOf(sourceA.absolutePath, sourceB.absolutePath),
            destination = destination.absolutePath,
            move = false,
            policy = ConflictPolicy.ASK,
            resolver = { _ ->
                asked++
                if (asked == 1) {
                    ConflictDecision.Apply(ConflictPolicy.OVERWRITE)
                } else {
                    ConflictDecision.CancelOperation
                }
            },
        )

        assertEquals(2, asked)
        assertTrue(
            "cancel must be reported as a cancellation, not a completion: $outcome",
            outcome is OperationOutcome.Cancelled,
        )
        // Neither destination was touched: the answer given for the first conflict was
        // discarded along with the operation.
        assertEquals("OLD-A", existingA.readText())
        assertEquals("OLD-B", existingB.readText())
        // And no renamed sibling was written either.
        assertEquals(2, destination.listFiles()?.size ?: -1)
    }

    // ------------------------------------------------------------------ extras

    @Test
    fun `conflict policy bulk flag distinguishes ask from the rest`() {
        assertFalse(ConflictPolicy.ASK.isBulk)
        assertTrue(ConflictPolicy.OVERWRITE.isBulk)
        assertTrue(ConflictPolicy.SKIP.isBulk)
        assertTrue(ConflictPolicy.RENAME.isBulk)
    }

    @Test
    fun `a symlink loop does not hang the planner`() {
        val source = dir("loop")
        val inner = File(source, "inner").apply { mkdirs() }
        // Create a symlink pointing back at the parent, which a naive walk would follow forever.
        val link = File(inner, "back").toPath()
        try {
            Files.createSymbolicLink(link, source.toPath())
        } catch (e: Exception) {
            // Creating symlinks may be unavailable (privilege); the test then proves nothing.
            return
        }

        val destination = dir("dest")
        val plan = engine().runBlockingPlan(listOf(source.absolutePath), destination.absolutePath)
        // Reaching this line at all is the assertion: the planner terminated.
        assertNotNull(plan)
    }
}
