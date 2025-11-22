// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("com.android.application") version "8.13.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
    // NEED TO ADD THIS LINE:
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.22" apply false
}