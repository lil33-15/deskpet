package com.deskpet.overlay

import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.app.PendingIntent
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.deskpet.R
import com.deskpet.data.PetRepository
import com.deskpet.data.model.DragDirection
import com.deskpet.data.model.PetAnimationState
import com.deskpet.engine.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlin.math.abs

class PetOverlayService : LifecycleService(), ViewModelStoreOwner, SavedStateRegistryOwner {

    override val viewModelStore = ViewModelStore()
    override val savedStateRegistry = SavedStateRegistryController.create(this).also { it.performRestore(null) }.savedStateRegistry

    private lateinit var wm: WindowManager
    private var cv: ComposeView? = null
    private lateinit var repo: PetRepository
    private lateinit var engine: PetEngine
    private lateinit var anim: AnimationController
    private lateinit var gesture: GestureHandler
    private lateinit var parser: SpritesheetParser
    private var auto: AutonomousBehavior? = null
    private var petId: String? = null
    private var job: Job? = null
    private var scaleJob: Job? = null
    /** 捏合缩放回写 DataStore 的防抖任务 */
    private var scaleSyncJob: Job? = null
    private var wx = 0f
    private var wy = 200f
    private var savedScale = 1f
    // CoroutineExceptionHandler 是必须的：SupervisorJob 只隔离子协程间的失败，
    // 不吞异常本身——漏网异常会走线程默认处理器直接崩掉前台服务进程。
    // 加上后降级为记日志，宠物最多停一拍而不是整个 App"已停止"。
    private val sc = CoroutineScope(
        Dispatchers.Main + SupervisorJob() + CoroutineExceptionHandler { _, e ->
            Log.e(TAG, "Uncaught exception in overlay scope", e)
        }
    )

    /** 亮灭屏状态：省电广播到来时需要据此决定是否恢复自主行为（灭屏时不能开） */
    @Volatile private var screenOn = true

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: android.content.Context?, i: Intent?) {
            // onReceive 不在协程作用域里，这里抛异常会直接崩进程，整体兜一层
            try {
                handleScreenBroadcast(i)
            } catch (e: Exception) {
                Log.e(TAG, "screen broadcast handling failed", e)
            }
        }

        private fun handleScreenBroadcast(i: Intent?) {
            if (!::anim.isInitialized) return
            val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
            when (i?.action) {
                Intent.ACTION_SCREEN_OFF -> { screenOn = false; anim.stop(); auto?.stop() }
                Intent.ACTION_SCREEN_ON -> {
                    screenOn = true
                    anim.start()
                    if (!pm.isPowerSaveMode) auto?.start()
                }
                android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> {
                    anim.powerSaveMode = pm.isPowerSaveMode
                    if (pm.isPowerSaveMode) auto?.stop()
                    else if (screenOn) auto?.start()
                }
            }
        }
    }

    private fun onDrag(dx: Float, dy: Float, d: DragDirection) {
        wx += dx
        wy += dy
        updatePosition()
        if (anim.isCodexPet()) {
            anim.switchState(gesture.mapDirectionToState(d))
        }
        auto?.onUserTouch()
    }

    /** 连击计数任务：350ms 静默窗口后按次数派发动作 */
    private var tapJob: Job? = null
    private var tapCount = 0

    /**
     * 小小kmmy 的暗号：单击=害羞，双击=亲亲，三连击及以上=生气。
     * 用计数窗口而非常见的 onDoubleTap 回调，是为了能区分三连击；
     * 代价是单击响应有 350ms 延迟，桌宠场景可接受。
     */
    private fun onTap() {
        if (!anim.isCodexPet()) {
            bounce()
            auto?.onUserTouch()
            return
        }
        auto?.onUserTouch()
        tapCount++
        tapJob?.cancel()
        tapJob = sc.launch {
            delay(350)
            val n = tapCount
            tapCount = 0
            when {
                n >= 3 -> anim.switchState(PetAnimationState.ANGRY)
                n == 2 -> anim.switchState(PetAnimationState.KISS)
                else -> anim.switchState(PetAnimationState.SHY)
            }
        }
    }

    /** 到处走走：行走状态时按帧率平移悬浮窗（边界由 clampPosition 兜底夹取） */
    private var wanderJob: Job? = null

    private fun startWander() {
        wanderJob?.cancel()
        wanderJob = sc.launch {
            val step = 5f * resources.displayMetrics.density
            while (isActive) {
                when (anim.currentState.value) {
                    PetAnimationState.RUNNING_LEFT -> { wx -= step; updatePosition() }
                    PetAnimationState.RUNNING_RIGHT -> { wx += step; updatePosition() }
                    else -> { }
                }
                delay(200)
            }
        }
    }

    private fun onScale(zoom: Float, focusX: Float, focusY: Float) {
        // 与 AnimationController / UI 滑块保持一致：50%~500%
        val ns = (savedScale * zoom).coerceIn(0.5f, 5f)
        // clamp 截断后实际生效的比例，位置补偿必须用它，否则锚点会漂
        val applied = if (savedScale > 0f) ns / savedScale else 1f
        savedScale = ns
        anim.setScale(ns)
        val pid = petId
        if (pid != null && applied != 1f) {
            scaleSyncJob?.cancel()
            scaleSyncJob = sc.launch {
                delay(400)
                try {
                    repo.setScale(pid, savedScale)
                } catch (e: Exception) {
                    Log.w(TAG, "scale sync failed", e)
                }
            }
        }
        if (applied != 1f) {
            wx += focusX * (1f - applied)
            wy += focusY * (1f - applied)
            updatePosition()
        }
        auto?.onUserTouch()
    }

    /** 手势结束时立刻落盘，避免进程被杀导致位置丢失 */
    private fun persistPositionSoon() {
        if (petId == null) return
        sc.launch {
            try {
                withTimeout(2000L) { persistPosition() }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to save position", e)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        repo = (application as com.deskpet.DeskPetApp).petRepository
        parser = SpritesheetParser()
        engine = PetEngine(parser, GifRenderer())
        anim = AnimationController(engine, sc)
        gesture = GestureHandler(
            onDrag = ::onDrag,
            onTap = ::onTap,
            isCodexPet = anim::isCodexPet,
            onScale = ::onScale,
            onDragEnd = ::persistPositionSoon
        )
        registerReceiverSafely()
        startForeground()
    }

    private fun registerReceiverSafely() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        androidx.core.content.ContextCompat.registerReceiver(
            this, screenReceiver, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        if (::anim.isInitialized) {
            anim.powerSaveMode = pm.isPowerSaveMode
        }
        screenOn = pm.isInteractive
    }

    private fun startForeground() {
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.createNotificationChannel(android.app.NotificationChannel(
                CHANNEL_ID, "小小kmmy", android.app.NotificationManager.IMPORTANCE_LOW
            ).apply { description = "小小kmmy悬浮窗运行中" })

            val contentIntent = android.app.PendingIntent.getActivity(
                this, 0,
                Intent(this, com.deskpet.MainActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                },
                android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val ntf = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("小小kmmy")
                .setContentText("正在桌面上跑来跑去")
                .setSmallIcon(R.drawable.ic_stat_pet)
                .setLargeIcon(android.graphics.BitmapFactory.decodeResource(resources, R.drawable.ic_notif_large))
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "关闭宠物",
                    PendingIntent.getService(
                        this, 1,
                        Intent(this, PetOverlayService::class.java).setAction(ACTION_STOP),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                )
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFY_ID, ntf, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFY_ID, ntf)
            }
        } catch (e: Exception) {
            Log.w(TAG, "startForeground failed", e)
            Toast.makeText(this@PetOverlayService, "前台服务失败: ${e.message}", Toast.LENGTH_LONG).show()
            stopSelf()
        }
    }

    override fun onStartCommand(i: Intent?, f: Int, s: Int): Int {
        super.onStartCommand(i, f, s)
        if (i?.action == ACTION_STOP) {
            repo.setOverlayAutoResumeAsync(false)
            stopSelf()
            return START_NOT_STICKY
        }
        job?.cancel()
        job = sc.launch {
            try {
                val id = i?.getStringExtra(EXTRA_PET_ID) ?: repo.getActivePetId().first()
                if (id.isNullOrBlank()) {
                    Log.w(TAG, "No pet id to show")
                    stopSelf()
                    return@launch
                }
                show(id)
            } catch (e: CancellationException) {
                return@launch
            } catch (e: Exception) {
                Log.e(TAG, "show failed", e)
                Toast.makeText(this@PetOverlayService, "启动失败: ${e.message}", Toast.LENGTH_LONG).show()
                OverlayState.setRunning(null)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        persistPositionAsync()
        OverlayState.setRunning(null)
        scaleJob?.cancel()
        tapJob?.cancel()
        wanderJob?.cancel()
        removeOverlayView()
        if (::anim.isInitialized) anim.stop()
        auto?.stop()
        if (::engine.isInitialized) engine.release()
        try {
            unregisterReceiver(screenReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister screen receiver", e)
        }
        viewModelStore.clear()
        sc.cancel()
        super.onDestroy()
    }

    override fun onTaskRemoved(r: Intent?) {
        super.onTaskRemoved(r)
        persistPositionAsync()
    }

    private fun persistPositionAsync() {
        petId?.let { repo.savePositionAsync(it, wx, wy, savedScale) }
    }

    override fun onConfigurationChanged(c: Configuration) {
        super.onConfigurationChanged(c)
        val p = displaySize()
        if (wx > p.x || wy > p.y) {
            wx = 100f
            wy = 100f
        }
        clampPosition()
        updatePosition()
    }

    private suspend fun persistPosition() {
        val id = petId ?: return
        repo.savePosition(id, wx, wy, savedScale)
    }

    private suspend fun show(id: String) {
        if (!Settings.canDrawOverlays(this)) {
            OverlayState.setRunning(null)
            stopSelf()
            return
        }
        val info = withContext(Dispatchers.IO) { repo.getPet(id) }
        if (info == null) {
            Log.w(TAG, "Pet not found: $id")
            Toast.makeText(this, "宠物不存在，请重新选择", Toast.LENGTH_SHORT).show()
            OverlayState.setRunning(null)
            stopSelf()
            return
        }
        if (petId != null && petId != id) {
            try {
                withTimeout(2000L) { persistPosition() }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to save previous pet position", e)
            }
        }
        petId = id
        wx = info.positionX
        wy = info.positionY
        val loaded = withContext(Dispatchers.IO) { anim.reload(info) }
        if (!loaded) {
            Toast.makeText(this, "宠物加载失败，文件可能已损坏", Toast.LENGTH_SHORT).show()
            OverlayState.setRunning(null)
            stopSelf()
            return
        }
        anim.setScale(info.scale)
        savedScale = info.scale
        if (screenOn) anim.start()
        currentCoroutineContext().ensureActive()
        if (!add()) {
            OverlayState.setRunning(null)
            stopSelf()
            return
        }
        observeScale(id)
        auto?.stop()
        auto = AutonomousBehavior(anim, sc)
        if (screenOn && !anim.powerSaveMode) auto?.start()
        // 行走位移协程：RUNNING_LEFT/RIGHT 状态期间平移悬浮窗
        startWander()
        OverlayState.setRunning(id)
    }

    private fun observeScale(id: String) {
        scaleJob?.cancel()
        scaleJob = sc.launch {
            repo.getAllPets().collect { pets ->
                val info = pets.find { it.id == id } ?: return@collect
                if (abs(info.scale - savedScale) > 0.001f) {
                    savedScale = info.scale
                    anim.setScale(info.scale)
                }
            }
        }
    }

    @android.annotation.SuppressLint("RtlHardcoded")
    private fun add(): Boolean {
        if (!::wm.isInitialized) return false
        if (!Settings.canDrawOverlays(this)) {
            return false
        }
        removeOverlayView()
        cv = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@PetOverlayService)
            setViewTreeViewModelStoreOwner(this@PetOverlayService)
            setViewTreeSavedStateRegistryOwner(this@PetOverlayService)
            setContent { PetOverlayRenderer(anim, gesture.modifier) }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = wx.toInt()
            y = wy.toInt()
        }
        return try {
            wm.addView(cv, params)
            true
        } catch (e: Exception) {
            Log.e(TAG, "addView", e)
            cv = null
            Toast.makeText(this, "悬浮窗添加失败: ${e.message}", Toast.LENGTH_LONG).show()
            false
        }
    }

    private fun removeOverlayView() {
        cv?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to remove overlay view", e)
            }
        }
        cv = null
    }

    private fun updatePosition() {
        clampPosition()
        cv?.let { v ->
            (v.layoutParams as? WindowManager.LayoutParams)?.let { p ->
                p.x = wx.toInt()
                p.y = (wy + bounceOffset).toInt()
                try {
                    wm.updateViewLayout(v, p)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to update overlay position", e)
                }
            }
        }
    }

    private fun clampPosition() {
        val sb = safeBounds()
        val w = anim.petWidth.value
        val h = anim.petHeight.value
        val margin = 24f * resources.displayMetrics.density
        wx = wx.coerceIn((sb.left - w + margin), (sb.right - margin))
        wy = wy.coerceIn(sb.top.toFloat(), (sb.bottom - h).toFloat().coerceAtLeast(sb.top.toFloat()))
    }

    private fun safeBounds(): android.graphics.Rect {
        val b = displayBounds()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val types = android.view.WindowInsets.Type.systemBars() or
                android.view.WindowInsets.Type.displayCutout()
            val ins = wm.currentWindowMetrics.windowInsets.getInsets(types)
            return android.graphics.Rect(
                b.left + ins.left, b.top + ins.top,
                b.right - ins.right, b.bottom - ins.bottom
            )
        }
        return b
    }

    private fun displayBounds(): android.graphics.Rect {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            wm.currentWindowMetrics.bounds
        } else {
            val p = Point()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getSize(p)
            android.graphics.Rect(0, 0, p.x, p.y)
        }
    }

    private fun displaySize(): Point {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            wm.currentWindowMetrics.bounds.let { Point(it.width(), it.height()) }
        } else {
            @Suppress("DEPRECATION")
            Point().also { wm.defaultDisplay.getSize(it) }
        }
    }

    private var bounceJob: Job? = null
    @Volatile
    private var bounceOffset = 0f

    private fun bounce() {
        if (bounceJob?.isActive == true) return
        bounceJob = sc.launch {
            try {
                bounceOffset = -8f * resources.displayMetrics.density
                updatePosition()
                delay(100)
            } finally {
                bounceOffset = 0f
                updatePosition()
            }
        }
    }

    companion object {
        const val EXTRA_PET_ID = "pet_id"
        const val ACTION_STOP = "com.deskpet.action.STOP_OVERLAY"
        private const val TAG = "PO"
        private const val NOTIFY_ID = 1
        private const val CHANNEL_ID = "deskpet_fg"
    }
}
