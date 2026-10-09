package dev.denza.apps

import dev.denza.apps.ui.dashboard.DashboardActions
import dev.denza.apps.ui.dashboard.DenzaActions
import java.lang.reflect.Method
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The screen is handed the same actions on every recomposition.
 *
 * Compose skips a panel whose parameters are the objects it was last given. The root used to keep
 * that true by remembering its actions under thirty-one keys, one per callback, and a key left out
 * would have handed a panel a stale callback without a word from the compiler. Now the activity
 * builds the actions once and the root wraps them once, so what is left to hold is that each action
 * is an object that stays: a getter that built a new lambda on every read would give every panel a
 * changed parameter on every frame, and nothing would say so but the frame time.
 *
 * Reads the members only - none is called, so nothing reaches the repository.
 */
class AppActionsTest {

    private val members: List<Method> = DenzaActions::class.java.methods
        .filter { it.parameterCount == 0 && it.name.startsWith("getOn") }

    @Test
    fun theActivityAnswersEveryActionWithOneObjectForTheProcess() {
        assertTrue("DenzaActions has no members to read", members.size > 30)
        members.forEach { member ->
            val first = member.invoke(AppActions)
            assertNotNull("${member.name} is not answered", first)
            assertSame("${member.name} is a new lambda on every read", first, member.invoke(AppActions))
        }
    }

    @Test
    fun theDashboardHandsOnTheActivitysOwnActions() {
        val dashboard = DashboardActions(
            app = AppActions,
            onChooseApps = {},
            onChooseNavigationApp = {},
            onChooseFseApp = {},
            onOpenClusterPicker = {},
            onOpenService = {},
            onOpenSettings = {},
        )
        members.forEach { member ->
            assertSame(
                "${member.name} reaches a tile as something other than the activity's",
                member.invoke(AppActions),
                member.invoke(dashboard),
            )
        }
    }
}
