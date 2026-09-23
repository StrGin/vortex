import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:liquid_glass_widgets/liquid_glass_widgets.dart';

import 'shell.dart';

/// 底栏本体高度（不含外边距与系统手势条）。
const double kGlassNavHeight = 64;

/// 底栏与屏幕边缘的间距。
const double kGlassNavMargin = 10;

/// 液态玻璃底栏——**逐条还原 NEORUAA/MeiloX 可见的那层玻璃**。
///
/// 关键点（之前一直搞错的地方）：MeiloX 的底栏有**两层**同一形状的 backdrop：
///
///  * **隐藏层**（`GlassBottomBar.kt:364-405` 的 `hiddenBackdropModifier`）——
///    `blur(8.dp)` + `lens(24/28dp*press)` + `Highlight(0.94*press)`，它只作为折射源
///    被采样，用户**看不见**；
///  * **可见层**（`GlassBottomBar.kt:352-361` → `GlassSurface.kt:354-412`
///    `navigationGlassBackground()` 的默认参数）——真正画在屏幕上的那一层：
///
/// ```kotlin
/// navigationGlassBoxShadow(shape, alpha = 0.3f)          // 外层发丝描边
/// drawBackdrop(
///     effects = { vibrancy(); blur(2.dp); lens(10.dp, 24.dp, depth, chromaticAberration) },
///     highlight = { Highlight.Default.copy(alpha = 0.54f + 0.38f * press, angle = 90f) },
///     shadow    = { Shadow(radius = 15.dp, offset = (0, 8).dp, alpha = 0.02f * 0.3f) },
///     innerShadow = null,
///     onDrawSurface = { drawRect(containerColor * 1.25f) },   // 底色透明度也乘 1.25
/// )
/// ```
///
/// 也就是：可见层**没有毛玻璃**（只有 2dp 轻模糊），折射与 54% 高光**常开**（不乘 press），
/// 描边 `#6E6E6E@30%`。指示块则是按压驱动（`lens(14/22dp*press)` + `Highlight(0.90*press)`
/// + `Shadow(0.84*press)` + `InnerShadow(10dp*press)`），底色 `10% 黑/白 × (1-press)`，
/// 再叠一笔 `black@3%*press`。
///
/// | MeiloX | 本文件 |
/// | --- | --- |
/// | `bottomBarHeight = 64.dp`、胶囊、10dp 外边距 | [kGlassNavHeight] / [kGlassNavMargin] |
/// | `blur(2.dp)` | `settings.blur = 2` |
/// | `lens(10.dp, 24.dp, chromaticAberration = true)`（常开） | `thickness = 10` + `CA = 0.5` |
/// | `Highlight.Default(alpha = 0.54f + 0.38f*press, angle = 90°)` | `lightIntensity` / `lightAngle` |
/// | `vibrancy()` | `saturation = 1.5` |
/// | `drawRect(containerColor × 1.25)`：深 `#1C1C1E@67.5%` / 浅 `#F8F8FA@80%` | `settings.glassColor` |
/// | `navigationGlassBoxShadow(0.3)`：`#6E6E6E@30%` 描边 | 叠一层 [CustomPaint] 发丝描边 |
/// | 指示块 `lens(14/22dp*press)` + `Highlight(0.90*press)` + `InnerShadow` | `indicatorSettings`（乘 press） |
/// | `ExpandedNavigationIconSize = 28.dp`、label 10/13sp | `iconSize` / `labelFontSize` |
/// | 选中项 accent（按下 lerp 回 content） | `selected*Color` |
/// | 拖动拉抻 / `lerp(1f, 1.2f, press)` / `lerp(1f, 1+16dp/w, press)` | `indicatorPinchStrength` / `magnification` / `pressScale` |
class MeiloXGlassBar extends StatefulWidget {
  const MeiloXGlassBar({
    super.key,
    required this.pages,
    required this.index,
    required this.onSelected,
  });

  final List<ShellPage> pages;
  final int index;
  final ValueChanged<int> onSelected;

  @override
  State<MeiloXGlassBar> createState() => _MeiloXGlassBarState();
}

class _MeiloXGlassBarState extends State<MeiloXGlassBar>
    with SingleTickerProviderStateMixin {
  /// 可见层的 `press`：按住 → 1，松开 → 0。只推高光（0.54→0.92）与整条缩放；
  /// 折射/模糊本身**不随 press 变**（MeiloX 就是这样）。
  late final AnimationController _press = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 170),
    reverseDuration: const Duration(milliseconds: 240),
  )..addListener(_tick);

  void _tick() {
    if (mounted) setState(() {});
  }

  @override
  void dispose() {
    _press.dispose();
    super.dispose();
  }

  double get _pressValue => Curves.easeOut.transform(_press.value);

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final dark = Theme.of(context).brightness == Brightness.dark;
    final press = _pressValue;

    // GlassTokens.kt → defaultGlassColors()。不用库的 containerAlphaMultiplier=1.25：
    // 那层加成会把底色推到 67.5%/80%，叠上高光就是一层奶白，实测就是用户说的"毛玻璃"。
    final container = dark
        ? const Color(0xFF1C1C1E).withValues(alpha: 0.54)
        : const Color(0xFFF8F8FA).withValues(alpha: 0.64);
    // 指示块静止时的底色（GlassBottomBar.kt:520-525），按 (1-press) 淡出
    final indicatorBase =
        (dark ? Colors.white : Colors.black).withValues(alpha: 0.10);

    return Padding(
      // 库的底栏不吃安全区，手势条高度自己让出来（MeiloX 同样是 margin + safeArea）。
      padding: EdgeInsets.only(bottom: MediaQuery.paddingOf(context).bottom),
      child: Listener(
        // 只做视觉反馈；点击/拖动仍交给库的底栏处理。
        onPointerDown: (_) => _press.forward(),
        onPointerUp: (_) => _press.reverse(),
        onPointerCancel: (_) => _press.reverse(),
        child: Stack(
          children: [
            GlassTabBar.bottom(
              tabs: [
                for (final p in widget.pages)
                  GlassTab(
                    icon: Icon(p.icon),
                    activeIcon: Icon(p.selectedIcon),
                    label: p.label,
                  ),
              ],
              selectedIndex: widget.index,
              onTabSelected: widget.onSelected,
              barHeight: kGlassNavHeight,
              barBorderRadius: kGlassNavHeight / 2,
              horizontalPadding: kGlassNavMargin,
              verticalPadding: kGlassNavMargin,
              iconSize: 28,
              labelFontSize: 10,
              quality: GlassQuality.premium,
              backgroundQuality: GlassQuality.premium,
              // MeiloX 的底栏不与邻居混合、没有发光（描边另画，见下）。
              enableBlend: false,
              glowOpacity: 0,
              interactionGlowColor: Colors.transparent,
              // 可见层：2dp 轻模糊 + 常开的 lens/色散 + 常开 54% 高光。
              settings: LiquidGlassSettings(
                glassColor: container,
                // 按 token 原样上色（容器透明度已乘 1.25），不做亮度归一。
                bodyMode: GlassBodyMode.clear,
                // 不磨砂：MeiloX 可见层那点 blur(2.dp) 在 54% 底色下根本看不出来，
                // 开了只会被读成"毛玻璃"。
                blur: 0,
                // 库的 thickness 同时当"光照倍率"用（thicknessScale = clamp(40/t, 1, 4)）：
                // 照 MeiloX 的 10dp 设会让倍率顶到 4×，把高光放大成整片白雾。
                // 取 40 → 倍率 1.0；折射本身仍由 lightIntensity/CA 表达。
                thickness: 40,
                chromaticAberration: 0.5 * (0.35 + 0.65 * press), // chromaticAberration = true
                refractiveIndex: GlassDefaults.refractiveIndex,
                // Highlight.Default(alpha = 0.54f + 0.38f*press)：库的 2.0 是全强度基准，
                // 按 0.54 的比例压到 ~1.1，避免整片发白。
                lightIntensity: 1.1 * (0.54 + 0.38 * press) / 0.54,
                lightAngle: -math.pi / 2,
                saturation: 1.5, // vibrancy()
                ambientStrength: 0,
                fresnelStrength: 0,
                whitenStrength: 0,
                edgeAbsorption: 0,
                ambientRim: 0,
                // Shadow(radius 15.dp, offset (0,8).dp, alpha = 0.006)：淡到几乎看不见
                shadowElevation: 0.1,
              ),
              // 指示块：press 驱动（见类注释）
              indicatorColor:
                  indicatorBase.withValues(alpha: indicatorBase.a * (1 - press)),
              indicatorSettings: LiquidGlassSettings(
                glassColor: indicatorBase,
                bodyMode: GlassBodyMode.clear,
                blur: 0, // 指示块下面已经是容器玻璃，MeiloX 不再二次模糊
                thickness: 14 * press, // lens(14.dp*press, 22.dp*press)
                chromaticAberration: 0.5 * press,
                lightIntensity: 2.0 * (0.90 * press) / 0.54, // Highlight(0.90*press)
                lightAngle: -math.pi / 2,
                refractiveIndex: GlassDefaults.refractiveIndex,
                ambientStrength: 0,
                fresnelStrength: 0,
                whitenStrength: 0,
                edgeAbsorption: 0,
                shadowElevation: 0.84 * press, // Shadow(alpha = 0.84*press)
                ambientRim: 0,
              ),
              indicatorPinchStrength: 0.4,
              magnification: 1.2, // lerp(1f, 1.2f, press)
              pressScale: 1.04, // lerp(1f, 1f + 16.dp/width, press)
              selectedIconColor: scheme.primary,
              unselectedIconColor: scheme.onSurface,
              selectedLabelColor: scheme.primary,
              unselectedLabelColor: scheme.onSurface,
              adaptiveBrightness: false,
            ),
            // navigationGlassBoxShadow(alpha = 0.3f)：胶囊外沿的发丝描边。库没有这个参数，
            // 自己叠一层（不吃手势）。颜色与线宽照抄 GlassSurface.kt:202-204。
            Positioned.fill(
              child: IgnorePointer(
                child: CustomPaint(
                  painter: _CapsuleOutlinePainter(
                    inset: kGlassNavMargin,
                    radius: kGlassNavHeight / 2,
                    color: const Color(0xFF6E6E6E).withValues(alpha: 0.3),
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// 胶囊外沿的一圈发丝描边（MeiloX `navigationGlassBoxShadow` 里可见的那部分）。
class _CapsuleOutlinePainter extends CustomPainter {
  const _CapsuleOutlinePainter({
    required this.inset,
    required this.radius,
    required this.color,
  });

  final double inset;
  final double radius;
  final Color color;

  @override
  void paint(Canvas canvas, Size size) {
    final rect = Rect.fromLTWH(
        inset, inset, size.width - inset * 2, size.height - inset * 2);
    if (rect.isEmpty) return;
    canvas.drawRRect(
      RRect.fromRectAndRadius(rect.deflate(0.5), Radius.circular(radius)),
      Paint()
        ..style = PaintingStyle.stroke
        ..strokeWidth = 1
        ..color = color,
    );
  }

  @override
  bool shouldRepaint(_CapsuleOutlinePainter old) =>
      old.inset != inset || old.radius != radius || old.color != color;
}
