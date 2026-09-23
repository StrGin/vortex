import 'dart:ui' as ui;

import 'package:flutter/foundation.dart';

import 'vela_grpc.dart';

/// Latest emulator framebuffer, kept out of the global app state: a 466x466 RGBA
/// frame is ~860 KB and arrives ~10x/s, so only WatchView listens to this.
class FrameStore extends ChangeNotifier {
  ui.Image? _image;
  int _width = 0;
  int _height = 0;
  int _revision = 0;
  int _lastSeq = -1;
  int _frames = 0;
  final List<int> _stamps = <int>[];
  final List<int> _recvStamps = <int>[];

  ui.Image? get image => _image;
  int get width => _width;
  int get height => _height;
  int get revision => _revision;
  int get frames => _frames;
  bool get hasFrame => _image != null && _width > 0 && _height > 0;

  /// 显示帧率：过去 1 秒里**解码上屏**的帧数（时间戳在 publish 时记，不在 getter 里）。
  double get fps => _rate(_stamps);

  /// 引擎推送帧率：过去 1 秒里**从引擎收到**的帧数（含被跳过的重复帧）。
  /// 两者对照就能看出"引擎推得多快"和"我们真正画了多少"。
  double get receivedFps => _rate(_recvStamps);

  static double _rate(List<int> stamps) {
    final now = DateTime.now().microsecondsSinceEpoch;
    while (stamps.isNotEmpty && now - stamps.first > 1000000) {
      stamps.removeAt(0);
    }
    return stamps.length.toDouble();
  }

  /// 每收到一帧（无论是否被跳过/是否解码）调用一次。
  void noteReceived() => _recvStamps.add(DateTime.now().microsecondsSinceEpoch);

  /// fps / 帧数这类统计**单独**通知，且最多 2Hz：它们以前挂在帧通知上，
  /// 等于每帧都要重建一次那排状态 chip。
  final ChangeNotifier stats = ChangeNotifier();
  int _lastStatsAt = 0;

  /// 解码一帧的平均耗时（指数滑动平均，ms）。它和"引擎推送速率"一起看就能分清
  /// 瓶颈：解码 ~几毫秒 = 引擎/客机产帧慢；解码 ~几十毫秒 = 是我们拖住了推送
  /// （gRPC 流控会把引擎压到我们的消费速率）。
  double decodeMs = 0;
  int _decodeStart = 0;

  void _notifyStats() {
    final now = DateTime.now().millisecondsSinceEpoch;
    if (now - _lastStatsAt < 500) return;
    _lastStatsAt = now;
    stats.notifyListeners();
  }

  void publish(Frame f, {void Function()? onDecoded}) {
    if (f.width <= 0 || f.height <= 0) {
      onDecoded?.call();
      return;
    }
    final px = f.width * f.height;
    if (f.rgba.lengthInBytes != px * 4) {
      // 只认 RGBA8888（我们请求的就是它）。别的长度说明引擎换了格式，
      // 这时候喂给 decodeImageFromPixels 会画花，直接跳过更安全。
      // 注意 onDecoded 一定要回调，否则流式侧的背压标志会一直卡住。
      onDecoded?.call();
      return;
    }
    _lastSeq = f.seq;
    _frames++;
    _stamps.add(DateTime.now().microsecondsSinceEpoch);
    _decodeStart = DateTime.now().microsecondsSinceEpoch;
    // decodeImageFromPixels is async; the notify happens when the texture exists,
    // so a slow decode never blocks the pump (the pump guards on _inFlight).
    ui.decodeImageFromPixels(
        f.rgba, f.width, f.height, ui.PixelFormat.rgba8888, (img) {
      _image?.dispose();
      _image = img;
      _width = f.width;
      _height = f.height;
      _revision++;
      final spent =
          (DateTime.now().microsecondsSinceEpoch - _decodeStart) / 1000.0;
      decodeMs = decodeMs == 0 ? spent : decodeMs * 0.8 + spent * 0.2;
      notifyListeners();
      _notifyStats();
      onDecoded?.call();
    });
  }

  int get lastSeq => _lastSeq;

  void clear() {
    _image?.dispose();
    _image = null;
    _width = _height = 0;
    _lastSeq = -1;
    _revision++;
    notifyListeners();
  }

  @override
  void dispose() {
    _image?.dispose();
    _image = null;
    super.dispose();
  }
}
