// SPDX-License-Identifier: AGPL-3.0-or-later
package org.mobileslicer.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MeshImporterTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun import(bytes: ByteArray, name: String): MeshInfo =
        MeshImporter.import(ByteArrayInputStream(bytes), name, tmp.newFile("out.stl"))

    /** Reads a binary STL back: triangle count from the header, and all vertices. */
    private fun readBack(f: File): Pair<Long, List<Float>> {
        val buf = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        val count = buf.getInt(80).toLong() and 0xffffffffL
        assertEquals("file size matches the count", 84 + 50 * count, f.length())
        val verts = (0 until count.toInt()).flatMap { t -> (0 until 9).map { buf.getFloat(84 + 50 * t + 12 + 4 * it) } }
        return count to verts
    }

    private val cubeObj = """
        # unit-ish cube, 8 vertices with colours, 6 quad faces
        o Cube
        v -10 -10 0 0.5 0.5 0.5
        v 10 -10 0 0.5 0.5 0.5
        v 10 10 0 0.5 0.5 0.5
        v -10 10 0 0.5 0.5 0.5
        v -1e1 -10 2.0e1 1 0 0
        v 10 -10 20 1 0 0
        v 10 10 20 1 0 0
        v -10 10 20 1 0 0
        vn 0 0 1
        vt 0 0
        f 1 4 3 2
        f 5/1 6/1 7/1 8/1
        f 1//1 2//1 6//1 5//1
        f 2/1/1 3/1/1 7/1/1 6/1/1
        f -6 -5 -1 -2
        f -8 -4 -1 -5
    """.trimIndent()

    @Test fun objWithColoursExponentsQuadsAndNegativeIndices() {
        val info = import(cubeObj.toByteArray(), "cube.obj")
        assertEquals("OBJ", info.sourceFormat)
        assertEquals(12, info.triangles) // 6 quads -> 12 triangles
        assertEquals(0, info.skippedTriangles)
        assertEquals(Bounds(-10f, -10f, 0f, 10f, 10f, 20f), info.bounds)
        assertEquals(12L, readBack(info.stl).first)
    }

    @Test fun objWithCrlfLineEndings() {
        val info = import(cubeObj.replace("\n", "\r\n").toByteArray(), "CUBE.OBJ")
        assertEquals(12, info.triangles)
    }

    @Test fun objBadIndicesAreSkipped() {
        val obj = "v 0 0 0\nv 1 0 0\nv 0 1 0\nf 1 2 3\nf 1 2 9\nf 0 1 2\n"
        val info = import(obj.toByteArray(), "x.obj")
        assertEquals(1, info.triangles)
        assertEquals(2, info.skippedTriangles)
    }

    @Test fun asciiStlTestCube() {
        val cube = File("../../engine/test/cube20.stl").readBytes()
        val info = import(cube, "cube20.stl")
        assertEquals("STL (ASCII)", info.sourceFormat)
        assertEquals(12, info.triangles)
        assertEquals(Bounds(-10f, -10f, 0f, 10f, 10f, 20f), info.bounds)
        // Same vertices, in the same order, as the source file.
        val expected = Regex("""vertex\s+(\S+)\s+(\S+)\s+(\S+)""").findAll(String(cube))
            .flatMap { m -> m.groupValues.drop(1).map { it.toFloat() } }.toList()
        assertEquals(expected, readBack(info.stl).second)
    }

    /** Binary STL whose 80-byte header starts with "solid", which trips naive detectors. */
    @Test fun binaryStlWithSolidHeader() {
        val tris = 3
        val buf = ByteBuffer.allocate(84 + 50 * tris).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("solid exported by some tool".toByteArray()); buf.position(80); buf.putInt(tris)
        for (t in 0 until tris) {
            buf.putFloat(0f).putFloat(0f).putFloat(1f)
            floatArrayOf(0f, 0f, t.toFloat(), 5f, 0f, t.toFloat(), 0f, 5f, t + 0.5f).forEach { buf.putFloat(it) }
            buf.putShort(0)
        }
        val info = import(buf.array(), "part.stl")
        assertEquals("STL (binary)", info.sourceFormat)
        assertEquals(3, info.triangles)
        assertEquals(Bounds(0f, 0f, 0f, 5f, 5f, 2.5f), info.bounds)
    }

    @Test fun nonFiniteTrianglesAreSkipped() {
        val obj = "v 0 0 0\nv 1 0 0\nv 0 1 0\nv nan 0 0\nf 1 2 3\nf 1 2 4\n"
        val info = import(obj.toByteArray(), "x.obj")
        assertEquals(1, info.triangles)
        assertEquals(1, info.skippedTriangles)
    }

    @Test fun unsupportedOrEmptyFilesAreRejected() {
        assertThrows(MeshImportException::class.java) { import("hello".toByteArray(), "notes.txt") }
        val out = File(tmp.root, "out.stl")
        assertThrows(MeshImportException::class.java) {
            MeshImporter.import(ByteArrayInputStream("v 0 0 0\n".toByteArray()), "empty.obj", out)
        }
        assertFalse("partial output is removed", out.exists())
    }

    /** Writes the OBJ cube's conversion for a desktop slice check (see engine/README.md). */
    @Test fun writeSampleForDesktopSlice() {
        val out = File("build/test-output/cube-from-obj.stl").apply { parentFile!!.mkdirs() }
        MeshImporter.import(ByteArrayInputStream(cubeObj.toByteArray()), "cube.obj", out)
    }
}

/** Timing check on a large file; runs only when MOBILESLICER_LARGE_MESH points at one. */
class MeshImporterLargeFileTest {
    @Test fun importLargeFile() {
        val path = System.getenv("MOBILESLICER_LARGE_MESH") ?: return
        val src = File(path)
        val out = File("build/test-output/large.stl").apply { parentFile!!.mkdirs() }
        val start = System.nanoTime()
        val info = src.inputStream().use { MeshImporter.import(it, src.name, out) }
        val seconds = (System.nanoTime() - start) / 1e9
        println("LARGE MESH: ${src.length() / 1_000_000} MB ${info.sourceFormat}, ${info.triangles} triangles, " +
            "${info.skippedTriangles} skipped, ${"%.1f".format(seconds)} s (including ${info.samples.size / 3} sample points), bounds ${info.bounds}")
    }
}
