package com.aggapp.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.aggapp.app.ui.theme.AggTheme
import com.lingion.sleepy.api.TimetableDependencies
import com.lingion.sleepy.ui.TimetableRoute
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var timetableDependencies: TimetableDependencies
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AggTheme {
                val navController = rememberNavController()
                AggScaffold(navController, timetableDependencies)
            }
        }
    }
}

private data class TabItem(val route: String, val label: String, val icon: @Composable () -> Unit)

@Composable
fun AggScaffold(navController: NavHostController, timetableDependencies: TimetableDependencies) {
    val tabs = listOf(
        TabItem("timetable", "课表") { Icon(Icons.Filled.Home, null) },
        TabItem("express", "快递") { Icon(Icons.Filled.LocalShipping, null) },
        TabItem("idcode", "身份码") { Icon(Icons.Filled.QrCode2, null) },
        TabItem("mine", "我的") { Icon(Icons.Filled.Person, null) },
    )
    val backStack by navController.currentBackStackEntryAsState()
    val current = backStack?.destination?.route ?: "timetable"
    val selectedTab = current

    Scaffold(
        containerColor = androidx.compose.material3.MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar {
                tabs.forEach { tab ->
                    NavigationBarItem(
                        modifier = Modifier.testTag("nav-${tab.route}"),
                        selected = selectedTab == tab.route,
                        onClick = {
                            if (current != tab.route) {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.startDestinationId) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        icon = tab.icon,
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { pad ->
        NavHost(
            navController = navController,
            startDestination = "timetable",
            modifier = Modifier.padding(pad),
            // 快速淡入淡出，替换默认约 700ms 的转场（默认转场叠加重组会明显卡顿）
            enterTransition = { fadeIn(tween(180)) },
            exitTransition = { fadeOut(tween(120)) },
            popEnterTransition = { fadeIn(tween(180)) },
            popExitTransition = { fadeOut(tween(120)) },
        ) {
            composable("timetable") { TimetableRoute(timetableDependencies) }
            composable("express") {
                Column(Modifier.fillMaxSize().testTag("express-page")) {
                    TextButton(
                        onClick = {
                            if (current != "idcode") {
                                navController.navigate("idcode") {
                                    popUpTo(navController.graph.startDestinationId) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("身份码") }
                    Box(Modifier.weight(1f)) { ExpressScreen() }
                }
            }
            composable("idcode") {
                Box(Modifier.fillMaxSize().testTag("idcode-page")) {
                    IdCodeScreen(hiltViewModel<MainViewModel>())
                }
            }
            composable("mine") { MineScreen() }
        }
    }
}
