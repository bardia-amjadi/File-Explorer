package com.bardia.fileexplorer

import com.bardia.fileexplorer.core.FileEntry
import com.bardia.fileexplorer.core.FileSorter
import com.bardia.fileexplorer.core.NameUtils
import com.bardia.fileexplorer.core.SortField
import com.bardia.fileexplorer.operations.UndoManager
import com.bardia.fileexplorer.search.SearchCriteria
import com.bardia.fileexplorer.search.SearchMatcher
import com.bardia.fileexplorer.tools.CompareResult
import com.bardia.fileexplorer.tools.Hashing
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun file(name: String, size: Long = 100, modified: Long = 1000, mime: String? = null) =
    FileEntry(uri = "content://t/$name", name = name, isDirectory = false, size = size, modified = modified, mime = mime, parentUri = "content://t")

private fun dir(name: String) = FileEntry(uri = "content://t/$name", name = name, isDirectory = true, parentUri = "content://t")

class SortingTest {
    @Test fun naturalOrderHandlesNumbers() {
        val sorted = listOf("file10", "file2", "File1", "file02").sortedWith { a, b -> FileSorter.naturalCompare(a, b) }
        assertEquals(listOf("File1", "file2", "file02", "file10"), sorted)
    }

    @Test fun foldersFirstAndDirection() {
        val list = listOf(file("b.txt"), dir("zeta"), file("a.txt"), dir("alpha"))
        assertEquals(listOf("alpha", "zeta", "a.txt", "b.txt"), FileSorter.sort(list, SortField.NAME, true).map { it.name })
        assertEquals(listOf("zeta", "alpha", "b.txt", "a.txt"), FileSorter.sort(list, SortField.NAME, false).map { it.name })
        assertEquals(listOf("a.txt", "alpha", "b.txt", "zeta"), FileSorter.sort(list, SortField.NAME, true, foldersFirst = false).map { it.name })
    }

    @Test fun sortBySizeAndDate() {
        val list = listOf(file("a", size = 30, modified = 3), file("b", size = 10, modified = 2), file("c", size = 20, modified = 1))
        assertEquals(listOf("b", "c", "a"), FileSorter.sort(list, SortField.SIZE, true).map { it.name })
        assertEquals(listOf("c", "b", "a"), FileSorter.sort(list, SortField.MODIFIED, true).map { it.name })
    }

    @Test fun hiddenFilter() {
        val list = listOf(file(".secret"), file("open.txt"))
        assertEquals(listOf("open.txt"), FileSorter.visible(list, false).map { it.name })
        assertEquals(2, FileSorter.visible(list, true).size)
    }
}

class SearchMatcherTest {
    @Test fun textTokensMatchInAnyOrder() {
        val e = file("Holiday Photo 2024.jpg")
        assertTrue(SearchMatcher.matches(e, SearchCriteria(text = "photo holiday")))
        assertFalse(SearchMatcher.matches(e, SearchCriteria(text = "holiday video")))
    }

    @Test fun extensionsAndFolders() {
        val c = SearchCriteria(extensions = SearchMatcher.parseExtensions("jpg, .PNG"))
        assertEquals(setOf("jpg", "png"), c.extensions)
        assertTrue(SearchMatcher.matches(file("a.PNG"), c))
        assertFalse(SearchMatcher.matches(file("a.txt"), c))
        assertFalse("folders are excluded when a file filter is active", SearchMatcher.matches(dir("pics"), c))
    }

    @Test fun sizeAndDateRanges() {
        val e = file("a.bin", size = 500, modified = 5000)
        assertTrue(SearchMatcher.matches(e, SearchCriteria(minSize = 500, maxSize = 500)))
        assertFalse(SearchMatcher.matches(e, SearchCriteria(minSize = 501)))
        assertTrue(SearchMatcher.matches(e, SearchCriteria(modifiedAfter = 4000, modifiedBefore = 6000)))
        assertFalse(SearchMatcher.matches(e, SearchCriteria(modifiedAfter = 6000)))
        assertFalse("unknown size never matches a size filter", SearchMatcher.matches(file("x", size = -1), SearchCriteria(minSize = 0)))
    }

    @Test fun hiddenFilesOnlyWhenRequested() {
        assertFalse(SearchMatcher.matches(file(".env"), SearchCriteria(text = "env")))
        assertTrue(SearchMatcher.matches(file(".env"), SearchCriteria(text = "env", includeHidden = true)))
    }

    @Test fun mimeWildcard() {
        assertTrue(SearchMatcher.matches(file("a.bin", mime = "image/png"), SearchCriteria(mime = "image/*")))
        assertFalse(SearchMatcher.matches(file("a.bin", mime = "text/plain"), SearchCriteria(mime = "image/*")))
    }
}

class NameUtilsTest {
    @Test fun uniqueNameKeepsExtension() {
        assertEquals("a.txt", NameUtils.uniqueName("a.txt", emptySet()))
        assertEquals("a (1).txt", NameUtils.uniqueName("a.txt", setOf("a.txt")))
        assertEquals("a (2).txt", NameUtils.uniqueName("a.txt", setOf("a.txt", "A (1).TXT")))
    }

    @Test fun uniqueNameForFoldersIgnoresDots() {
        assertEquals("v1.2 (1)", NameUtils.uniqueName("v1.2", setOf("v1.2"), hasExtension = false))
    }

    @Test fun dotfilesAreNotSplit() {
        assertEquals(".bashrc (1)", NameUtils.uniqueName(".bashrc", setOf(".bashrc")))
    }

    @Test fun validation() {
        assertNotNull(NameUtils.validate(""))
        assertNotNull(NameUtils.validate("a/b"))
        assertNotNull(NameUtils.validate(".."))
        assertNotNull(NameUtils.validate("x".repeat(256)))
        assertNull(NameUtils.validate("report (final).docx"))
    }
}

class HashingTest {
    @Test fun sha256KnownVectors() = runBlocking {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Hashing.sha256("abc".byteInputStream()))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Hashing.sha256("".byteInputStream()))
    }

    @Test fun sha256CountsBytes() = runBlocking {
        var total = 0L
        Hashing.sha256(ByteArray(300_000).inputStream()) { total += it }
        assertEquals(300_000L, total)
    }

    @Test fun hex() {
        assertEquals("00ff10", Hashing.toHex(byteArrayOf(0, -1, 16)))
    }
}

class CompareVerdictTest {
    private fun r(sameSize: Boolean, sameMod: Boolean, verified: Boolean, identical: Boolean?) =
        CompareResult(true, sameSize, sameMod, verified, identical, null, null)

    @Test fun verdictsAreHonestAboutVerification() {
        assertTrue(r(true, true, true, true).verdict.contains("Identical content"))
        assertTrue(r(true, true, true, false).verdict.contains("Different content"))
        assertTrue(r(false, false, false, false).verdict.contains("metadata only"))
        val probable = r(true, true, false, null).verdict
        assertTrue(probable.contains("not verified"))
    }
}

class UndoManagerTest {
    @Test fun runsOnceAndOnlyOnce() {
        val undo = UndoManager()
        var calls = 0
        val id = undo.register("x") { calls++ }
        assertTrue(undo.run(id))
        assertEquals(1, calls)
        assertFalse(undo.run(id))
        assertEquals(1, calls)
    }

    @Test fun unknownIdFails() {
        assertFalse(UndoManager().run(999))
    }
}
