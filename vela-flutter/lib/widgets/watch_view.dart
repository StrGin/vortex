import 'dart:async';
import 'dart:ui' as ui;

import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';

import '../models/vela_models.dart';
import '../state/app_state.dart';
import '../state/vela_bridge.dart';
import '../theme/app_theme.dart';

/// Emulator framebuffer inside the device silhouette (circle / pill / rect),
/// with gestures mapped from view pixels to device pixels.
///
/// The watch claims every pointer that lands on it ([EagerGestureRecognizer]
/// beats the page's scrollable in the gesture arena), so dragging the dial never
/// also scrolls the page behind it.
class WatchView extends StatefulWidget {
  final DeviceProfile? profile;
  const WatchView({super.key, this.profile});

  @override
  State<WatchView> createState() => _WatchViewState();
}

class _WatchViewState extends State<WatchView> {
  /// Hold this long without moving and the guest sees a long press.
  static const Duration _longPressAfter = Duration(milliseconds: 550);

  /// Movement (logical px) that turns a press into a drag.
  static const double _dragSlop = 12.0;

  Timer? _longPressTimer;
  Offset? _downLocal;
  bool _longPressFired = false;
  bool _dragging = false;
  late final _InputQueue _input = _InputQueue(AppScope.of(context).bridge);

  @override
  void dispose() {
    _longPressTimer?.cancel();
    super.dispose();
  }

  void _cancelLongPress() {
    _longPressTimer?.cancel();
    _longPressTimer = null;
  }

  void _onDown(PointerDownEvent e, _Mapper m, VelaBridge bridge) {
    _cancelLongPress();
    _downLocal = e.localPosition;
    _longPressFired = false;
    _dragging = false;
    final at = m.toDevice(e.localPosition);
    _longPressTimer = Timer(_longPressAfter, () {
      if (!mounted || _dragging || _longPressFired || _downLocal != e.localPosition) {
        return;
      }
      _longPressFired = true;
      bridge.gesture(() => bridge.grpc.longPress(at.dx.toInt(), at.dy.toInt()));
    });
  }

  void _onMove(PointerMoveEvent e, _Mapper m) {
    final down = _downLocal;
    if (down == null) return;
    if (!_dragging) {
      if ((e.localPosition - down).distance <= _dragSlop) return;
      _dragging = true;
      _cancelLongPress();
      // Press where the finger landed, then let the pointer-follow stream move it.
      _input.press(m.toDevice(down));
    }
    _input.move(m.toDevice(e.localPosition));
  }

  // 关于"右滑返回"：实测结论是**一律放行**最合适——
  // * miwear 5.0 客机（手表）自带边缘右滑返回，滑动原样送过去就能退；
  // * `GoBack`/`Back` 按键两套镜像都不认（只有 `GoHome` 认），注入按键没用；
  // * pre-4.0（手环）根本没有系统返回，客机里 kill/pkill 也杀不掉 vapp 应用，
  //   拦下来只会白吞一次滑动（不如让应用自己处理）。
  // s3/s4 真机固件自带该手势，我们同样不碰。

  void _onUp(PointerUpEvent e, _Mapper m, VelaBridge bridge) {
    _cancelLongPress();
    final down = _downLocal;
    _downLocal = null;
    if (down == null) return;
    if (_dragging) {
      _dragging = false;
      _input.release(m.toDevice(e.localPosition));
      unawaited(bridge.forceFrame());
      return;
    }
    if (_longPressFired) return;
    final at = m.toDevice(down);
    bridge.gesture(() => bridge.grpc.tap(at.dx.toInt(), at.dy.toInt()));
  }

  void _onCancel() {
    _cancelLongPress();
    final down = _downLocal;
    _downLocal = null;
    if (_dragging) {
      _dragging = false;
      if (down != null) _input.release(down);
    }
  }

  @override
  Widget build(BuildContext context) {
    final state = AppScope.of(context);
    final bridge = state.bridge;
    final frames = bridge.frames;
    final profile = widget.profile;
    // 宽高比以**实际帧**为准：画的时候是把帧硬拉满这个盒子（drawImageRect），
    // 所以一旦"设备档案尺寸"和引擎真正吐出的分辨率不一致（例如 192x490 的手环
    // 档案配到 466x466 的帧），画面就会被拉伸。帧还没来时退回档案尺寸。
    final fw = frames.width;
    final fh = frames.height;
    final pw = fw > 0
        ? fw
        : ((profile?.width ?? 0) > 0 ? profile!.width : 466);
    final ph = fh > 0
        ? fh
        : ((profile?.height ?? 0) > 0 ? profile!.height : 466);

    return Center(
      child: LayoutBuilder(builder: (context, box) {
        final maxW = box.maxWidth.isFinite ? box.maxWidth : 400.0;
        final maxH = box.maxHeight.isFinite ? box.maxHeight : 400.0;
        final scale = [maxW / pw, maxH / ph].reduce((a, b) => a < b ? a : b);
        final w = pw * scale;
        final h = ph * scale;
        final radius = profile != null && profile.isCircle
            ? w / 2
            : (profile?.cornerRadius ?? 0) > 0
                ? profile!.cornerRadius.toDouble() * scale
                : 24.0 * scale;
        final mapper = _Mapper(w, h, pw, ph);

        final scheme = Theme.of(context).colorScheme;
        final running = bridge.status.running;
        final connected = bridge.grpc.connected;

        return Container(
          width: w + 10,
          height: h + 10,
          padding: const EdgeInsets.all(5),
          decoration: BoxDecoration(
            // 外壳：细描边 + 极轻投影（MD3 的卡片式处理，不做拟物表带）。
            color: scheme.surfaceContainerHighest,
            borderRadius: BorderRadius.circular(
              (radius.clamp(0.0, h / 2) + 5),
            ),
            border: Border.all(color: scheme.outlineVariant, width: 1),
            boxShadow: [
              BoxShadow(
                color: Colors.black.withValues(alpha: 0.22),
                blurRadius: 18,
                offset: const Offset(0, 8),
              ),
            ],
          ),
          child: SizedBox(
          width: w,
          height: h,
          child: RawGestureDetector(
            behavior: HitTestBehavior.opaque,
            gestures: {
              EagerGestureRecognizer:
                  GestureRecognizerFactoryWithHandlers<EagerGestureRecognizer>(
                EagerGestureRecognizer.new,
                (r) {},
              ),
            },
            child: Listener(
              behavior: HitTestBehavior.opaque,
              onPointerDown: (e) => _onDown(e, mapper, bridge),
              onPointerMove: (e) => _onMove(e, mapper),
              onPointerUp: (e) => _onUp(e, mapper, bridge),
              onPointerCancel: (_) => _onCancel(),
              child: ClipRRect(
                borderRadius: BorderRadius.circular(radius.clamp(0.0, h / 2)),
                child: ColoredBox(
                  color: Colors.black,
                  child: AnimatedBuilder(
                    animation: frames,
                    builder: (context, _) {
                      final ui.Image? img = frames.image;
                      if (img == null) {
                        return Center(
                          child: Padding(
                            padding: const EdgeInsets.all(Insets.lg),
                            child: Column(
                              mainAxisSize: MainAxisSize.min,
                              children: [
                                Icon(
                                  running
                                      ? Icons.hourglass_empty_rounded
                                      : Icons.watch_off_outlined,
                                  size: 30,
                                  color: Colors.white38,
                                ),
                                const SizedBox(height: Insets.sm),
                                Text(
                                  running
                                      ? (connected
                                          ? '已连接，等第一帧…'
                                          : '引擎在跑，正在等 gRPC 就绪…')
                                      : '还没有画面\n启动引擎，或连接远端模拟器',
                                  textAlign: TextAlign.center,
                                  style: const TextStyle(
                                    color: Colors.white54,
                                    height: 1.4,
                                  ),
                                ),
                              ],
                            ),
                          ),
                        );
                      }
                      // 首帧淡入：从"什么都没有"到出图的这一下不再硬切。
                      return AnimatedSwitcher(
                        duration: Motion.slow,
                        child: CustomPaint(
                          key: const ValueKey<String>('frame'),
                          size: Size.infinite,
                          painter: _FramePainter(img),
                        ),
                      );
                    },
                  ),
                ),
              ),
            ),
          ),
        ),
        );
      }),
    );
  }
}

/// View pixels -> device pixels for one laid-out frame.
class _Mapper {
  const _Mapper(this.w, this.h, this.pw, this.ph);
  final double w;
  final double h;
  final int pw;
  final int ph;

  Offset toDevice(Offset local) => Offset(
        (local.dx / w * pw).round().clamp(0, pw - 1).toDouble(),
        (local.dy / h * ph).round().clamp(0, ph - 1).toDouble(),
      );
}

/// Serialises press/move/release on one gRPC stream and coalesces moves: while a
/// move is in flight only the newest position is kept, so a fast drag cannot
/// queue up behind the guest's (slow) frame time. The release always lands after
/// every queued move.
class _InputQueue {
  _InputQueue(this.bridge);
  final VelaBridge bridge;

  final List<Offset> _moves = <Offset>[];
  Offset? _releaseAt;
  bool _busy = false;

  void press(Offset p) {
    _moves.add(p);
    _drain();
  }

  void move(Offset p) {
    _moves.add(p);
    _drain();
  }

  void release(Offset p) {
    _releaseAt = p;
    _drain();
  }

  Future<void> _drain() async {
    if (_busy) return;
    _busy = true;
    try {
      while (_moves.isNotEmpty || _releaseAt != null) {
        if (_moves.isNotEmpty) {
          final p = _moves.removeAt(0);
          _moves.clear(); // only the latest position matters
          try {
            await bridge.grpc.mouse(p.dx.toInt(), p.dy.toInt(), 1);
          } catch (e) {
            bridge.inputError = '$e';
          }
          continue;
        }
        final p = _releaseAt!;
        _releaseAt = null;
        try {
          await bridge.grpc.mouse(p.dx.toInt(), p.dy.toInt(), 0);
        } catch (e) {
          bridge.inputError = '$e';
        }
      }
    } finally {
      _busy = false;
    }
  }
}

class _FramePainter extends CustomPainter {
  final ui.Image image;
  _FramePainter(this.image);

  @override
  void paint(Canvas canvas, Size size) {
    final src = Rect.fromLTWH(0, 0, image.width.toDouble(), image.height.toDouble());
    canvas.drawImageRect(
        // low：表盘是缩小显示，medium 的双线性每帧都做，换成 low 省 GPU
        // （466→~330 这种比例肉眼看不出差别）。
        image, src, Offset.zero & size, Paint()..filterQuality = FilterQuality.low);
  }

  @override
  bool shouldRepaint(_FramePainter old) =>
      old.image != image ||
      old.image.width != image.width ||
      old.image.height != image.height;
}
