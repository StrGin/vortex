// 把 g1 涡纹光栅化成各密度图标 PNG。
// 跑法：复制到 vela-flutter/test/gen_icons_test.dart，再
//   cd vela-flutter && flutter test test/gen_icons_test.dart
// 跑完把 test/gen_icons_test.dart 删掉（别留在 test/ 里，否则每次 flutter test 都会写盘）。
// 产物：D:\download\sim\simpsimvela\icon-proposals\out\mipmap-*\
import 'dart:io';
import 'dart:math' as math;
import 'dart:ui' as ui;

import 'package:flutter_test/flutter_test.dart';

const _bg = ui.Color(0xFFF2F5F9);
const _ink = ui.Color(0xFF000000);
const _cx = 512.0, _cy = 512.0;
const _rOut = 374.0, _rIn = 62.0, _stroke = 88.0, _turns = 1.8;
final _phase = 315 * math.pi / 180;

const _outDir = r'D:\download\sim\simpsimvela\icon-proposals\out';

class Geo {
  Geo(this.pts, this.dx, this.dy, this.maxR, this.span);
  final List<ui.Offset> pts;
  final double dx, dy, maxR, span;
}

Geo _geometry() {
  final pts = <ui.Offset>[];
  for (var i = 0; i <= 1400; i++) {
    final t = i / 1400;
    final th = _phase + t * _turns * 2 * math.pi;
    final r = _rOut + (_rIn - _rOut) * t;
    pts.add(ui.Offset(_cx + r * math.cos(th), _cy + r * math.sin(th)));
  }
  var minX = double.infinity, maxX = -double.infinity;
  var minY = double.infinity, maxY = -double.infinity;
  for (final p in pts) {
    minX = math.min(minX, p.dx);
    maxX = math.max(maxX, p.dx);
    minY = math.min(minY, p.dy);
    maxY = math.max(maxY, p.dy);
  }
  final dx = _cx - (minX + maxX) / 2;
  final dy = _cy - (minY + maxY) / 2;
  var maxR = 0.0;
  for (final p in pts) {
    final d = math.sqrt(math.pow(p.dx + dx - _cx, 2) + math.pow(p.dy + dy - _cy, 2));
    maxR = math.max(maxR, d);
  }
  return Geo(pts, dx, dy, maxR + _stroke / 2,
      math.max(maxX - minX, maxY - minY) + _stroke);
}

Future<void> _write(String path, int size, Geo g, double scale, bool withBg) async {
  final rec = ui.PictureRecorder();
  final canvas = ui.Canvas(rec, const ui.Rect.fromLTWH(0, 0, 1024, 1024));
  canvas.scale(size / 1024);
  if (withBg) {
    canvas.drawRect(const ui.Rect.fromLTWH(0, 0, 1024, 1024),
        ui.Paint()..color = _bg);
  }
  final stroke = ui.Path()
    ..moveTo(_cx + (g.pts.first.dx + g.dx - _cx) * scale,
        _cy + (g.pts.first.dy + g.dy - _cy) * scale);
  for (final p in g.pts.skip(1)) {
    stroke.lineTo(_cx + (p.dx + g.dx - _cx) * scale, _cy + (p.dy + g.dy - _cy) * scale);
  }
  canvas.drawPath(
      stroke,
      ui.Paint()
        ..style = ui.PaintingStyle.stroke
        ..strokeWidth = _stroke * scale
        ..strokeCap = ui.StrokeCap.round
        ..strokeJoin = ui.StrokeJoin.round
        ..isAntiAlias = true
        ..color = _ink);
  final img = await rec.endRecording().toImage(size, size);
  final bytes = await img.toByteData(format: ui.ImageByteFormat.png);
  final f = File(path)..parent.createSync(recursive: true);
  f.writeAsBytesSync(bytes!.buffer.asUint8List());
  img.dispose();
}

void main() {
  test('rasterize launcher icons', () async {
    final g = _geometry();
    const legacyK = 0.82; // 外廓占画布比例
    const fgSafe = 66 / 108; // 自适应图标安全区
    final sLegacy = 1024 * legacyK / g.span;
    final sFg = 512 * fgSafe / g.maxR;
    const densities = {'mdpi': 1.0, 'hdpi': 1.5, 'xhdpi': 2.0, 'xxhdpi': 3.0, 'xxxhdpi': 4.0};
    for (final e in densities.entries) {
      final dir = '$_outDir\\mipmap-${e.key}';
      final legacy = (48 * e.value).round();
      final fg = (108 * e.value).round();
      await _write('$dir\\ic_launcher.png', legacy, g, sLegacy, true);
      await _write('$dir\\ic_launcher_foreground.png', fg, g, sFg, false);
      // ignore: avoid_print
      print('mipmap-${e.key}: $legacy / $fg');
    }
    // ignore: avoid_print
    print('legacy scale=$sLegacy fg scale=$sFg maxR=${g.maxR}');
  });
}
