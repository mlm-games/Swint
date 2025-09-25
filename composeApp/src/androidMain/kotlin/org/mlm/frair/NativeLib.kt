package org.mlm.frair

object NativeLib {
    init {
        // The library name matches [package].name in rust/Cargo.toml
        System.loadLibrary("frair_native")
    }

    @JvmStatic
    external fun greetFromRust(name: String): String
}