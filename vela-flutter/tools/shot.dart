// 抓一帧 Vela 客机画面存成 PNG（无头检查用）。
//   dart run tools/shot.dart [host] [port] [out.png] [tapX tapY]
import 'dart:typed_data';

import 'package:grpc/grpc.dart';

import 'dart_grpc_check.dart' as g;

Future<void> main(List<String> args) async {
  final host = args.isNotEmpty ? args[0] : '127.0.0.1';
  final port = args.length > 1 ? int.parse(args[1]) : 8554;
  final out = args.length > 2 ? args[2] : 'tools/shot.png';

  final ch = ClientChannel(host,
      port: port,
      options: const ChannelOptions(credentials: ChannelCredentials.insecure()));
  final client = Client(ch);
  ClientMethod<List<int>, List<int>> method(String name) =>
      ClientMethod<List<int>, List<int>>(
        '/android.emulation.control.EmulatorController/$name',
        (List<int> v) => Uint8List.fromList(v),
        (List<int> v) => v,
      );
  Future<List<int>> rpc(String name, List<int> req, int secs) =>
      client.$createUnaryCall(method(name), req,
          options: CallOptions(timeout: Duration(seconds: secs)));

  final img = g.parseImage(await rpc('getScreenshot', g.screenshotReq(), 25));
  final px = img.data.lengthInBytes ~/ 4;
  final w = img.width > 0 ? img.width : 466;
  final h = px ~/ w;
  g.writePng(out, img.data, w, h);
  print('$out  ${w}x$h  ${img.data.length} bytes');

  if (args.length > 4) {
    final x = int.parse(args[3]), y = int.parse(args[4]);
    await rpc('sendTouch', g.touchEvent([g.touchMsg(x, y, 1, 255)]), 10);
    await Future<void>.delayed(const Duration(milliseconds: 120));
    await rpc('sendTouch', g.touchEvent([g.touchMsg(x, y, 1, 0)]), 10);
    print('tapped $x,$y');
  }
  await ch.shutdown();
}
