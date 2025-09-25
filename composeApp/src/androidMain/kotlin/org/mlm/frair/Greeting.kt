package org.mlm.frair

class Greeting {
    fun greet(): String {
        val platform = getPlatform().name
        return RustApi.greet(platform)
    }
}