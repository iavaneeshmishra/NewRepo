// Minimal stand-ins for org.junit so the real unit tests can run with plain kotlinc
// when Gradle/Maven Central are unavailable. NOT used by the Android build.
@file:Suppress("PackageDirectoryMismatch", "unused")

package org.junit

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Test

class AssertionError(message: String) : Error(message)

object Assert {
    @JvmStatic fun fail(message: String = "fail"): Nothing = throw AssertionError(message)

    @JvmStatic fun assertTrue(cond: Boolean) = assertTrue("expected true", cond)
    @JvmStatic fun assertTrue(message: String, cond: Boolean) { if (!cond) fail(message) }
    @JvmStatic fun assertFalse(cond: Boolean) = assertFalse("expected false", cond)
    @JvmStatic fun assertFalse(message: String, cond: Boolean) { if (cond) fail(message) }
    @JvmStatic fun assertNull(v: Any?) { if (v != null) fail("expected null, got $v") }
    @JvmStatic fun assertNotNull(v: Any?) { if (v == null) fail("expected non-null") }

    @JvmStatic fun assertEquals(expected: Any?, actual: Any?) = assertEquals("", expected, actual)
    @JvmStatic fun assertEquals(message: String, expected: Any?, actual: Any?) {
        val eq = when {
            expected is ByteArray && actual is ByteArray -> expected.contentEquals(actual)
            expected is Number && actual is Number -> expected.toLong() == actual.toLong()
            else -> expected == actual
        }
        if (!eq) fail("$message expected <$expected> but was <$actual>".trim())
    }
    @JvmStatic fun assertArrayEquals(expected: ByteArray, actual: ByteArray) {
        if (!expected.contentEquals(actual)) fail("arrays differ")
    }
}
