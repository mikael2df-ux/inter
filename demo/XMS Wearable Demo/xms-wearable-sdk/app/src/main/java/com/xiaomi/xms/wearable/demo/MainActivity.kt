package com.xiaomi.xms.wearable.demo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.xiaomi.xms.wearable.Wearable
import com.xiaomi.xms.wearable.auth.Permission
import com.xiaomi.xms.wearable.demo.databinding.ActivityMainBinding
import com.xiaomi.xms.wearable.demo.interconnect.InterconnectService

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val REQ_NOTIF = 101
    }

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val navView: BottomNavigationView = binding.navView

        val navController = findNavController(R.id.nav_host_fragment_activity_main)
        val appBarConfiguration = AppBarConfiguration(
            setOf(
                R.id.navigation_home, R.id.navigation_message, R.id.navigation_notifications
            )
        )
        setupActionBarWithNavController(navController, appBarConfiguration)
        navView.setupWithNavController(navController)

        // 1) Android 13+: разрешение на показ foreground-уведомления.
        ensurePostNotifications()

        // 2) Попросить у Mi Fitness разрешения DEVICE_MANAGER + NOTIFY для подключённых часов.
        //    Диалог рисует Mi Fitness, нужен activity-контекст (поэтому делаем здесь, а не в сервисе).
        requestWearPermissionsOnce()

        // 3) Запускаем фоновый сервис-мост. Он сам найдёт подключённые часы,
        //    повесит listener и будет отвечать на get_status.
        startInterconnectService()
    }

    private fun ensurePostNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQ_NOTIF
                )
            }
        }
    }

    private fun requestWearPermissionsOnce() {
        val nodeApi = Wearable.getNodeApi(applicationContext)
        val authApi = Wearable.getAuthApi(applicationContext)
        nodeApi.connectedNodes
            .addOnSuccessListener { nodes ->
                val node = nodes?.firstOrNull()
                if (node == null) {
                    Log.w(TAG, "no connected wearable node; Mi Fitness and band must be paired first")
                    return@addOnSuccessListener
                }
                authApi.checkPermissions(
                    node.id,
                    arrayOf(Permission.DEVICE_MANAGER, Permission.NOTIFY)
                ).addOnSuccessListener { granted ->
                    val needRequest = granted == null || granted.size < 2 ||
                            !granted[0] || !granted[1]
                    if (needRequest) {
                        authApi.requestPermission(
                            node.id,
                            Permission.DEVICE_MANAGER,
                            Permission.NOTIFY
                        )
                            .addOnSuccessListener { granted2 ->
                                Log.d(TAG, "granted perms: ${granted2?.toList()}")
                            }
                            .addOnFailureListener {
                                Log.w(TAG, "requestPermission failed: ${it.message}")
                            }
                    } else {
                        Log.d(TAG, "wear perms already granted")
                    }
                }.addOnFailureListener {
                    Log.w(TAG, "checkPermissions failed: ${it.message}")
                }
            }
            .addOnFailureListener {
                Log.w(TAG, "connectedNodes failed: ${it.message}")
            }
    }

    private fun startInterconnectService() {
        val intent = Intent(this, InterconnectService::class.java).apply {
            action = InterconnectService.ACTION_START
        }
        ContextCompat.startForegroundService(this, intent)
    }
}
