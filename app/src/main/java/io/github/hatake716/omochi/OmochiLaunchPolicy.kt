package io.github.hatake716.omochi

internal enum class OmochiLaunchDestination {
    HOME,
    WORKBENCH,
}

/** Chooses the first screen without coupling the decision to an Android Activity. */
internal object OmochiLaunchPolicy {
    fun destination(
        runtimeInstalled: Boolean,
        japaneseClaudeInstalled: Boolean,
    ): OmochiLaunchDestination =
        if (runtimeInstalled && japaneseClaudeInstalled) {
            OmochiLaunchDestination.WORKBENCH
        } else {
            OmochiLaunchDestination.HOME
        }
}
