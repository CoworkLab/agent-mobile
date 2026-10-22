package com.phoneagent.tools

/** Global navigation actions the platform performs directly. */
enum class GlobalAction {
    BACK,
    HOME,
    RECENTS,
    NOTIFICATIONS,
    QUICK_SETTINGS,
    LOCK_SCREEN,
    ;

    /**
     * `lock_screen` is excluded from the batch allow-list: locking mid-batch would strand the
     * remaining actions on the lock screen, which the agent cannot unlock.
     */
    val isSafeForBatch: Boolean get() = this != LOCK_SCREEN
}

enum class ScrollDirection {
    UP,
    DOWN,
    LEFT,
    RIGHT,
}
