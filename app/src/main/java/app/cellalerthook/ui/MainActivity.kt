package app.cellalerthook.ui

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import app.cellalerthook.AppContainer
import app.cellalerthook.CellAlertHookApplication
import app.cellalerthook.R
import app.cellalerthook.ui.alerts.AlertsScreen
import app.cellalerthook.ui.dashboard.DashboardScreen
import app.cellalerthook.ui.diagnostics.DiagnosticsScreen
import app.cellalerthook.ui.lock.LockScreen
import app.cellalerthook.ui.settings.SettingsScreen
import app.cellalerthook.ui.theme.CellAlertHookTheme

class MainActivity : ComponentActivity() {
    private val container get() = (application as CellAlertHookApplication).container

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Keeps the screens out of screenshots, screen sharing and the recent apps preview.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContent {
            CellAlertHookTheme {
                val unlocked by container.appLock.unlocked.collectAsState()
                if (unlocked) {
                    CellAlertHookApp(container)
                } else {
                    LockScreen(container.appLock, container::unsealLinks.takeIf { container.hasSealedLinks })
                }
            }
        }
    }

    /** Leaving the app in any way locks it. A rotation or language change is not leaving. */
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) container.appLock.lock()
    }
}

private enum class Section(@StringRes val label: Int, val icon: ImageVector) {
    DASHBOARD(R.string.nav_dashboard, Icons.Filled.Home),
    ALERTS(R.string.nav_alerts, Icons.Filled.Notifications),
    SETTINGS(R.string.nav_settings, Icons.Filled.Settings),
    DIAGNOSTICS(R.string.nav_diagnostics, Icons.Filled.Build),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CellAlertHookApp(container: AppContainer) {
    var section by rememberSaveable { mutableStateOf(Section.DASHBOARD) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(section.label), style = MaterialTheme.typography.titleLarge) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                Section.entries.forEach { item ->
                    NavigationBarItem(
                        selected = item == section,
                        onClick = { section = item },
                        icon = { Icon(item.icon, contentDescription = null) },
                        label = { Text(stringResource(item.label), maxLines = 1) },
                    )
                }
            }
        },
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            when (section) {
                Section.DASHBOARD -> DashboardScreen(container)
                Section.ALERTS -> AlertsScreen(container)
                Section.SETTINGS -> SettingsScreen(container)
                Section.DIAGNOSTICS -> DiagnosticsScreen(container)
            }
        }
    }
}
