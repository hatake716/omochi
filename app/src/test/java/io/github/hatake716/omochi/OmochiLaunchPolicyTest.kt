package io.github.hatake716.omochi

import org.junit.Assert.assertEquals
import org.junit.Test

class OmochiLaunchPolicyTest {
    @Test
    fun completedSetupLaunchesWorkbench() {
        assertEquals(
            OmochiLaunchDestination.WORKBENCH,
            OmochiLaunchPolicy.destination(
                runtimeInstalled = true,
                japaneseClaudeInstalled = true,
            ),
        )
    }

    @Test
    fun incompleteOrMigratingSetupLaunchesHome() {
        listOf(
            false to false,
            false to true,
            true to false,
        ).forEach { (runtimeInstalled, japaneseClaudeInstalled) ->
            assertEquals(
                OmochiLaunchDestination.HOME,
                OmochiLaunchPolicy.destination(runtimeInstalled, japaneseClaudeInstalled),
            )
        }
    }
}
