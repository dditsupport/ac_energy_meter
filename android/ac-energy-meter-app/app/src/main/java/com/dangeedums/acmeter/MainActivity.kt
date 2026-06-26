package com.dangeedums.acmeter

import android.app.Application
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import com.dangeedums.acmeter.ui.CloudDashboardScreen
import com.dangeedums.acmeter.ui.CloudLoginScreen
import com.dangeedums.acmeter.ui.CloudViewModel
import com.dangeedums.acmeter.ui.DeviceDetailScreen
import com.dangeedums.acmeter.ui.DeviceDetailViewModel
import com.dangeedums.acmeter.ui.MainViewModel
import com.dangeedums.acmeter.ui.SavedDevicesScreen
import com.dangeedums.acmeter.ui.ScanScreen
import com.dangeedums.acmeter.ui.ServerConfigScreen
import com.dangeedums.acmeter.ui.WifiConfigScreen
import com.dangeedums.acmeter.ui.WifiConfigViewModel
import com.dangeedums.acmeter.ui.theme.AcEnergyMeterTheme
import java.net.URLEncoder
import java.net.URLDecoder

class MainActivity : ComponentActivity() {

    private val mainVm: MainViewModel by viewModels { MainViewModel.factory(application) }
    private val cloudVm: CloudViewModel by viewModels { CloudViewModel.factory(application) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AcEnergyMeterTheme {
                Surface(modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background) {
                    AppRoot(mainVm, cloudVm, application)
                }
            }
        }
    }
}

@Composable
private fun AppRoot(mainVm: MainViewModel, cloudVm: CloudViewModel, application: Application) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    val showFab = currentRoute == "devices_saved"

    Scaffold(
        bottomBar = { AppBottomBar(nav, currentRoute) },
        floatingActionButton = {
            if (showFab) {
                FloatingActionButton(onClick = { nav.navigate("devices_scan") }) {
                    Icon(Icons.Default.Search, contentDescription = "Scan nearby")
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = "devices_saved",
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            devicesGraph(nav, mainVm, application)
            cloudGraph(cloudVm)
        }
    }
}

@Composable
private fun AppBottomBar(nav: NavHostController, currentRoute: String?) {
    NavigationBar {
        NavigationBarItem(
            selected = currentRoute?.startsWith("devices_") == true ||
                       currentRoute?.startsWith("device/") == true,
            onClick  = { nav.navigateSingleTop("devices_saved") },
            icon     = { Icon(Icons.Default.Devices, contentDescription = null) },
            label    = { Text("Devices") },
        )
        NavigationBarItem(
            selected = currentRoute == "cloud",
            onClick  = { nav.navigateSingleTop("cloud") },
            icon     = { Icon(Icons.Default.Cloud, contentDescription = null) },
            label    = { Text("Cloud") },
        )
    }
}

private fun NavHostController.navigateSingleTop(route: String) {
    navigate(route) {
        launchSingleTop = true
        restoreState = true
        popUpTo(graph.startDestinationId) { saveState = true }
    }
}

private fun NavGraphBuilder.devicesGraph(
    nav: NavHostController,
    mainVm: MainViewModel,
    application: Application,
) {
    composable("devices_saved") {
        val devices by mainVm.savedDevices.collectAsStateWithLifecycle(initialValue = emptyList())
        SavedDevicesScreen(
            devices = devices,
            onRemove = mainVm::removeDevice,
            onOpen = { d ->
                val name = URLEncoder.encode(d.name, "UTF-8")
                val addr = URLEncoder.encode(d.address, "UTF-8")
                nav.navigate("device/$addr/$name")
            },
        )
    }
    composable("devices_scan") {
        val scanState by mainVm.scanState.collectAsStateWithLifecycle()
        val context = LocalContext.current
        ScanScreen(
            state = scanState,
            onStart = mainVm::startScan,
            onStop  = mainVm::stopScan,
            onAdd   = { device ->
                mainVm.addDevice(device)
                mainVm.stopScan()
                Toast.makeText(
                    context,
                    context.getString(R.string.device_added, device.name),
                    Toast.LENGTH_SHORT,
                ).show()
                // Confirm + return to the saved-devices list, where the newly
                // added device now appears (it's persisted via the store flow).
                nav.popBackStack()
            },
            onBack  = { nav.popBackStack() },
        )
    }

    // Nested graph so detail / wifi / server share one DeviceDetailViewModel
    // (and therefore one open BLE connection).
    navigation(startDestination = "device_detail", route = "device/{address}/{name}") {
        composable("device_detail") { entry ->
            val parentEntry = remember(entry) {
                nav.getBackStackEntry("device/{address}/{name}")
            }
            val address = URLDecoder.decode(parentEntry.arguments?.getString("address") ?: "", "UTF-8")
            val name    = URLDecoder.decode(parentEntry.arguments?.getString("name") ?: address, "UTF-8")
            val vm: DeviceDetailViewModel = viewModel(
                viewModelStoreOwner = parentEntry,
                factory = DeviceDetailViewModel.factory(application, address),
            )
            DeviceDetailScreen(
                deviceName = name,
                vm = vm,
                onBack = { nav.popBackStack() },
                onConfigureWifi   = { nav.navigate("device_wifi") },
                onConfigureServer = { nav.navigate("device_server") },
                onGoToCloud = { nav.navigateSingleTop("cloud") },
            )
        }
        composable("device_wifi") { entry ->
            val parentEntry = remember(entry) {
                nav.getBackStackEntry("device/{address}/{name}")
            }
            val address = URLDecoder.decode(parentEntry.arguments?.getString("address") ?: "", "UTF-8")
            val parentVm: DeviceDetailViewModel = viewModel(
                viewModelStoreOwner = parentEntry,
                factory = DeviceDetailViewModel.factory(application, address),
            )
            // Per-screen VM but uses the parent's already-connected MeterGatt.
            val vm: WifiConfigViewModel = remember(parentVm) { WifiConfigViewModel(parentVm.gatt) }
            WifiConfigScreen(vm = vm, onBack = { nav.popBackStack() })
        }
        composable("device_server") { entry ->
            val parentEntry = remember(entry) {
                nav.getBackStackEntry("device/{address}/{name}")
            }
            val address = URLDecoder.decode(parentEntry.arguments?.getString("address") ?: "", "UTF-8")
            val parentVm: DeviceDetailViewModel = viewModel(
                viewModelStoreOwner = parentEntry,
                factory = DeviceDetailViewModel.factory(application, address),
            )
            ServerConfigScreen(gatt = parentVm.gatt, vm = parentVm, onBack = { nav.popBackStack() })
        }
    }
}

private fun NavGraphBuilder.cloudGraph(cloudVm: CloudViewModel) {
    composable("cloud") {
        val ui by cloudVm.ui.collectAsStateWithLifecycle()
        if (ui.loggedIn) {
            CloudDashboardScreen(vm = cloudVm, onSignOut = { cloudVm.logout() })
        } else {
            CloudLoginScreen(vm = cloudVm)
        }
    }
}
