// Standalone check that Dart can drive the Vela emulator's gRPC API with hand-encoded
// protobuf (no protoc): frame out via getScreenshot, touch in via sendTouch.
//   dart run tools/dart_grpc_check.dart [host] [port]
import 'dart:io';
import 'dart:typed_data';

import 'package:grpc/grpc.dart';

void _vi(List<int> o, int v) {
  var x = v;
  while (true) {
    final b = x & 0x7f;
    x = x >>> 7;
    o.add(b | (x == 0 ? 0 : 0x80));
    if (x == 0) return;
  }
}

void _tag(List<int> o, int field, int wt) => _vi(o, (field << 3) | wt);
void _fV(List<int> o, int field, int v) {
  _tag(o, field, 0);
  _vi(o, v);
}
void _fB(List<int> o, int field, List<int> v) {
  _tag(o, field, 2);
  _vi(o, v.length);
  o.addAll(v);
}

/// ImageFormat { format=1 } ; 0=PNG 1=RGBA8888 2=RGB888
List<int> screenshotReq({int format = 1}) {
  final b = <int>[];
  _fV(b, 1, format);
  return b;
}

/// Touch { x=1 y=2 identifier=3 pressure=4 touch_major=5 touch_minor=6 }
List<int> touchMsg(int x, int y, int id, int pressure) {
  final b = <int>[];
  _fV(b, 1, x);
  _fV(b, 2, y);
  _fV(b, 3, id);
  _fV(b, 4, pressure);
  _fV(b, 5, 20);
  _fV(b, 6, 20);
  return b;
}

/// TouchEvent { repeated touches=1, int32 display=2 }
List<int> touchEvent(List<List<int>> touches) {
  final b = <int>[];
  for (final t in touches) {
    _fB(b, 1, t);
  }
  return b;
}

class Image {
  int width = 0;
  int height = 0;
  Uint8List data = Uint8List(0);
}

Image parseImage(List<int> buf) {
  final r = Image();
  final b = Uint8List.fromList(buf);
  var i = 0;
  int readVarint() {
    var v = 0;
    var sh = 0;
    while (i < b.length) {
      final c = b[i++];
      v |= (c & 0x7f) << sh;
      sh += 7;
      if ((c & 0x80) == 0) break;
    }
    return v;
  }

  while (i < b.length) {
    final key = readVarint();
    final field = key >> 3;
    final wt = key & 7;
    switch (wt) {
      case 0:
        final v = readVarint();
        if (field == 2) r.width = v;
        if (field == 3) r.height = v;
        break;
      case 2:
        final ln = readVarint();
        if (i + ln > b.length) return r;
        final v = b.sublist(i, i + ln);
        i += ln;
        if (field == 4) r.data = Uint8List.fromList(v);
        break;
      case 5:
        i += 4;
        break;
      case 1:
        i += 8;
        break;
      default:
        return r;
    }
  }
  return r;
}

void writePng(String path, Uint8List rgba, int w, int h) {
  // Framebuffer is top-down (verified against the live emulator); do not flip.
  final raw = Uint8List(h * (1 + w * 4));
  for (var y = 0; y < h; y++) {
    raw[y * (1 + w * 4)] = 0;
    raw.setRange(y * (1 + w * 4) + 1, y * (1 + w * 4) + 1 + w * 4,
        rgba.sublist(y * w * 4, (y + 1) * w * 4));
  }
  final comp = ZLibCodec().encode(raw);
  final out = BytesBuilder();
  void chunk(String type, List<int> data) {
    final t = Uint8List.fromList(type.codeUnits);
    final len = Uint8List(4)..buffer.asByteData().setUint32(0, data.length);
    final crc = _crc32([...t, ...data]);
    final cb = Uint8List(4)..buffer.asByteData().setUint32(0, crc);
    out.add(len);
    out.add(t);
    out.add(data);
    out.add(cb);
  }

  final ihdr = Uint8List(13);
  final id = ihdr.buffer.asByteData()
    ..setUint32(0, w)
    ..setUint32(4, h);
  ihdr[8] = 8;
  ihdr[9] = 6;
  out.add([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
  chunk('IHDR', ihdr);
  chunk('IDAT', comp);
  chunk('IEND', const []);
  File(path).writeAsBytesSync(out.toBytes());
}

int _crc32(List<int> bytes) {
  var c = 0xffffffff;
  for (final b in bytes) {
    c ^= b;
    for (var k = 0; k < 8; k++) {
      c = (c & 1) != 0 ? (0xedb88320 ^ (c >> 1)) : (c >> 1);
    }
  }
  return (c ^ 0xffffffff) & 0xffffffff;
}

Future<void> main(List<String> args) async {
  final host = args.isNotEmpty ? args[0] : '127.0.0.1';
  final port = args.length > 1 ? int.parse(args[1]) : 8557;
  final ch = ClientChannel(host,
      port: port, options: const ChannelOptions(credentials: ChannelCredentials.insecure()));
  final client = Client(ch);

  ClientMethod<List<int>, List<int>> method(String name) => ClientMethod<List<int>, List<int>>(
        '/android.emulation.control.EmulatorController/$name',
        (List<int> v) => Uint8List.fromList(v),
        (List<int> v) => v,
      );

  Future<List<int>> rpc(String name, List<int> req, int secs) async =>
      await client.$createUnaryCall(method(name), req,
          options: CallOptions(timeout: Duration(seconds: secs)));

  final sw = Stopwatch()..start();
  final status = await rpc('getStatus', <int>[], 10);
  print('getStatus: ${status.length} bytes in ${sw.elapsedMilliseconds} ms');

  sw.reset();
  final shot = await rpc('getScreenshot', screenshotReq(), 20);
  final img = parseImage(shot);
  final px = img.data.lengthInBytes ~/ 4;
  final side = (px.toDouble().floorToDouble() == px ? _isqrt(px) : 0);
  print('getScreenshot: ${img.data.length} bytes in ${sw.elapsedMilliseconds} ms, '
      'proto w/h=${img.width}x${img.height}, px=$px');
  final w = side > 0 ? side : 466;
  final h = px ~/ w;
  writePng('tools/dart_grpc_check.png', img.data, w, h);
  print('wrote tools/dart_grpc_check.png as ${w}x$h');

  // touch: tap the centre and report how much of the framebuffer moved
  final before = parseImage(await rpc('getScreenshot', screenshotReq(), 20));
  await rpc('sendTouch', touchEvent([touchMsg(w ~/ 2, h ~/ 2, 1, 255)]), 10);
  await Future<void>.delayed(const Duration(milliseconds: 350));
  await rpc('sendTouch', touchEvent([touchMsg(w ~/ 2, h ~/ 2, 1, 0)]), 10);
  await Future<void>.delayed(const Duration(milliseconds: 1200));
  final after = parseImage(await rpc('getScreenshot', screenshotReq(), 20));
  var diff = 0;
  final n = before.data.lengthInBytes < after.data.lengthInBytes
      ? before.data.lengthInBytes
      : after.data.lengthInBytes;
  for (var i = 0; i < n; i += 4) {
    if (before.data[i] != after.data[i] ||
        before.data[i + 1] != after.data[i + 1] ||
        before.data[i + 2] != after.data[i + 2]) diff++;
  }
  print('after centre tap: $diff px changed (of ${n ~/ 4})');
  await ch.shutdown();
}

int _isqrt(int v) {
  var x = v;
  var y = (x + 1) ~/ 2;
  while (y < x) {
    x = y;
    y = (x + v ~/ x) ~/ 2;
  }
  return x;
}
