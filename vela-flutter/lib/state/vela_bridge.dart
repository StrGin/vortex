import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/services.dart';

import '../models/vela_models.dart';
import 'frame_store.dart';
import 'vela_grpc.dart';

/// Talks to the native engine host (`com.velasim.app`) and to the running
/// emulator's gRPC endpoint. On desktop, or whenever the plugin is not
/// registered, every native call throws MissingPluginException and the bridge
/// falls back to "remote endpoint" mode: the UI drives an emulator that someone
/// else started (e.g. the official AIoT IDE on the LAN).
class VelaBridge {
  static const control = MethodChannel('velasim/control');
  static const events = EventChannel('velasim/events');

  final FrameStore frames = FrameStore();
  final VelaGrpc grpc = VelaGrpc();

  bool nativeAvailable = true;
  String? lastNativeError;

  List<DeviceProfile> devices = const [];
  List<SystemImage> images = const [];
  List<String> avds = const [];
  EngineStatus status = const EngineStatus();
  NativeRuntimeStatus native = const NativeRuntimeStatus();
  ToolchainStatus toolchain = const ToolchainStatus();
  final List<String> logLines = <String>[];

  Timer? _pump;
  /// 取帧间隔：默认 40ms（≈25fps）。客机自己能跑 20-40fps，间隔太大就是它在等我们。
  int frameIntervalMs = 40;
  /// Device size from the selected profile. This build leaves the deprecated
  /// Image.width/height at 0, and a non-square framebuffer (bands are 192x490)
  /// cannot be recovered from the byte count alone.
  int expectedWidth = 0;
  int expectedHeight = 0;
  bool pumpRunning = false;
  String? pumpError;

  StreamSubscription? _eventsSub;

  void listenEvents(void Function(Map<String, Object?>) onEvent) {
    _eventsSub?.cancel();
    _eventsSub = events.receiveBroadcastStream().listen((raw) {
      try {
        final m = raw is String ? jsonDecode(raw) : raw;
        if (m is Map) onEvent(m.cast<String, Object?>());
      } catch (_) {}
    }, onError: (_) {});
  }

  Future<T?> _call<T>(String method, [Map<String, Object?>? args]) async {
    try {
      final r = await control.invokeMethod<Object?>(method, args);
      nativeAvailable = true;
      lastNativeError = null;
      return r is T ? r : null;
    } on MissingPluginException catch (e) {
      nativeAvailable = false;
      lastNativeError = 'native host unavailable: ${e.message}';
      return null;
    } on PlatformException catch (e) {
      lastNativeError = '${e.code}: ${e.message}';
      rethrow;
    }
  }

  Future<void> refreshAll() async {
    await Future.wait([refreshDevices(), refreshImages(), refreshAvds(), refreshStatus()]);
  }

  /// Android 12+ wallpaper accent, used to seed the color scheme. Null on older
  /// releases, on desktop, and whenever the native host is absent.
  Future<Color?> systemAccent() async {
    final raw = await _call<Map<Object?, Object?>>('systemAccent');
    if (raw == null) return null;
    final r = (raw['r'] as num?)?.toInt();
    final g = (raw['g'] as num?)?.toInt();
    final b = (raw['b'] as num?)?.toInt();
    if (r == null || g == null || b == null) return null;
    return Color.fromARGB(255, r, g, b);
  }

  Future<void> refreshDevices() async {
    final r = await _call<List<Object?>>('listDevices');
    if (r != null) {
      devices = r
          .whereType<Map<Object?, Object?>>()
          .map(DeviceProfile.fromMap)
          .toList(growable: false);
    }
  }

  /// `listImages` returns one flat list: installed dirs first (with `sizeBytes`
  /// and `state`), then the catalogue entries that are still missing. A type can
  /// only appear once, so no merging is needed here.
  Future<void> refreshImages() async {
    final r = await _call<List<Object?>>('listImages');
    if (r == null) return;
    images = r
        .whereType<Map<Object?, Object?>>()
        .map(SystemImage.fromMap)
        .toList(growable: false);
  }

  Future<void> refreshAvds() async {
    final r = await _call<List<Object?>>('listAvds');
    if (r == null) return;
    avds = r
        .whereType<Map<Object?, Object?>>()
        .map((m) => '${m['avdId'] ?? m['skin'] ?? ''}')
        .where((id) => id.isNotEmpty)
        .toList(growable: false);
  }

  Future<void> refreshStatus() async {
    final r = await _call<Map<Object?, Object?>>('engineStatus');
    if (r != null) status = EngineStatus.fromMap(r);
  }

  /// 界面偏好：引导页是否看过。
  Future<bool> onboardingDone() async {
    final r = await _call<Map<Object?, Object?>>('uiPrefs');
    return r?['onboardingDone'] == true;
  }

  Future<void> setUiPref(String key, String value) =>
      _call('setUiPref', {'key': key, 'value': value});

  /// 读回某个界面偏好（没有就是空串）。
  Future<String> uiPref(String key) async {
    final r = await _call<Map<Object?, Object?>>('uiPrefs');
    return '${r?[key] ?? ''}';
  }

  /// 可执行负载状态（jniLibs 里的 loader 与 stub 是否就位）。
  Future<NativeRuntimeStatus?> refreshNative() async {
    final r = await _call<Map<Object?, Object?>>('nativeStatus');
    if (r == null) return null;
    native = NativeRuntimeStatus.fromMap(r);
    return native;
  }

  /// node / aiot-toolkit availability.
  Future<ToolchainStatus?> refreshToolchain() async {
    final r = await _call<Map<Object?, Object?>>('toolchainStatus');
    if (r == null) return null;
    toolchain = ToolchainStatus.fromMap(r);
    return toolchain;
  }

  /// Opens the system page for this app: "All files access" while it is missing
  /// (needed to import projects from /sdcard), otherwise the app details page.
  Future<void> openSettings() => _call('openSettings').then((_) => refreshToolchain());

  /// Feeds an event payload into the cached device list.
  void applyDeviceEvent(Map<String, Object?> e) {
    final list = e['devices'];
    if (list is! List) return;
    devices = list
        .whereType<Map<Object?, Object?>>()
        .map(DeviceProfile.fromMap)
        .toList(growable: false);
  }

  /// Feeds an event payload into the cached image list.
  void applyImageEvent(Map<String, Object?> e) {
    final list = e['images'];
    if (list is! List) return;
    images = list
        .whereType<Map<Object?, Object?>>()
        .map(SystemImage.fromMap)
        .toList(growable: false);
  }

  /// Creates the AVD and returns the native result, whose `problems` list is the
  /// only record of a half-created device (image not downloaded yet, skin gone).
  Future<Map<Object?, Object?>?> createAvd(String avdId) async {
    final r = await _call<Map<Object?, Object?>>('createAvd', {'avdId': avdId});
    await refreshAvds();
    return r;
  }

  Future<void> deleteAvd(String avdId) => _call('deleteAvd', {'avdId': avdId}).then((_) => refreshAvds());
  Future<void> downloadImage(String type) => _call('downloadImage', {'type': type});
  Future<void> deleteImage(String type) => _call('deleteImage', {'type': type}).then((_) => refreshImages());
  Future<void> stopEngine() async {
    await _call('stopEngine');
    await refreshStatus();
  }

  /// Launches the engine and returns the native launch result.
  ///
  /// The host answers even when the launch failed (`mode: error|none`, `error`,
  /// `problems`), so callers must read the result -- ignoring it turns a broken
  /// launch into a silent "connected" and a frame-less screen.
  Future<Map<Object?, Object?>?> startEngine(String avdId,
      {int? grpcPort,
      bool verbose = false,
      bool noAudio = false,
      String? extraArgs}) async {
    await _call('createAvd', {'avdId': avdId});
    final r = await _call<Map<Object?, Object?>>('startEngine', {
      'avdId': avdId,
      if (grpcPort != null) 'grpcPort': grpcPort,
      'verbose': verbose,
      if (noAudio) 'noAudio': true,
      if (extraArgs != null && extraArgs.isNotEmpty) 'extraArgs': extraArgs,
    });
    await refreshStatus();
    return r;
  }

  Future<List<String>> fetchLogs({int limit = 400}) async {
    final r = await _call<List<Object?>>('getLogs', {'limit': limit});
    if (r == null) return const [];
    return r.map((e) => '$e').toList(growable: false);
  }

  Future<List<String>> fetchSerialLog({int limit = 400}) async {
    final r = await _call<List<Object?>>('getSerialLog', {'limit': limit});
    if (r == null) return const [];
    return r.map((e) => '$e').toList(growable: false);
  }

  Future<void> clearLogs() => _call('clearLogs');

  /// The native paths map; `null` when no host is registered.
  Future<Map<Object?, Object?>?> paths() => _call<Map<Object?, Object?>>('getPaths');

  void addLog(String line) {
    logLines.add(line);
    if (logLines.length > 2000) logLines.removeRange(0, logLines.length - 2000);
  }

  /// Connect to the emulator's gRPC endpoint. `host` defaults to loopback, which is
  /// what the on-device engine binds to; a LAN address enables desktop testing
  /// against the official IDE's emulator.
  Future<String> connect({String host = '127.0.0.1', int? port}) async {
    final p = port ?? (status.grpcPort > 0 ? status.grpcPort : 8554);
    final v = await grpc.connect(host, p);
    startPump();
    return v;
  }

  Future<void> disconnect() async {
    stopPump();
    await grpc.close();
    frames.clear();
  }

  /// 取帧方式：true = 引擎**流式推帧**（`streamScreenshot`，官方 AIoT IDE 用的就是它）、
  /// false = 定时轮询 `getScreenshot`。流式是默认；某个 AVD 上不出帧时切回轮询兜底。
  bool streamFrames = true;

  /// 用户切换取帧方式（会记住）。切换后立刻重启取帧循环。
  void setStreamFrames(bool v) {
    if (streamFrames == v) return;
    streamFrames = v;
    setUiPref('stream_frames', v ? '1' : '0');
    if (pumpRunning) startPump();
  }

  /// 流式模式下的最小帧间隔：**默认 0（不节流）**——帧由引擎推，我们只做
  /// "上一帧解码完再收下一帧"的背压。之前沿用轮询的 40ms 设置当节流，等于把
  /// 帧率钉死在 25fps（真机上看到的就是 20 多帧）。
  int get _minStreamGapMs => 0;

  void Function()? _streamCancel;
  int _lastStreamAt = 0;
  bool _streamDecoding = false;

  void startPump() {
    stopPump();
    pumpRunning = true;
    pumpError = null;
    if (streamFrames) {
      _startStream();
      return;
    }
    _pump = Timer.periodic(Duration(milliseconds: frameIntervalMs), (_) => _pumpOnce());
  }

  Future<void> _startStream() async {
    try {
      _streamCancel = await grpc.streamScreenshot(
        onFrame: _onStreamFrame,
        onError: (e) {
          pumpError = '$e';
          // 流断了就退回轮询，别让表盘黑着。
          if (pumpRunning && streamFrames) {
            streamFrames = false;
            startPump();
          }
        },
      );
      if (!pumpRunning) {
        // 订阅回来时已经断开了。
        _streamCancel?.call();
        _streamCancel = null;
      }
    } catch (e) {
      pumpError = '$e';
      streamFrames = false;
      if (pumpRunning) startPump();
    }
  }

  /// 上一帧的采样指纹（引擎不给 seq 时用来识别"这帧跟上一帧一样"）。
  int _lastSample = 0;
  bool _hasSample = false;

  /// 流式回调：帧是引擎推的，这里只做背压 + 去重 + 丢旧帧。
  void _onStreamFrame(Frame f) {
    if (!pumpRunning) return;
    frames.noteReceived();
    final now = DateTime.now().millisecondsSinceEpoch;
    if (_minStreamGapMs > 0 && now - _lastStreamAt < _minStreamGapMs) return;
    if (_streamDecoding) return; // 上一帧还没解码完，丢掉旧帧
    var frame = f;
    if (frame.width <= 0 || frame.height <= 0) {
      if (expectedWidth * expectedHeight * 4 == frame.rgba.lengthInBytes) {
        frame = Frame(expectedWidth, expectedHeight, frame.rgba, frame.seq);
      } else {
        pumpError = 'frame size unknown (${frame.rgba.lengthInBytes} B); '
            'select the matching device profile';
        return;
      }
    }
    if (frame.seq > 0 && frame.seq == frames.lastSeq && frames.hasFrame) return;
    // 很多 AVD 的 Image 不带 seq：用跨缓冲区的 64 点采样做指纹，重复帧直接跳过
    // （868KB 的解码是这个循环里最贵的一步）。
    final sample = _sampleHash(frame.rgba);
    if (frame.seq == 0 && _hasSample && sample == _lastSample) return;
    _lastSample = sample;
    _hasSample = true;
    _lastStreamAt = now;
    pumpError = null;
    _streamDecoding = true;
    frames.publish(frame, onDecoded: () => _streamDecoding = false);
  }

  void stopPump() {
    _streamCancel?.call();
    _streamCancel = null;
    _streamDecoding = false;
    _pump?.cancel();
    _pump = null;
    pumpRunning = false;
  }

  /// 跨缓冲区的 64 点采样（长度 + 每隔 len/64 取一个字节），用来识别重复帧。
  static int _sampleHash(Uint8List b) {
    var h = b.lengthInBytes;
    if (b.isEmpty) return h;
    final step = (b.lengthInBytes ~/ 64).clamp(1, 1 << 20);
    for (var i = 0; i < b.lengthInBytes; i += step) {
      h = (h * 31 + b[i]) & 0x3fffffff;
    }
    return h;
  }

  bool _inFlight = false;

  Future<void> _pumpOnce() async {
    if (_inFlight || !grpc.connected) return;
    _inFlight = true;
    try {
      var f = await grpc.screenshot();
      frames.noteReceived();
      if (f.width <= 0 || f.height <= 0) {
        if (expectedWidth * expectedHeight * 4 == f.rgba.lengthInBytes) {
          f = Frame(expectedWidth, expectedHeight, f.rgba, f.seq);
        } else {
          pumpError = 'frame size unknown (${f.rgba.lengthInBytes} B); '
              'select the matching device profile';
          return;
        }
      }
      pumpError = null;
      // 客机没出新帧就别重复解码这 868KB：省下的 CPU 直接变成触摸响应和帧率。
      if (f.seq > 0 && f.seq == frames.lastSeq && frames.hasFrame) {
        return;
      }
      frames.publish(f);
    } catch (e) {
      pumpError = '$e';
    } finally {
      _inFlight = false;
    }
  }

  Future<void> forceFrame() async {
    if (grpc.connected) await _pumpOnce();
  }

  /// UI-local pixels -> device pixels; the frame size is authoritative.
  Future<void> tapLocal(double dx, double dy, double viewW, double viewH) async {
    final w = frames.width, h = frames.height;
    if (w <= 0 || h <= 0 || viewW <= 0 || viewH <= 0) return;
    await grpc.tap((dx / viewW * w).round().clamp(0, w - 1), (dy / viewH * h).round().clamp(0, h - 1));
    await forceFrame();
  }

  Future<void> swipeLocal(double x0, double y0, double x1, double y1, double viewW, double viewH) async {
    final w = frames.width, h = frames.height;
    if (w <= 0 || h <= 0 || viewW <= 0 || viewH <= 0) return;
    int cx(double v, int max) => (v / viewW * max).round().clamp(0, max - 1);
    int cy(double v) => (v / viewH * h).round().clamp(0, h - 1);
    await grpc.swipe(cx(x0, w), cy(y0), cx(x1, w), cy(y1));
    await forceFrame();
  }

  /// Download progress per image type: 0..1, or -1 when finished/unknown.
  final Map<String, double> downloadProgress = <String, double>{};

  void trackDownload(Map<String, Object?> e) {
    // The event discriminator is "type": "download"; the image type arrives as
    // "imageType" (the native emit() renames the payload's own "type" key).
    final t = '${e['imageType']}';
    final done = (e['done'] is int) ? e['done'] as int : 0;
    final total = (e['total'] is int) ? e['total'] as int : 0;
    if (total <= 0) {
      downloadProgress.remove(t);
      return;
    }
    if (done >= total) {
      downloadProgress.remove(t);
    } else {
      downloadProgress[t] = done / total;
    }
  }

  /// Runs an input action, records the failure instead of throwing, and asks for
  /// a fresh frame so the UI reflects it immediately.
  String? inputError;

  Future<void> gesture(Future<void> Function() action) async {
    try {
      await action();
      inputError = null;
    } catch (e) {
      inputError = '$e';
    }
    await forceFrame();
  }

  /// Ask the guest's app framework to start an installed quick app
  /// (`adb shell am start <package>` on the native side).
  Future<void> launchPackage(String package, {String? imageType}) async {
    final r = await _call<Map<Object?, Object?>>('launchApp', {
      'package': package,
      if (imageType != null) 'imageType': imageType,
    });
    // am start failing (guest not booted, adb bridge down) must not look like success.
    inputError = r == null || r['ok'] == true ? null : '${r['msg'] ?? '启动 $package 失败'}';
    await forceFrame();
  }

  Future<void> key(String name) async {
    await grpc.sendKey(name);
    await forceFrame();
  }

  Future<void> battery(int level, {int status = 2, int charger = 0}) =>
      grpc.setBattery(level, status: status, charger: charger);

  Future<void> gps(double lat, double lon, {double altitude = 0}) =>
      grpc.setGps(lat, lon, altitude: altitude);

  Future<void> sensor(int type, List<double> values) => grpc.setSensor(type, values);

  /// 手机 → 客机的剪贴板。
  Future<void> setClipboard(String text) => grpc.setClipboard(text);

  /// 手机上可导入的镜像 zip（/sdcard/Download、/sdcard/Vortex、/sdcard）。
  Future<List<Map<Object?, Object?>>> localZips() async {
    final r = await _call<List<Object?>>('listLocalZips');
    if (r == null) return const [];
    return r
        .whereType<Map<Object?, Object?>>()
        .toList(growable: false);
  }

  /// 用一个本地 zip 安装镜像；进度走和下载相同的 `download` 事件。
  Future<Map<Object?, Object?>?> importImage(String path) =>
      _call<Map<Object?, Object?>>('importImage', {'path': path});

  Future<void> vmState(int state) => grpc.setVmState(state);

  Future<int> queryVmState() => grpc.getVmState();

  /// Saves the current framebuffer as PNG; returns the path written.
  Future<String> saveSnapshot(String dir, String avdId) async {
    final bytes = await grpc.screenshotPng();
    final d = await Directory(dir).create(recursive: true);
    final f = File('${d.path}/$avdId-${DateTime.now().millisecondsSinceEpoch}.png');
    await f.writeAsBytes(bytes);
    return f.path;
  }

  Future<void> dispose() async {
    stopPump();
    await _eventsSub?.cancel();
    await grpc.close();
    frames.dispose();
  }
}
