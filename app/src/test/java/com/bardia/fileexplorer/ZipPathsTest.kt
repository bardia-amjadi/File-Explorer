package com.bardia.fileexplorer

import com.bardia.fileexplorer.archive.ZipEntryInfo
import com.bardia.fileexplorer.archive.ZipPaths
import com.bardia.fileexplorer.archive.ZipTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZipPathsTest {
    @Test fun acceptsNormalPaths() {
        assertEquals(listOf("a", "b", "c.txt"), ZipPaths.sanitize("a/b/c.txt"))
        assertEquals(listOf("dir"), ZipPaths.sanitize("dir/"))
        assertEquals(listOf("a", "b"), ZipPaths.sanitize("./a//b"))
    }

    @Test fun rejectsParentTraversal() {
        assertNull(ZipPaths.sanitize("../evil.txt"))
        assertNull(ZipPaths.sanitize("a/../../evil.txt"))
        assertNull(ZipPaths.sanitize("a/.."))
        assertNull(ZipPaths.sanitize("..\\evil.txt"))
        assertNull(ZipPaths.sanitize("a\\..\\..\\evil"))
    }

    @Test fun rejectsAbsoluteAndDrivePaths() {
        assertNull(ZipPaths.sanitize("/etc/passwd"))
        assertNull(ZipPaths.sanitize("C:/Windows/x"))
        assertNull(ZipPaths.sanitize("c:\\x"))
    }

    @Test fun rejectsEmptyAndNul() {
        assertNull(ZipPaths.sanitize(""))
        assertNull(ZipPaths.sanitize("./"))
        assertNull(ZipPaths.sanitize("a\u0000b"))
    }

    @Test fun backslashesAreSeparators() {
        assertEquals(listOf("a", "b.txt"), ZipPaths.sanitize("a\\b.txt"))
    }

    @Test fun selectionIncludesFolderContents() {
        val sel = setOf("docs", "readme.txt")
        assertTrue(ZipPaths.isSelected("docs", sel))
        assertTrue(ZipPaths.isSelected("docs/a/b.txt", sel))
        assertTrue(ZipPaths.isSelected("readme.txt", sel))
        assertFalse(ZipPaths.isSelected("docs2/a.txt", sel))
        assertFalse(ZipPaths.isSelected("other.txt", sel))
    }

    private fun e(path: String, dir: Boolean = false, size: Long = 10, unsafe: Boolean = false) =
        ZipEntryInfo(path, dir, size, size, 0L, unsafe)

    @Test fun treeListsDirectChildrenFoldersFirst() {
        val entries = listOf(e("b.txt"), e("a/x.txt"), e("a/sub/y.txt"), e("a/sub", dir = true), e("c", dir = true), e("../bad", unsafe = true))
        val root = ZipTree.children(entries, "")
        assertEquals(listOf("a", "c", "b.txt"), root.map { it.name })
        assertTrue(root[0].isDirectory)
        assertEquals(2, root[0].itemCount)
        val a = ZipTree.children(entries, "a")
        assertEquals(listOf("sub", "x.txt"), a.map { it.name })
        assertEquals("a/sub", a[0].path)
    }

    @Test fun treeSkipsUnsafeEntries() {
        val root = ZipTree.children(listOf(e("evil", unsafe = true), e("ok.txt")), "")
        assertEquals(listOf("ok.txt"), root.map { it.name })
    }
}
