package com.xiaomi.xms.wearable.demo.interconnect

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
 *  - вешает MessageApi.addListener ТОЛЬКО после того, как permissions подтверждены;
 *  - при приходе {"type":"get_status"} собирает battery/ip/network и шлёт обратно;
 *  - после каждого входящего — перевешивает listener (защита от «молча умершего» биндера);
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

        /** Как часто опрашивать permissions, пока пользователь листает диалог Mi Fitness. */
        private const val PERM_RETRY_MS = 2_000L

        /** Сколько ждать, прежде чем сдаться (логически — пользователь проигнорировал диалог). */
        private const val PERM_RETRY_MAX_MS = 60_000L
    }

    private var nodeApi: NodeApi? = null
    private var messageApi: MessageApi? = null
    private var authApi: AuthApi? = null
    private var serviceApi: ServiceApi? = null

    /** did -> listener (listener'ов может быть несколько — по одному на каждый node) */
    private val listeners = ConcurrentHashMap<String, OnMessageReceivedListener>()

    /** Какие did уже подписаны на ITEM_CONNECTION (чтобы не дублировать подписку). */
    private val connectionSubscribed = ConcurrentHashMap.newKeySet<String>()

    /** Для какого did уже идёт цикл ожидания permissions (чтобы не плодить Handler'ов). */
    private val pendingPermWaits = ConcurrentHashMap.newKeySet<String>()

    @Volatile private var started = false

    private val mainHandler = Handler(Looper.getMainLooper())

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
                    pendingPermWaits.clear()
                }
            })
            // Первая попытка сразу — часто сервис уже привязан к моменту запуска.
            bootstrapNodes()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        for ((did, _) in listeners) {
            runCatching { messageApi?.removeListener(did) }
            runCatching { nodeApi?.unsubscribe(did, DataItem.ITEM_CONNECTION) }
        }
        listeners.clear()
        connectionSubscribed.clear()
        pendingPermWaits.clear()
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

        // Подписка на ITEM_CONNECTION — можно делать сразу, не требует DEVICE_MANAGER/NOTIFY
        // (перевешивание listener'а делаем, когда часы реконнектятся).
        subscribeConnection(did)

        // Listener вешаем ТОЛЬКО после подтверждения permissions.
        // Если их пока нет — стартуем вежливый опрос с экспоненциальным back-off'ом.
        ensurePermissionsThenAttach(did, elapsedMs = 0L)
    }

    /**
     * Периодически проверяет permissions для [did]; когда DEVICE_MANAGER + NOTIFY выданы —
     * вешает listener. Если за [PERM_RETRY_MAX_MS] не выданы — всё равно пытается повесить
     * listener (на некоторых прошивках NOTIFY не обязателен), и сдаётся.
     */
    private fun ensurePermissionsThenAttach(did: String, elapsedMs: Long) {
        if (!pendingPermWaits.add(did) && elapsedMs == 0L) {
            // Для этого did уже крутится ожидание — не плодим.
            return
        }
        val aApi = authApi ?: run {
            pendingPermWaits.remove(did)
            return
        }

        aApi.checkPermissions(did, arrayOf(Permission.DEVICE_MANAGER, Permission.NOTIFY))
            .addOnSuccessListener { granted ->
                val allOk = granted != null && granted.size >= 2 && granted[0] && granted[1]
                Log.d(TAG, "perms check did=$did elapsed=${elapsedMs}ms granted=${granted?.toList()}")
                if (allOk) {
                    pendingPermWaits.remove(did)
                    attachMessageListener(did)
                } else if (elapsedMs >= PERM_RETRY_MAX_MS) {
                    // Время вышло. Всё равно вешаем — хуже не будет, на некоторых прошивках
                    // NOTIFY не требуется, а без listener'а смысла в сервисе нет.
                    Log.w(TAG, "perms still missing after ${elapsedMs}ms; attaching listener anyway")
                    pendingPermWaits.remove(did)
                    attachMessageListener(did)
                } else {
                    mainHandler.postDelayed({
                        ensurePermissionsThenAttach(did, elapsedMs + PERM_RETRY_MS)
                    }, PERM_RETRY_MS)
                }
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "checkPermissions failed did=$did: ${e.message}; attaching listener anyway")
                pendingPermWaits.remove(did)
                attachMessageListener(did)
            }
    }

    private fun attachMessageListener(did: String) {
        if (listeners.containsKey(did)) return

        val listener = OnMessageReceivedListener { nodeId, bytes ->
            val text = try { String(bytes, Charsets.UTF_8) } catch (_: Throwable) { "<bin>" }
            Log.d(TAG, "RX did=$nodeId size=${bytes.size} hex=${bytes.toHexPreview()} text=$text")
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

    /**
     * Снять и заново повесить listener на [did]. Вызывается после каждого обработанного
     * входящего — защита от «молча умершего» xms-биндера, который может перестать отдавать
     * пакеты листенеру без каких-либо колбэков.
     */
    private fun reattachMessageListener(did: String) {
        val mApi = messageApi ?: return
        val old = listeners.remove(did)
        if (old != null) {
            mApi.removeListener(did)
                .addOnSuccessListener { Log.d(TAG, "removeListener ok did=$did (pre-reattach)") }
                .addOnFailureListener { Log.w(TAG, "removeListener failed did=$did: ${it.message}") }
        }
        attachMessageListener(did)
    }

    private fun subscribeConnection(did: String) {
        if (!connectionSubscribed.add(did)) return
        nodeApi?.subscribe(did, DataItem.ITEM_CONNECTION) { nodeId, item, data ->
            if (item.type != DataItem.ITEM_CONNECTION.type) return@subscribe
            val status = data.connectedStatus
            Log.d(TAG, "connection change did=$nodeId status=$status")
            if (status == DataSubscribeResult.RESULT_CONNECTION_CONNECTED) {
                // Часы вернулись — listener мог протухнуть, перевесим.
                reattachMessageListener(nodeId)
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

        // Защитное перевешивание listener'а после каждого обработанного сообщения:
        // на ряде прошивок xms-биндер молча перестаёт отдавать последующие пакеты,
        // если этого не делать, — симптом выглядит как «работает только первый Refresh».
        reattachMessageListener(did)
    }

    private fun respondStatus(did: String, forceRefreshIp: Boolean, replyTo: String) {
        val ctx = applicationContext
        Thread {
            val statusJson = PhoneStatusHelper.collectSync(ctx, forceRefreshIp)
            if (replyTo.isNotEmpty()) {
                statusJson.put("reply_to", replyTo)
            }
            val raw = statusJson.toString()
            val payload = raw.toByteArray(Charsets.UTF_8)
            Log.d(TAG, "TX did=$did size=${payload.size} hex=${payload.toHexPreview()} json=$raw")
            messageApi?.sendMessage(did, payload)
                ?.addOnSuccessListener {
                    Log.d(TAG, "TX did=$did ok")
                }
                ?.addOnFailureListener { e ->
                    Log.w(TAG, "TX did=$did failed: ${e.message}")
                }
        }.start()
    }
}

/** Короткое hex-превью (первые 32 байта) — для logcat'а, чтобы видеть реальный трафик. */
private fun ByteArray.toHexPreview(limit: Int = 32): String {
    if (isEmpty()) return "<empty>"
    val end = minOf(size, limit)
    val sb = StringBuilder(end * 3)
    for (i in 0 until end) {
        if (i > 0) sb.append(' ')
        sb.append(String.format("%02x", this[i].toInt() and 0xff))
    }
    if (size > limit) sb.append(" …(+${size - limit})")
    return sb.toString()
}
