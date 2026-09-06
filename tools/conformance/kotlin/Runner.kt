// Reflection-based runner for the @Test methods in the Android unit tests.
// Used only by tools/conformance/run-kotlin.sh (plain kotlinc, no Gradle).
package conformance

import java.lang.reflect.InvocationTargetException

fun main(args: Array<String>) {
    var passed = 0; var failed = 0
    for (className in args) {
        val cls = Class.forName(className)
        println(cls.simpleName)
        val instance = cls.getDeclaredConstructor().newInstance()
        for (m in cls.declaredMethods.filter { it.isAnnotationPresent(org.junit.Test::class.java) }.sortedBy { it.name }) {
            try {
                m.isAccessible = true
                m.invoke(instance)
                println("  ✓ ${m.name}"); passed++
            } catch (e: InvocationTargetException) {
                println("  ✗ ${m.name}: ${e.cause}"); failed++
                e.cause?.stackTrace?.take(6)?.forEach { println("      at $it") }
            }
        }
    }
    println("\n$passed passed, $failed failed")
    if (failed > 0) System.exit(1)
}
