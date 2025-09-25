package org.mlm.frair

actual object RustApi {
    actual fun greet(name: String): String = NativeLib.greetFromRust(name)
}