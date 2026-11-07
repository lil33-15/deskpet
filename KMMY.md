# 小小kmmy 桌宠

基于 deskpet 的自用定制版（Android 桌面悬浮宠物），形象来自手绘设计：黑色圆角方块 + 白色 K + 蓝色点点 + 火柴棍四肢。

## 互动暗号
- 单击 → 害羞（捂脸脸红）
- 双击 → 亲亲（冒爱心）
- 三连击 → 生气（发抖冒火）
- 长按拖拽移动位置；双指捏合缩放

## 自主行为（待机 30 秒后随机整活）
到处走 / 蹦跳 / 原地快跑 / 坐着发呆 / 捧账本记账 / 侧躺睡觉 / 翘二郎腿坐椅子 / 凑近屏幕看人 / 比心

## 出包
推送到 main 分支后 GitHub Actions 自动构建，约 5~8 分钟：
Actions → 最新一次运行 → 页面底部 Artifacts → 下载 `app-debug`，解压得到 app-debug.apk，传到手机安装即可（minSdk 26，Android 8.0+）。

## 技术要点
- 精灵图不落二进制：首次启动时由 `KmmySpritesheetRenderer` 用 Canvas 现场绘制（8列×16行，每格 192×192）
- 坐标设计稿：`tools/generate_kmmy_spritesheet.py`（Python/PIL 版，用于本地预览调参）
- 每只宠物可通过 pet.json 的 `rows` 字段声明自己的精灵图行数，老素材默认 9 行不受影响
