package app.ripple.mesh.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.ripple.mesh.R
import app.ripple.mesh.ui.screens.ChatScreen
import app.ripple.mesh.ui.screens.HomeScreen
import app.ripple.mesh.ui.screens.SettingsScreen

class MainActivity : ComponentActivity() {
    private val vm: MeshViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { RippleTheme { RippleRoot(vm, intent) } }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent) }
}

fun requiredPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= 31) {
    buildList {
        add(Manifest.permission.BLUETOOTH_SCAN); add(Manifest.permission.BLUETOOTH_ADVERTISE); add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()
} else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

@Composable
fun RippleRoot(vm: MeshViewModel, launchIntent: Intent?) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(requiredPermissions().all { ContextCompat.checkSelfPermission(context, it) == android.content.pm.PackageManager.PERMISSION_GRANTED })
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        granted = result.filterKeys { it != Manifest.permission.POST_NOTIFICATIONS }.values.all { it }
    }

    if (!granted) {
        PermissionGate { launcher.launch(requiredPermissions()) }
        return
    }

    LaunchedEffect(Unit) { vm.bind() }

    val nav = rememberNavController()
    LaunchedEffect(launchIntent) {
        launchIntent?.getStringExtra("conversation")?.let { nav.navigate("chat/$it") }
    }

    NavHost(navController = nav, startDestination = "home") {
        composable("home") {
            HomeScreen(vm, onOpenChat = { nav.navigate("chat/$it") }, onOpenSettings = { nav.navigate("settings") })
        }
        composable("chat/{conversation}", arguments = listOf(navArgument("conversation") { type = NavType.StringType })) {
            ChatScreen(vm, conversation = it.arguments!!.getString("conversation")!!, onBack = { nav.popBackStack() })
        }
        composable("settings") { SettingsScreen(vm, onBack = { nav.popBackStack() }) }
    }
}

@Composable
private fun PermissionGate(onRequest: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge)
        Text(stringResource(R.string.permission_rationale), textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 24.dp))
        Button(onClick = onRequest) { Text(stringResource(R.string.permission_grant)) }
    }
}

@Composable
fun RippleTheme(content: @Composable () -> Unit) {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val scheme = if (dark) darkColorScheme(primary = Color(0xFF7FDBCA), secondary = Color(0xFF9CC5FF), tertiary = Color(0xFFFFB59D))
    else lightColorScheme(primary = Color(0xFF006B5E), secondary = Color(0xFF3B5E8C), tertiary = Color(0xFF9C4325))
    MaterialTheme(colorScheme = scheme, content = content)
}
