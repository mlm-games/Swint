#![allow(non_snake_case)]

use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;
use std::panic;

// Keep pure logic separate so it’s reusable across platforms.
fn greet(name: &str) -> String {
    format!("Hello from Rust, {name} 👋")
}

// Static JNI binding for: org.mlm.frair.NativeLib.greetFromRust(String): String
#[no_mangle]
pub extern "system" fn Java_org_mlm_frair_NativeLib_greetFromRust(
    mut env: JNIEnv,
    _class: JClass,
    name: JString,
) -> jstring {
    // Convert JString -> owned Rust String (avoid borrowing lifetimes tied to JNIEnv)
    let input: String = env
        .get_string(&name)            // Result<JavaStr, _>
        .map(Into::into)              // Result<String, _>
        .unwrap_or_default();         // "" on error

    // Never let a panic unwind across JNI
    let result = panic::catch_unwind(|| greet(&input));

    match result {
        Ok(msg) => env.new_string(msg).unwrap().into_raw(),
        Err(_) => {
            let _ = env.throw_new("java/lang/RuntimeException", "Rust panic in greetFromRust");
            env.new_string("").unwrap().into_raw()
        }
    }
}