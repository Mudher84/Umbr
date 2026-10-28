plugins {
    id("com.android.application") version "9.4.1" apply false
    // AGP's built-in Kotlin uses this compiler version (it must read the 2.4 stdlib our dependencies bring)
    id("org.jetbrains.kotlin.android") version "2.4.20" apply false
}
