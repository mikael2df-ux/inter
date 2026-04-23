package com.xiaomi.xms.wearable.demo.interconnect

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.xiaomi.xms.wearable.Wearable
import com.xiaomi.xms.wearable.auth.AuthApi
import com.xiaomi.xms.wearable.auth.Permission
import com.xiaomi.xms.wearable.demo.R
import com.xiaomi.xms.wearable.demo.ui.message.PhoneStatusHelper
import com.xiaomi.xms.wearable.message.MessageApi
import com.xiaomi.xms.wearable.message.OnMessageReceivedListener
import com.xiaomi.xms.wearable.node.DataItem
import com.xiaomi.xms.wearable.node.DataSubscribeResult
import com.xiaomi.xms.wearable.node.Node
import com.xiaomi.xms.wearable.node.NodeApi
import com.xiaomi.xms.wearable.service.OnServiceConnectionListener
import com.xiaomi.xms.wearable.service.ServiceApi
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * ForegroundService, который держит канал interconnect с часами:
 *  - дожидается пока Mi Fitness/Xiaomi Wear (xms service) будет привязан;
 *  - получает did подключённых часов;
 *  - запрашивает DEVICE_MANAGER + NOTIFY (если ещё не выдано);
 *  - вешает MessageApi.addListener на каждый node;
 *  - при приходе {"type":"get_status"} собирает battery/ip/network и шлёт обратно;
 *  - слушает ITEM_CONNECTION — перевешивает listener при реконнекте часов.
 *
 * Запускается из MainActivity через ContextCompat.startForegroundService(...).
 */
class InterconnectService : Service() {

    companion object {
        private const val TAG = "InterconnectSvc"
        private const val CHANNEL_ID = "interconnect_bridge"
        private const val NOTIF_ID = 4711

        const val ACTION_START = "com.xiaomi.xms.wearable.demo.START"
        const val ACTION_STOP = "com.xiaomi.xms.wearable.demo.STOP"
    }

    private var nodeApi: NodeApi? = null
    private var messageApi: MessageApi? = null
    private var authApi: AuthApi? = null
    private var serviceApi: ServiceApi? = null

    /** did -> listener (listener'ов может быть несколько — по одному на каждый node) */
    private val listeners = ConcurrentHashMap<String, OnMessageReceivedListener>()

    /** Нужно помнить, какие did уже подписаны на ITEM_CONNECTION, чтобы не дублировать */
    private val connectionSubscribed = ConcurrentHashMap.newKeySet<String>()

    @Volatile private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val appCtx = applicationContext
        nodeApi = Wearable.getNodeApi(appCtx)
        messageApi = Wearable.getMessageApi(appCtx)
        authApi = Wearable.getAuthApi(appCtx)
        serviceApi = Wearable.getServiceApi(appCtx)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelfSafely()
            return START_NOT_STICKY
        }

        ensureForeground()

        if (!started) {
            started = true
            // Подписываемся на состояние xms-сервиса, чтобы перестартовать логику,
            // если Mi Fitness будет переподключаться.
            serviceApi?.registerServiceConnectionListener(object : OnServiceConnectionListener {
                override fun onServiceConnected() {
                    Log.d(TAG, "xms service connected")
                    bootstrapNodes()
                }
                override fun onServiceDisconnected() {
                    Log.w(TAG, "xms service disconnected")
                    // listener'ы на той стороне протухают, сбросим кэш — перевесим при реконнекте.
                    listeners.clear()
                    connectionSubscribed.clear()
                }
            })
            // Первая попытка сразу — часто сервис уже привязан к моменту запуска.
            bootstrapNodes()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        for ((did, _) in listeners) {
            runCatching { messageApi?.removeListener(did) }
            runCatching { nodeApi?.unsubscribe(did, DataItem.ITEM_CONNECTION) }
        }
        listeners.clear()
        connectionSubscribed.clear()
        super.onDestroy()
    }

    // ---------- Foreground notification ----------

    private fun ensureForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    "Mi Band bridge",
                    NotificationManager.IMPORTANCE_MIN
                ).apply {
                    description = "Keeps interconnect channel alive"
                    setShowBadge(false)
                }
                nm.createNotificationChannel(ch)
            }
        }

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Mi Band bridge")
            .setContentText("listening for watch requests")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // На API 34 обязательно явно указывать тип foreground service.
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun stopSelfSafely() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Throwable) {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    // ---------- Logic ----------

    private fun bootstrapNodes() {
        val nApi = nodeApi ?: return
        nApi.connectedNodes
            .addOnSuccessListener { nodes ->
                Log.d(TAG, "connectedNodes=${nodes?.size}")
                nodes?.forEach { node -> setupNode(node) }
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "connectedNodes failed: ${e.message}")
            }
    }

    private fun setupNode(node: Node) {
        val did = node.id
        Log.d(TAG, "setupNode did=$did name=${node.name}")

        // 1) Убедимся, что у нас есть нужные права. Если их нет — запросим.
        //    requestPermission НЕ рисует UI автоматически в нашем service-контексте;
        //    он показывает системный диалог от Mi Fitness. Если пользователь ещё
        //    не выдавал права — лучше вызвать requestPermission из MainActivity первый раз.
        authApi?.checkPermissions(did, arrayOf(Permission.DEVICE_MANAGER, Permission.NOTIFY))
            ?.addOnSuccessListener { granted ->
                val allOk = granted != null && granted.size >= 2 && granted[0] && granted[1]
                Log.d(TAG, "perms check did=$did granted=${granted?.toList()}")
                if (allOk) {
                    attachMessageListener(did)
                    subscribeConnection(did)
                } else {
                    Log.w(TAG, "permissions not granted yet — user should open MainActivity once")
                    // Всё равно попробуем — на некоторых прошивках listener работает и без NOTIFY.
                    attachMessageListener(did)
                    subscribeConnection(did)
                }
            }
            ?.addOnFailureListener { e ->
                Log.w(TAG, "checkPermissions failed: ${e.message}")
                attachMessageListener(did)
                subscribeConnection(did)
            }
    }

    private fun attachMessageListener(did: String) {
        if (listeners.containsKey(did)) return

        val listener = OnMessageReceivedListener { nodeId, bytes ->
            val text = try { String(bytes, Charsets.UTF_8) } catch (_: Throwable) { "<bin>" }
            Log.d(TAG, "RX did=$nodeId size=${bytes.size} text=$text")
            handleIncoming(nodeId, text)
        }
        listeners[did] = listener

        messageApi?.addListener(did, listener)
            ?.addOnSuccessListener {
                Log.d(TAG, "addListener ok did=$did")
            }
            ?.addOnFailureListener { e ->
                Log.w(TAG, "addListener failed did=$did: ${e.message}")
                listeners.remove(did)
            }
    }

    private fun subscribeConnection(did: String) {
        if (!connectionSubscribed.add(did)) return
        nodeApi?.subscribe(did, DataItem.ITEM_CONNECTION) { nodeId, item, data ->
            if (item.type != DataItem.ITEM_CONNECTION.type) return@subscribe
            val status = data.connectedStatus
            Log.d(TAG, "connection change did=$nodeId status=$status")
            if (status == DataSubscribeResult.RESULT_CONNECTION_CONNECTED) {
                // Часы вернулись — listener мог протухнуть, перевесим.
                listeners.remove(nodeId)
                attachMessageListener(nodeId)
            } else {
                // Часы ушли — чистим кэш listener'а, при следующем реконнекте перевесим.
                listeners.remove(nodeId)
            }
        }
            ?.addOnSuccessListener { Log.d(TAG, "subscribe ITEM_CONNECTION ok did=$did") }
            ?.addOnFailureListener {
                Log.w(TAG, "subscribe ITEM_CONNECTION failed did=$did: ${it.message}")
                connectionSubscribed.remove(did)
            }
    }

    private fun handleIncoming(did: String, rawText: String) {
        // Пытаемся распарсить как JSON; если не JSON — всё равно отвечаем статусом.
        val json = runCatching { JSONObject(rawText) }.getOrNull()
        val type = json?.optString("type") ?: ""
        val forceIp = type.equals("get_status", ignoreCase = true) ||
                rawText.contains("refresh", ignoreCase = true)

        respondStatus(did, forceRefreshIp = forceIp, replyTo = type)
    }

    private fun respondStatus(did: String, forceRefreshIp: Boolean, replyTo: String) {
        val ctx = applicationContext
        Thread {
            val statusJson = PhoneStatusHelper.collectSync(ctx, forceRefreshIp)
            if (replyTo.isNotEmpty()) {
                statusJson.put("reply_to", replyTo)
            }
            val payload = statusJson.toString().toByteArray(Charsets.UTF_8)
            messageApi?.sendMessage(did, payload)
                ?.addOnSuccessListener {
                    Log.d(TAG, "TX did=$did ok json=$statusJson")
                }
                ?.addOnFailureListener { e ->
                    Log.w(TAG, "TX did=$did failed: ${e.message}")
                }
        }.start()
    }
}
