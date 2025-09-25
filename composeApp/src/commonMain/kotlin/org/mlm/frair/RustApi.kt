package org.mlm.frair

// Common facade so shared code can call into Rust without platform checks
expect object RustApi {
    fun greet(name: String): String
}