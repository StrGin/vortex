/// Minimal gRPC client for the Vela emulator (a fork of the Android emulator).
///
/// Wire protocol verified against a live emulator: cleartext HTTP/2 on the
/// emulator's `-grpc <port>`, service `android.emulation.control.EmulatorController`.
/// Messages are encoded/decoded by hand rather than with generated stubs — the
/// surface we need is small and it keeps the build free of protoc. Field numbers
/// come from emulator_controller.proto as shipped inside vela.aiot-emulator.
library;

import 'dart:typed_data';

import 'package:grpc/grpc.dart';

class Frame {
  final int width;
  final int height;
  final Uint8List rgba;
  final int seq;
  const Frame(this.width, this.height, this.rgba, [this.seq = 0]);
}

/// SensorType enum values we expose (emulator_controller.proto).
class VelaSensor {
  static const acceleration = 0;
  static const gyroscope = 1;
  static const magneticField = 2;
  static const orientation = 3;
  static const temperature = 4;
  static const proximity = 5;
  static const light = 6;
  static const pressure = 7;
  static const humidity = 8;
  static const heartRate = 14;
  static const rgbcLight = 15;
}

/// VmRunState enum.
class VelaVm {
  static const running = 1;
  static const paused = 3;
  static const shutdown = 5;
  static const terminate = 7;
  static const reset = 9;
  static const restart = 11;
  static const start = 12;
  static const stop = 13;
}

class VelaGrpc {
  static const _svc = '/android.emulation.control.EmulatorController/';

  ClientChannel? _channel;
  Client? _client;
  String? host;
  int? port;

  bool get connected => _client != null;

  Future<String> connect(String host, int port, {Duration? timeout}) async {
    await close();
    final ch = ClientChannel(host,
        port: port,
        options: const ChannelOptions(credentials: ChannelCredentials.insecure()));
    _channel = ch;
    _client = Client(ch);
    this.host = host;
    this.port = port;
    try {
      return await getStatus(timeout: timeout ?? const Duration(seconds: 5));
    } catch (e) {
      await close();
      rethrow;
    }
  }

  Future<void> close() async {
    _client = null;
    final ch = _channel;
    _channel = null;
    host = null;
    port = null;
    if (ch != null) {
      try {
        await ch.shutdown();
      } catch (_) {}
    }
  }

  Future<List<int>> _rpc(String name, List<int> req, {Duration? timeout}) async {
    final c = _client;
    if (c == null) throw StateError('未连接模拟器');
    final m = ClientMethod<List<int>, List<int>>(
      '$_svc$name',
      (List<int> v) => Uint8List.fromList(v),
      (List<int> v) => v,
    );
    return c.$createUnaryCall(m, req,
        options: CallOptions(timeout: timeout ?? const Duration(seconds: 10)));
  }

  Future<String> getStatus({Duration? timeout}) async {
    final r = await _rpc('getStatus', const [], timeout: timeout);
    for (final f in _Pb.parse(r)) {
      if (f.field == 1 && f.kind == _Pb.len && (f.bytes?.isNotEmpty ?? false)) {
        return String.fromCharCodes(f.bytes!);
      }
    }
    return 'connected';
  }

  /// ImageFormat.format: 0=PNG 1=RGBA8888 2=RGB888.
  ///
  /// The framebuffer arrives **top-down** in this build; the proto comment saying
  /// "bottom up" is wrong (verified by rendering a watchface both ways).
  /// Image.width/height are deprecated and left at 0, so callers must know the
  /// device size — see VelaBridge.expectedWidth.
  Future<Frame> screenshot({int format = 1, Duration? timeout}) async {
    final r = await _rpc('getScreenshot', _Pb().v(1, format).bytes,
        timeout: timeout ?? const Duration(seconds: 20));
    return _decodeImage(r);
  }

  /// Image 消息 → Frame（unary 与流式共用）。
  Frame _decodeImage(List<int> r) {
    var width = 0, height = 0, seq = 0;
    Uint8List data = Uint8List(0);
    for (final f in _Pb.parse(r)) {
      switch (f.field) {
        case 2:
          width = f.varint ?? 0;
          break;
        case 3:
          height = f.varint ?? 0;
          break;
        case 4:
          data = f.bytes ?? Uint8List(0);
          break;
        case 5:
          seq = f.varint ?? 0;
          break;
      }
    }
    final px = data.lengthInBytes ~/ 4;
    if (width <= 0 || height <= 0) {
      final side = _isqrt(px);
      if (side * side == px) width = height = side;
    }
    return Frame(width, height, data, seq);
  }

  /// **流式取帧**：`rpc streamScreenshot(ImageFormat) returns (stream Image)`
  /// （`emulator_controller.proto:151`）—— 官方 AIoT IDE 的 `vela.aiot-emulator`
  /// 扩展就是这么取帧的（不是轮询 `getScreenshot`）。
  ///
  /// 返回一个取消函数；[onFrame] 在每帧到达时同步回调（不含解码）。
  Future<void Function()> streamScreenshot({
    // 1 = RGBA8888。试过 RGB888（少 25% 字节），但 Flutter 的
    // ui.PixelFormat 没有 rgb888，还要自己展开成 RGBA，反而多一次 CPU 拷贝。
    int format = 1,
    required void Function(Frame frame) onFrame,
    void Function(Object error)? onError,
    void Function()? onDone,
  }) async {
    final c = _client;
    if (c == null) throw StateError('未连接模拟器');
    final m = ClientMethod<List<int>, List<int>>(
      '$_svc' 'streamScreenshot',
      (List<int> v) => Uint8List.fromList(v),
      (List<int> v) => v,
    );
    // 服务端流式：请求只发一次（grpc-dart 的统一入口收的是请求流）。
    final call = c.$createStreamingCall(m, Stream<List<int>>.value(_Pb().v(1, format).bytes),
        options: CallOptions(timeout: const Duration(hours: 12)));
    final sub = call.listen(
      (List<int> msg) {
        try {
          onFrame(_decodeImage(msg));
        } catch (e) {
          onError?.call(e);
        }
      },
      onError: (Object e) => onError?.call(e),
      onDone: () => onDone?.call(),
      cancelOnError: true,
    );
    return sub.cancel;
  }

  /// PNG variant, used for saving a snapshot to disk.
  Future<Uint8List> screenshotPng({Duration? timeout}) async {
    final r = await _rpc('getScreenshot', _Pb().v(1, 0).bytes,
        timeout: timeout ?? const Duration(seconds: 20));
    for (final f in _Pb.parse(r)) {
      if (f.field == 4) return f.bytes ?? Uint8List(0);
    }
    return Uint8List(0);
  }

    /// MouseEvent { x=1, y=2, buttons=3, display=4 }
  ///
  /// **This is the input channel that actually reaches a Vela guest.** Verified
  /// against a live emulator: a click via sendMouse changed 57,344 pixels of the
  /// framebuffer while sendTouch changed 0 (control with no input: also 0).
  /// Xiaomi's own automation (npm `velajs-mcp`) implements tap/double_tap/scroll
  /// exactly this way: buttons=1 to press, 0 to release.
  Future<void> mouse(int x, int y, int buttons) =>
      _rpc('sendMouse', _Pb().v(1, x).v(2, y).v(3, buttons).bytes);

  Future<void> tap(int x, int y, {int dwellMs = 35}) async {
    await mouse(x, y, 1);
    await Future<void>.delayed(Duration(milliseconds: dwellMs));
    await mouse(x, y, 0);
  }

  Future<void> longPress(int x, int y, {int ms = 700}) async {
    await mouse(x, y, 1);
    for (var t = 0; t < ms; t += 100) {
      await Future<void>.delayed(const Duration(milliseconds: 100));
      await mouse(x, y, 1);
    }
    await mouse(x, y, 0);
  }

  Future<void> swipe(int x0, int y0, int x1, int y1,
      {int steps = 12, int durationMs = 240}) async {
    await mouse(x0, y0, 1);
    final per = (durationMs / steps).round().clamp(8, 200);
    for (var i = 1; i <= steps; i++) {
      final t = i / steps;
      await mouse((x0 + (x1 - x0) * t).round(), (y0 + (y1 - y0) * t).round(), 1);
      await Future<void>.delayed(Duration(milliseconds: per));
    }
    await mouse(x1, y1, 0);
  }

  /// KeyboardEvent { codeType=1, eventType=2, keyCode=3, key=4, text=5 }
  /// codeType 0=Usb 1=Evdev; eventType 0=down 1=up 2=press.
  /// `key` accepts W3C names the emulator maps to Android keys: GoHome, GoBack, Power.
  Future<void> sendKey(String key, {int eventType = 2, int codeType = 0}) =>
      _rpc('sendKey', _Pb().v(1, codeType).v(2, eventType).s(4, key).bytes);

  /// BatteryState { hasBattery=1, isPresent=2, charger=3, chargeLevel=4, health=5, status=6 }
  Future<void> setBattery(int level, {int status = 2, int charger = 0}) => _rpc(
      'setBattery',
      _Pb()
          .v(1, 1)
          .v(2, 1)
          .v(3, charger)
          .v(4, level.clamp(0, 100))
          .v(5, 0)
          .v(6, status)
          .bytes);

  /// GpsState { passiveUpdate=1, latitude=2, longitude=3, speed=4, bearing=5, altitude=6, satellites=7 }
  Future<void> setGps(double lat, double lon,
          {double altitude = 0, double speed = 0, double bearing = 0, int satellites = 9}) =>
      _rpc(
          'setGps',
          _Pb()
              .v(1, 0)
              .d(2, lat)
              .d(3, lon)
              .d(4, speed)
              .d(5, bearing)
              .d(6, altitude)
              .v(7, satellites)
              .bytes);

  /// ClipData { text = 1 } —— 手机与客机之间的剪贴板同步。
  Future<void> setClipboard(String text) =>
      _rpc('setClipboard', _Pb().s(1, text).bytes);

  /// 取回客机的剪贴板内容（空串表示客机那边是空的）。
  Future<String> getClipboard() async {
    final r = await _rpc('getClipboard', const []);
    for (final f in _Pb.parse(r)) {
      if (f.field == 1 && (f.bytes?.isNotEmpty ?? false)) {
        return String.fromCharCodes(f.bytes!);
      }
    }
    return '';
  }

  /// SensorValue { target=1, status=2, value=3: ParameterValue { repeated float data=1 packed } }
  Future<void> setSensor(int type, List<double> values) {
    final floats = Uint8List(values.length * 4);
    final bd = floats.buffer.asByteData();
    for (var i = 0; i < values.length; i++) {
      bd.setFloat32(i * 4, values[i], Endian.little);
    }
    final param = _Pb().b(1, floats).bytes;
    return _rpc('setSensor', _Pb().v(1, type).b(3, param).bytes);
  }

  /// VmRunState { state=1 } — pause/resume/reset/shutdown the guest.
  Future<void> setVmState(int state) => _rpc('setVmState', _Pb().v(1, state).bytes);

  Future<int> getVmState() async {
    final r = await _rpc('getVmState', const []);
    for (final f in _Pb.parse(r)) {
      if (f.field == 1) return f.varint ?? 0;
    }
    return 0;
  }

  static int _isqrt(int v) {
    if (v <= 0) return 0;
    var x = v;
    var y = (x + 1) ~/ 2;
    while (y < x) {
      x = y;
      y = (x + v ~/ x) ~/ 2;
    }
    return x;
  }
}

/// Hand-rolled protobuf writer/reader (varint, length-delimited, fixed64).
class _Pb {
  final List<int> _out = <int>[];
  List<int> get bytes => _out;

  _Pb v(int field, int value) {
    _tag(field, 0);
    _vi(value);
    return this;
  }

  _Pb b(int field, List<int> value) {
    _tag(field, 2);
    _vi(value.length);
    _out.addAll(value);
    return this;
  }

  _Pb s(int field, String value) => b(field, Uint8List.fromList(value.codeUnits));

  _Pb d(int field, double value) {
    _tag(field, 1);
    final bd = ByteData(8)..setFloat64(0, value, Endian.little);
    _out.addAll(bd.buffer.asUint8List());
    return this;
  }

  void _tag(int field, int wt) => _vi((field << 3) | wt);

  void _vi(int n) {
    var x = n;
    while (true) {
      final byte = x & 0x7f;
      x = x >>> 7;
      _out.add(byte | (x == 0 ? 0 : 0x80));
      if (x == 0) return;
    }
  }

  static const varint = 0;
  static const len = 2;

  static List<_PbField> parse(List<int> buf) {
    final b = buf is Uint8List ? buf : Uint8List.fromList(buf);
    final out = <_PbField>[];
    var i = 0;
    int readVarint() {
      var v = 0;
      var sh = 0;
      while (i < b.length) {
        final c = b[i++];
        v |= (c & 0x7f) << sh;
        sh += 7;
        if ((c & 0x80) == 0) return v;
      }
      return v;
    }

    while (i < b.length) {
      final start = i;
      final key = readVarint();
      final field = key >> 3;
      final wt = key & 7;
      if (wt == varint) {
        out.add(_PbField(field, varint, varint: readVarint()));
      } else if (wt == len) {
        final ln = readVarint();
        if (i + ln > b.length) break;
        out.add(_PbField(field, len, bytes: b.sublist(i, i + ln)));
        i += ln;
      } else if (wt == 5) {
        i += 4;
      } else if (wt == 1) {
        i += 8;
      } else {
        break;
      }
      if (i == start) break;
    }
    return out;
  }
}

class _PbField {
  final int field;
  final int kind;
  final int? varint;
  final Uint8List? bytes;
  const _PbField(this.field, this.kind, {this.varint, this.bytes});
}
