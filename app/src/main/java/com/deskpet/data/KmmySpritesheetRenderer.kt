package com.deskpet.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.util.Log
import java.io.File

/**
 * 小小kmmy 精灵图现场渲染器。
 *
 * 形象来自珊珊的手绘设计：黑色圆角方块身体 + 白色 K + 蓝色点点 + 火柴棍四肢。
 * 网格：8 列 × 16 行，单格 192×192（与 assets/pets/kmmy/pet.json 的 rows/states 对应）。
 * 在首次加载示例宠物时直接画到 filesDir，避免二进制图片入库；
 * 坐标与 tools/generate_kmmy_spritesheet.py（预览/调参用）保持一致。
 */
object KmmySpritesheetRenderer {

    private const val TAG = "KmmyRenderer"
    private const val BUILTIN_ID = "kmmy"
    private const val CELL = 192
    private const val COLS = 8
    private const val ROWS = 16

    private const val BODY = 0xFF121216.toInt()
    private const val WHITE = 0xFFF5F5F5.toInt()
    private const val BLUE = 0xFF408CFF.toInt()
    private const val PINK = 0xFFFF82A0.toInt()
    private const val RED = 0xFFFF5564.toInt()
    private const val GREY = 0xFF787882.toInt()
    private const val BROWN = 0xFF96643C.toInt()
    private const val CREAM = 0xFFFAF6EB.toInt()
    private const val CHAIR = 0xFF6E5842.toInt()

    /** 是内置 kmmy 且目录里还没有精灵图时，现场画一张；否则返回 null（让调用方走原逻辑） */
    fun renderIfBuiltin(configId: String, destDir: File): File? {
        if (configId != BUILTIN_ID) return null
        val out = File(destDir, "spritesheet.webp")
        if (out.exists()) return out
        return try {
            val sheet = Bitmap.createBitmap(CELL * COLS, CELL * ROWS, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(sheet)
            for (row in 0 until ROWS) {
                val n = frameCountOf(row)
                for (col in 0 until n) {
                    drawFrame(canvas, row, col)
                }
            }
            destDir.mkdirs()
            out.outputStream().use { os ->
                // API 30 起 WEBP 常量改走 WEBP_LOSSLESS；低版本 WEBP quality=100 即无损。
                // 必须无损：有损压缩的边缘噪声会让透明通道出现杂点
                val ok = if (Build.VERSION.SDK_INT >= 30) {
                    sheet.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, os)
                } else {
                    @Suppress("DEPRECATION")
                    sheet.compress(Bitmap.CompressFormat.WEBP, 100, os)
                }
                if (!ok) throw java.io.IOException("webp encode failed")
            }
            sheet.recycle()
            out
        } catch (t: Throwable) {
            Log.e(TAG, "render kmmy spritesheet failed", t)
            out.delete()
            null
        }
    }

    private fun frameCountOf(row: Int): Int = when (row) {
        0 -> 6; 1 -> 8; 2 -> 8; 3 -> 4; 4 -> 5; 5 -> 8; 6 -> 6; 7 -> 6
        8 -> 6; 9 -> 4; 10 -> 5; 11 -> 6; 12 -> 6; 13 -> 4; 14 -> 5; 15 -> 4
        else -> 0
    }

    // ============ 基础画笔 ============

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }

    private fun limb(c: Canvas, pts: FloatArray, width: Float) {
        stroke.color = BODY
        stroke.strokeWidth = width
        val path = Path()
        path.moveTo(pts[0], pts[1])
        for (i in 2 until pts.size step 2) path.lineTo(pts[i], pts[i + 1])
        c.drawPath(path, stroke)
        // 圆帽（与 Python 版 limb 端点圆等价）
        fill.color = BODY
        c.drawCircle(pts[0], pts[1], width / 2, fill)
        c.drawCircle(pts[pts.size - 2], pts[pts.size - 1], width / 2, fill)
    }

    /** 画身体（黑方块+K+蓝点）。x=中心，bottom=身体底边 y。可倾斜/压扁/放大蓝点 */
    private fun body(
        c: Canvas, x: Float, bottom: Float, size: Float = 96f,
        tilt: Float = 0f, squash: Float = 1f,
        kColor: Int = WHITE, dotScale: Float = 1f
    ) {
        c.save()
        if (tilt != 0f) c.rotate(tilt, x, bottom)
        val bh = size * squash
        val rect = RectF(x - size / 2, bottom - bh, x + size / 2, bottom)
        fill.color = BODY
        c.drawRoundRect(rect, 18f, 18f, fill)
        // 白色 K（垂直视觉居中，+6 与 Python 版一致）
        textPaint.textSize = 56f
        textPaint.color = kColor
        val b = Rect()
        textPaint.getTextBounds("K", 0, 1, b)
        c.drawText("K", rect.centerX(), rect.centerY() - b.exactCenterY() + 6f, textPaint)
        // 蓝点点（右上）
        fill.color = BLUE
        c.drawCircle(rect.right - 26f, rect.top + 18f, 8f * dotScale, fill)
        c.restore()
    }

    // ============ 姿势拼装 ============

    private class Pose(
        var x: Float = 96f, var foot: Float = 180f, var size: Float = 96f,
        var tilt: Float = 0f, var squash: Float = 1f,
        var armsL: String = "down", var armsR: String = "down",
        var legsL: String = "stand", var legsR: String = "stand",
        var legsFront: Boolean = false, var kColor: Int = WHITE, var dotScale: Float = 1f
    )

    private fun drawKmmy(c: Canvas, p: Pose) {
        val bodyTop = p.foot - 14f - p.size * p.squash
        val hipY = p.foot - 14f
        val shY = bodyTop + 30f
        val lx = p.x - p.size / 2 + 6
        val rx = p.x + p.size / 2 - 6
        val x = p.x
        val foot = p.foot

        fun legs(name: String, leftSide: Boolean): FloatArray {
            val hx = if (leftSide) x - 20f else x + 20f
            return when (name) {
                "walk1" -> if (leftSide) floatArrayOf(hx, hipY, x - 33, foot) else floatArrayOf(hx, hipY, x + 27, foot)
                "walk2" -> if (leftSide) floatArrayOf(hx, hipY, x - 13, foot) else floatArrayOf(hx, hipY, x + 35, foot)
                "air" -> if (leftSide) floatArrayOf(hx, hipY, x - 28, foot - 16) else floatArrayOf(hx, hipY, x + 28, foot - 16)
                "crouch" -> if (leftSide) floatArrayOf(hx, hipY, x - 30, foot - 6, x - 24, foot) else floatArrayOf(hx, hipY, x + 30, foot - 6, x + 24, foot)
                "sit" -> if (leftSide) floatArrayOf(hx, hipY, hx, foot + 18, x - 26, foot + 34) else floatArrayOf(hx, hipY, hx, foot + 18, x + 26, foot + 34)
                "cross" -> if (leftSide) floatArrayOf(hx, hipY, hx, foot + 16, x - 24, foot + 32) else floatArrayOf(hx, hipY, x + 2, foot + 6, x + 22, foot - 8)
                "cross2" -> if (leftSide) floatArrayOf(hx, hipY, hx, foot + 16, x - 24, foot + 32) else floatArrayOf(hx, hipY, x + 2, foot + 2, x + 20, foot - 12)
                else -> floatArrayOf(hx, hipY, hx, foot)
            }
        }

        fun drawLegs() {
            limb(c, legs(p.legsL, true), 7f)
            limb(c, legs(p.legsR, false), 7f)
        }

        fun arm(sx: Float, name: String): FloatArray {
            val s = if (sx < x) -1f else 1f
            return when (name) {
                "swing1" -> floatArrayOf(sx, shY, sx + if (s < 0) -22f else 6f, shY + 30)
                "swing2" -> floatArrayOf(sx, shY, sx + if (s < 0) -6f else 22f, shY + 30)
                "up" -> floatArrayOf(sx, shY, sx + 18f * s, shY - 28)
                "wave" -> floatArrayOf(sx, shY, sx + 20f, shY - 28, sx + 12f, shY - 48)
                "wave2" -> floatArrayOf(sx, shY, sx + 22f, shY - 24, sx + 30f, shY - 40)
                "cover" -> floatArrayOf(sx, shY, x + 16f * s, shY + 22)
                "book" -> floatArrayOf(sx, shY, x + 22f * s, shY + 42)
                "write" -> floatArrayOf(sx, shY, x + 36f, shY + 48)
                "back" -> floatArrayOf(sx, shY, sx + 12f * s, shY + 12)
                "heart" -> floatArrayOf(sx, shY, x + 26f * s, bodyTop - 18)
                else -> floatArrayOf(sx, shY, sx + 14f * s, shY + 32)
            }
        }

        if (!p.legsFront) drawLegs()
        limb(c, arm(lx, p.armsL), 6f)
        limb(c, arm(rx, p.armsR), 6f)
        body(c, x, foot - 14f, p.size, p.tilt, p.squash, p.kColor, p.dotScale)
        if (p.legsFront) drawLegs()
    }

    // ============ 小道具/表情 ============

    private fun blush(c: Canvas, x: Float = 96f, y: Float = 108f) {
        fill.color = PINK
        fill.alpha = 220
        c.drawRoundRect(RectF(x - 45, y - 6, x - 23, y + 6), 6f, 6f, fill)
        c.drawRoundRect(RectF(x + 23, y - 6, x + 45, y + 6), 6f, 6f, fill)
        fill.alpha = 255
    }

    private fun heart(c: Canvas, cx: Float, cy: Float, s: Float, color: Int = RED) {
        fill.color = color
        c.drawOval(RectF(cx - s, cy - 0.9f * s, cx, cy), fill)
        c.drawOval(RectF(cx, cy - 0.9f * s, cx + s, cy), fill)
        val path = Path()
        path.moveTo(cx - 0.95f * s, cy - 0.25f * s)
        path.lineTo(cx + 0.95f * s, cy - 0.25f * s)
        path.lineTo(cx, cy + s)
        path.close()
        c.drawPath(path, fill)
    }

    private fun angerMark(c: Canvas, x: Float = 96f, y: Float = 30f) {
        fill.color = RED
        for (o in arrayOf(-1f to 0f, 1f to 0f, 0f to -1f, 0f to 1f)) {
            c.drawRoundRect(
                RectF(x - 5 + o.first * 10, y - 16 + o.second * 10,
                    x + 5 + o.first * 10, y + 16 + o.second * 10), 4f, 4f, fill
            )
        }
    }

    private fun zzz(c: Canvas, x: Float, y: Float, n: Int) {
        textPaint.color = GREY
        for (i in 0 until n) {
            textPaint.textSize = if (i == 0) 30f else 26f
            c.drawText("z", x + i * 22, y - i * 20, textPaint)
        }
    }

    private fun book(c: Canvas, x: Float = 96f, y: Float = 150f) {
        fill.color = BROWN
        c.drawRoundRect(RectF(x - 30, y - 6, x + 30, y + 30), 4f, 4f, fill)
        fill.color = CREAM
        c.drawRoundRect(RectF(x - 26, y - 2, x + 26, y + 26), 3f, 3f, fill)
        stroke.color = BROWN
        stroke.strokeWidth = 2f
        c.drawLine(x, y - 2, x, y + 26, stroke)
        stroke.color = 0xFFAAAAA0.toInt()
        for (i in 0 until 3) {
            c.drawLine(x - 20, y + 5 + i * 7, x - 6, y + 5 + i * 7, stroke)
            c.drawLine(x + 6, y + 5 + i * 7, x + 20, y + 5 + i * 7, stroke)
        }
    }

    private fun chair(c: Canvas, x: Float = 96f, seat: Float = 150f) {
        fill.color = CHAIR
        c.drawRoundRect(RectF(x - 50, seat - 70, x - 38, seat), 3f, 3f, fill)
        c.drawRoundRect(RectF(x - 50, seat - 6, x + 44, seat + 6), 4f, 4f, fill)
        c.drawRoundRect(RectF(x - 46, seat + 6, x - 36, seat + 58), 3f, 3f, fill)
        c.drawRoundRect(RectF(x + 32, seat + 6, x + 42, seat + 58), 3f, 3f, fill)
    }

    private fun steam(c: Canvas, x: Float = 96f, y: Float = 52f) {
        stroke.color = 0xFFC8C8D2.toInt()
        stroke.strokeWidth = 4f
        for (ox in floatArrayOf(-30f, 30f)) {
            c.drawArc(RectF(x + ox - 10, y - 14, x + ox + 10, y + 10), 200f, 140f, false, stroke)
        }
    }

    // ============ 16 行状态动画 ============

    /** 入口：把画布平移到 (row,col) 格子后画该帧 */
    private fun drawFrame(sheet: Canvas, row: Int, col: Int) {
        sheet.save()
        sheet.translate(col * CELL.toFloat(), row * CELL.toFloat())
        drawState(sheet, row, col)
        sheet.restore()
    }

    /** 在已定位好的格子坐标系里画一帧（row2 镜像复用 row1，不重复平移） */
    private fun drawState(sheet: Canvas, row: Int, col: Int) {
        when (row) {
            0 -> { // idle 待机呼吸
                val bob = intArrayOf(0, -2, -4, -4, -2, 0)[col]
                val sq = floatArrayOf(1f, 1f, 0.98f, 0.98f, 1f, 1f)[col]
                val p = Pose(foot = 180f + bob, squash = sq)
                if (col % 2 == 1) { p.armsL = "swing2"; p.armsR = "swing1" }
                drawKmmy(sheet, p)
            }
            1 -> { // 向右走
                val seq = arrayOf("walk1" to "walk2", "stand" to "stand", "walk2" to "walk1", "stand" to "stand")
                val (ll, lr) = seq[col % 4]
                val p = Pose(foot = 180f + if (col % 2 == 1) -3 else 0, tilt = 3f, legsL = ll, legsR = lr)
                if (col % 2 == 0) { p.armsL = "swing1"; p.armsR = "swing2" } else { p.armsL = "swing2"; p.armsR = "swing1" }
                drawKmmy(sheet, p)
            }
            2 -> { // 向左走 = 水平镜像向右走
                sheet.save()
                sheet.scale(-1f, 1f, 96f, 96f)
                drawState(sheet, 1, col)
                sheet.restore()
            }
            3 -> { // 挥手
                drawKmmy(sheet, Pose(armsR = if (col % 2 == 0) "wave" else "wave2", tilt = if (col % 2 == 1) 2f else -2f))
            }
            4 -> { // 跳
                when (col) {
                    0 -> drawKmmy(sheet, Pose(squash = 0.82f, legsL = "crouch", legsR = "crouch"))
                    1 -> drawKmmy(sheet, Pose(squash = 1.05f, legsL = "air", legsR = "air", armsL = "up", armsR = "up", foot = 196f))
                    2 -> drawKmmy(sheet, Pose(legsL = "air", legsR = "air", armsL = "up", armsR = "up", foot = 206f))
                    3 -> drawKmmy(sheet, Pose(squash = 0.88f, legsL = "crouch", legsR = "crouch"))
                    else -> drawKmmy(sheet, Pose())
                }
            }
            5 -> { // 晕（备选动作）
                val dx = intArrayOf(0, -6, 6, -5, 5, -3, 3, 0)[col]
                drawKmmy(sheet, Pose(x = 96f + dx, tilt = dx.toFloat(), kColor = GREY))
                stroke.color = WHITE
                stroke.strokeWidth = 3f
                for (ox in floatArrayOf(-18f, 18f)) {
                    sheet.drawArc(RectF(96 + dx + ox - 10, 78f, 96 + dx + ox + 10, 98f), 0f, 300f, false, stroke)
                }
            }
            6 -> { // 坐着发呆
                val tilt = intArrayOf(0, 0, 2, 0, 0, -2)[col]
                drawKmmy(sheet, Pose(foot = 190f, legsL = "sit", legsR = "sit", tilt = tilt.toFloat()))
            }
            7 -> { // 原地快跑
                drawKmmy(sheet, Pose(
                    foot = 180f + if (col % 2 == 1) -6 else 0,
                    legsL = if (col % 2 == 0) "walk1" else "walk2",
                    legsR = if (col % 2 == 0) "walk2" else "walk1",
                    armsL = if (col % 2 == 0) "swing1" else "swing2",
                    armsR = if (col % 2 == 0) "swing2" else "swing1",
                    squash = if (col % 2 == 1) 0.97f else 1f
                ))
            }
            8 -> { // 记账（捧账本 + 写字）
                drawKmmy(sheet, Pose(
                    armsL = "book", armsR = if (col % 2 == 1) "write" else "book",
                    tilt = intArrayOf(0, 0, 1, 0, 0, 1)[col].toFloat()
                ))
                book(sheet)
            }
            9 -> { // 害羞（捂脸 + 脸红）
                val tilt = intArrayOf(-4, -6, -4, -2)[col]
                drawKmmy(sheet, Pose(tilt = tilt.toFloat(), armsL = "cover", armsR = "cover"))
                blush(sheet)
            }
            10 -> { // 亲亲（前倾 + 爱心升起）
                val sizes = floatArrayOf(96f, 100f, 104f, 104f, 100f)
                val foots = floatArrayOf(180f, 178f, 176f, 176f, 178f)
                drawKmmy(sheet, Pose(size = sizes[col], foot = foots[col], armsL = "up", armsR = "up"))
                val hs = arrayOf(
                    floatArrayOf(116f, 60f, 10f), floatArrayOf(122f, 48f, 12f),
                    floatArrayOf(128f, 36f, 14f), floatArrayOf(134f, 28f, 16f), floatArrayOf(140f, 22f, 16f)
                )
                heart(sheet, hs[col][0], hs[col][1], hs[col][2])
            }
            11 -> { // 生气（抖动 + 生气符号 + 蒸汽）
                val dx = intArrayOf(0, -5, 5, -5, 5, 0)[col]
                drawKmmy(sheet, Pose(x = 96f + dx, armsL = "up", armsR = "up", kColor = 0xFFFFAAAF.toInt()))
                angerMark(sheet, y = 26f + (col % 2) * 3)
                if (col % 2 == 1) steam(sheet)
            }
            12 -> { // 睡觉（侧躺 + zzz）
                fill.color = BODY
                sheet.drawRoundRect(RectF(30f, 120f, 150f, 172f), 18f, 18f, fill)
                sheet.save()
                sheet.rotate(-90f, 90f, 146f)
                textPaint.textSize = 56f
                textPaint.color = WHITE
                val b = Rect()
                textPaint.getTextBounds("K", 0, 1, b)
                sheet.drawText("K", 90f, 146f - b.exactCenterY() + 6f, textPaint)
                sheet.restore()
                fill.color = BLUE
                sheet.drawCircle(42f, 132f, 8f, fill)
                limb(sheet, floatArrayOf(150f, 140f, 172f, 134f), 7f)
                limb(sheet, floatArrayOf(150f, 160f, 172f, 156f), 7f)
                val zy = (60 - if (col < 3) col * 6 else (5 - col) * 6).toFloat()
                zzz(sheet, 120f, zy, if (col % 2 == 0) 3 else 2)
            }
            13 -> { // 翘二郎腿坐椅子
                chair(sheet)
                drawKmmy(sheet, Pose(
                    foot = 146f, armsL = "back", armsR = "back",
                    legsL = "cross", legsR = if (col % 2 == 0) "cross" else "cross2", legsFront = true,
                    tilt = intArrayOf(-2, 0, 2, 0)[col].toFloat()
                ))
            }
            14 -> { // 凑近屏幕看（越贴越近）
                val sizes = floatArrayOf(96f, 112f, 130f, 148f, 160f)
                val dots = floatArrayOf(1f, 1.2f, 1.4f, 1.7f, 2f)
                drawKmmy(sheet, Pose(size = sizes[col], foot = 184f, armsL = "up", armsR = "up", dotScale = dots[col]))
            }
            15 -> { // 比心
                drawKmmy(sheet, Pose(armsL = "heart", armsR = "heart", tilt = intArrayOf(-2, 0, 2, 0)[col].toFloat()))
                heart(sheet, 96f, 52f, 16f)
                if (col % 2 == 1) {
                    heart(sheet, 140f, 76f, 9f, PINK)
                    heart(sheet, 52f, 80f, 9f, PINK)
                }
            }
        }
    }
}
