package com.deskpet.engine

import com.deskpet.data.model.PetAnimationState
import kotlinx.coroutines.*
import kotlin.random.Random

class AutonomousBehavior(
    private val animationController: IAnimationStateController,
    private val coroutineScope: CoroutineScope,
    /**
     * 时间源。默认 System.nanoTime()（单调时钟，纳秒换算毫秒）：墙钟
     * System.currentTimeMillis() 会随 NTP 校时/用户改时间跳变——时间被回拨后
     * lastTouchTime 落在"未来"，差值恒负，宠物会"养着养着就不动了"直到再点一下。
     * 纯 Kotlin 单调源也让 JVM 单元测试无需依赖 Android stub。
     * 单元测试注入虚拟时钟（TestScheduler.currentTime）。
     */
    private val now: () -> Long = { System.nanoTime() / 1_000_000L }
) {
    // 小小kmmy 的自主节目单：到处走（左右行走，悬浮窗位置由 PetOverlayService 平移）、
    // 蹦跳、原地快跑、坐着发呆、记账（REVIEW）、睡觉、翘二郎腿、凑近看人、比心。
    // 老的 9 行素材（橘团）没有第 9~15 行，切过去会被钳制到 REVIEW 画面，不崩溃
    private val autoStates = listOf(
        PetAnimationState.RUNNING_LEFT,
        PetAnimationState.RUNNING_RIGHT,
        PetAnimationState.RUNNING,
        PetAnimationState.JUMPING,
        PetAnimationState.WAITING,
        PetAnimationState.REVIEW,
        PetAnimationState.SLEEP,
        PetAnimationState.SIT,
        PetAnimationState.PEEK,
        PetAnimationState.LOVE
    )

    private var lastTouchTime: Long = now()
    private var behaviorJob: Job? = null

    fun onUserTouch() {
        lastTouchTime = now()
    }

    fun start() {
        behaviorJob?.cancel()
        behaviorJob = coroutineScope.launch {
            while (isActive) {
                delay(Random.nextLong(5_000, 30_001))  // 5~30 秒随机间隔
                // 30 秒内有过触摸就跳过，避免用户正在互动时宠物自己乱动
                if (now() - lastTouchTime >= IDLE_THRESHOLD_MS) {
                    if (animationController.currentState.value == PetAnimationState.IDLE) {
                        animationController.switchState(autoStates.random())
                    }
                }
            }
        }
    }

    fun stop() {
        behaviorJob?.cancel()
        behaviorJob = null
    }

    companion object {
        private const val IDLE_THRESHOLD_MS = 30_000L
    }
}
