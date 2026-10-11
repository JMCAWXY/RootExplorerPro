package io.rootexplorer.pro.core.crypto

import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Digest computation.
 *
 * The expected values are the published test vectors for "abc" and for the empty input, so a
 * passing test proves the implementation produces the same bytes every other tool does — which
 * is the only thing that makes a checksum useful.
 */
class HashEngineTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val engine = HashEngine()

    private fun fileWith(content: String): File {
        val file = temp.newFile()
        file.writeBytes(content.toByteArray(Charsets.UTF_8))
        return file
    }

    private suspend fun digestOf(content: String, algorithm: HashAlgorithm): String {
        val outcome = engine.hashFile(fileWith(content).absolutePath, algorithm)
        assertTrue("expected success, got $outcome", outcome is HashOutcome.Success)
        return (outcome as HashOutcome.Success).result.digest
    }

    // ------------------------------------------------------------------ known vectors

    @Test
    fun `md5 of abc matches the published vector`() = runTest {
        assertEquals("900150983cd24fb0d6963f7d28e17f72", digestOf("abc", HashAlgorithm.MD5))
    }

    @Test
    fun `sha1 of abc matches the published vector`() = runTest {
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", digestOf("abc", HashAlgorithm.SHA1))
    }

    @Test
    fun `sha256 of abc matches the published vector`() = runTest {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            digestOf("abc", HashAlgorithm.SHA256),
        )
    }

    @Test
    fun `sha256 of the empty file matches the published vector`() = runTest {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            digestOf("", HashAlgorithm.SHA256),
        )
    }

    @Test
    fun `md5 of the empty file matches the published vector`() = runTest {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", digestOf("", HashAlgorithm.MD5))
    }

    @Test
    fun `digest length matches the algorithm`() = runTest {
        assertEquals(HashAlgorithm.MD5.hexLength, digestOf("abc", HashAlgorithm.MD5).length)
        assertEquals(HashAlgorithm.SHA1.hexLength, digestOf("abc", HashAlgorithm.SHA1).length)
        assertEquals(HashAlgorithm.SHA256.hexLength, digestOf("abc", HashAlgorithm.SHA256).length)
    }

    @Test
    fun `hex output is lower case`() = runTest {
        val digest = digestOf("abc", HashAlgorithm.SHA256)
        assertEquals(digest.lowercase(), digest)
    }

    // ------------------------------------------------------------------ multi-algorithm

    @Test
    fun `hashing several algorithms at once agrees with hashing them separately`() = runTest {
        val file = fileWith("abc")
        val multi = engine.hashFileMulti(file.absolutePath)

        for (algorithm in HashAlgorithm.all) {
            val single = engine.hashFile(file.absolutePath, algorithm)
            val fromMulti = multi[algorithm]
            assertTrue(fromMulti is HashOutcome.Success)
            assertEquals(
                "multi-pass result must equal single-pass for ${algorithm.label}",
                (single as HashOutcome.Success).result.digest,
                (fromMulti as HashOutcome.Success).result.digest,
            )
        }
    }

    // ------------------------------------------------------------------ failures

    @Test
    fun `a missing file fails with a clear reason`() = runTest {
        val outcome = engine.hashFile(File(temp.root, "nope.bin").absolutePath, HashAlgorithm.SHA256)
        assertTrue(outcome is HashOutcome.Failure)
        assertTrue((outcome as HashOutcome.Failure).reason.contains("不存在"))
    }

    @Test
    fun `a directory is refused rather than hashed`() = runTest {
        val dir = temp.newFolder("d")
        val outcome = engine.hashFile(dir.absolutePath, HashAlgorithm.SHA256)
        assertTrue(outcome is HashOutcome.Failure)
        assertTrue((outcome as HashOutcome.Failure).reason.contains("目录"))
    }

    // ------------------------------------------------------------------ progress

    @Test
    fun `progress reports the real byte count`() = runTest {
        val content = "x".repeat(1000)
        val file = fileWith(content)
        val reports = mutableListOf<HashProgress>()

        val outcome = engine.hashFile(file.absolutePath, HashAlgorithm.SHA256) { reports += it }

        assertTrue(outcome is HashOutcome.Success)
        assertEquals(1000L, (outcome as HashOutcome.Success).result.bytesRead)
        // The final report always carries the complete size.
        assertEquals(1000L, reports.last().bytesRead)
        assertEquals(1000L, reports.last().totalBytes)
    }

    @Test
    fun `progress fraction is complete at the end`() = runTest {
        val file = fileWith("data")
        val reports = mutableListOf<HashProgress>()
        engine.hashFile(file.absolutePath, HashAlgorithm.MD5) { reports += it }

        val last = reports.last()
        assertEquals(1f, last.fraction)
    }

    // ------------------------------------------------------------------ verification

    @Test
    fun `a matching checksum verifies`() = runTest {
        val outcome = engine.hashFile(fileWith("abc").absolutePath, HashAlgorithm.SHA256)
        val result = (outcome as HashOutcome.Success).result

        assertEquals(HashVerification.Match, engine.verify(result.digest, result))
    }

    @Test
    fun `verification is case insensitive and ignores separators`() = runTest {
        val outcome = engine.hashFile(fileWith("abc").absolutePath, HashAlgorithm.SHA256)
        val result = (outcome as HashOutcome.Success).result

        val upper = result.digest.uppercase()
        assertEquals(HashVerification.Match, engine.verify(upper, result))
        assertEquals(HashVerification.Match, engine.verify(result.groupedDigest(), result))
        assertEquals(HashVerification.Match, engine.verify("  ${result.digest}  ", result))
    }

    @Test
    fun `a different checksum is reported as a mismatch`() = runTest {
        val outcome = engine.hashFile(fileWith("abc").absolutePath, HashAlgorithm.SHA256)
        val result = (outcome as HashOutcome.Success).result
        val wrong = "0".repeat(64)

        val verification = engine.verify(wrong, result)
        assertTrue(verification is HashVerification.Mismatch)
    }

    @Test
    fun `a wrong-length checksum is invalid input, not a mismatch`() = runTest {
        val outcome = engine.hashFile(fileWith("abc").absolutePath, HashAlgorithm.SHA256)
        val result = (outcome as HashOutcome.Success).result

        // Reporting a short paste as "mismatch" would wrongly suggest the file is corrupt.
        val verification = engine.verify("abc123", result)
        assertTrue(verification is HashVerification.InvalidInput)
        assertTrue((verification as HashVerification.InvalidInput).reason.contains("长度"))
    }

    @Test
    fun `non-hex characters are invalid input`() = runTest {
        val outcome = engine.hashFile(fileWith("abc").absolutePath, HashAlgorithm.SHA256)
        val result = (outcome as HashOutcome.Success).result

        val verification = engine.verify("z".repeat(64), result)
        assertTrue(verification is HashVerification.InvalidInput)
    }

    @Test
    fun `an empty paste is invalid input`() = runTest {
        val outcome = engine.hashFile(fileWith("abc").absolutePath, HashAlgorithm.SHA256)
        val result = (outcome as HashOutcome.Success).result
        assertTrue(engine.verify("", result) is HashVerification.InvalidInput)
    }

    @Test
    fun `a same-length checksum for a different algorithm is a mismatch not a crash`() = runTest {
        val outcome = engine.hashFile(fileWith("abc").absolutePath, HashAlgorithm.SHA256)
        val result = (outcome as HashOutcome.Success).result
        // MD5's digest is shorter, so it must be reported as invalid length for SHA-256.
        assertTrue(engine.verify("900150983cd24fb0d6963f7d28e17f72", result) is HashVerification.InvalidInput)
    }

    // ------------------------------------------------------------------ metadata

    @Test
    fun `algorithms are labelled and broken ones are marked`() {
        assertEquals("MD5", HashAlgorithm.MD5.label)
        assertEquals("SHA-1", HashAlgorithm.SHA1.label)
        assertEquals("SHA-256", HashAlgorithm.SHA256.label)

        assertTrue(HashAlgorithm.MD5.isCryptographicallyBroken)
        assertTrue(HashAlgorithm.SHA1.isCryptographicallyBroken)
        assertFalse(HashAlgorithm.SHA256.isCryptographicallyBroken)
    }

    @Test
    fun `grouped digest is the right length`() = runTest {
        val outcome = engine.hashFile(fileWith("abc").absolutePath, HashAlgorithm.SHA256)
        val result = (outcome as HashOutcome.Success).result
        val grouped = result.groupedDigest()
        // 64 hex characters in groups of 8 means 8 groups and 7 spaces.
        assertEquals(64 + 7, grouped.length)
    }
}
