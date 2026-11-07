package com.deskpet.data.model

enum class PetAnimationState(val row: Int, val specName: String) {
    IDLE(0, "idle"),
    RUNNING_RIGHT(1, "running-right"),
    RUNNING_LEFT(2, "running-left"),
    WAVING(3, "waving"),
    JUMPING(4, "jumping"),
    FAILED(5, "failed"),
    WAITING(6, "waiting"),
    RUNNING(7, "running"),
    REVIEW(8, "review"),
    // ===== 小小kmmy 扩展状态（第 9~15 行，需要 pet.json 声明 rows=16）=====
    // 老的 9 行素材被切到这些状态时，行号会被钳制到第 8 行（REVIEW），只复用画面不崩溃
    SHY(9, "shy"),
    KISS(10, "kiss"),
    ANGRY(11, "angry"),
    SLEEP(12, "sleep"),
    SIT(13, "sit"),
    PEEK(14, "peek"),
    LOVE(15, "love");

    companion object {
        /** 默认状态映射；帧数与精灵图绘制脚本（tools/generate_kmmy_spritesheet.py）保持一致 */
        val DEFAULT_STATE_MAP: Map<String, AnimationState> = entries.associate { state ->
            state.specName to AnimationState(
                name = state.specName,
                row = state.row,
                frames = when (state) {
                    IDLE -> 6
                    RUNNING_RIGHT -> 8
                    RUNNING_LEFT -> 8
                    WAVING -> 4
                    JUMPING -> 5
                    FAILED -> 8
                    WAITING -> 6
                    RUNNING -> 6
                    REVIEW -> 6
                    SHY -> 4
                    KISS -> 5
                    ANGRY -> 6
                    SLEEP -> 6
                    SIT -> 4
                    PEEK -> 5
                    LOVE -> 4
                }
            )
        }
    }
}
