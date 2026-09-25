import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:math' as math;

import '../state/app_state.dart';

/// 内置的 **AI / 脚本控制接口**（HTTP + MCP 形状的 JSON-RPC）。
///
/// 本 App 里所有能力都已经是本地调用（gRPC 取帧/输入、adb 装机、工具链构建），
/// 这里只是把它们包成一个 localhost 的接口，让 AI 或脚本能像人一样操作这台
/// 模拟器：截屏 → 点按 → 再截屏。
///
/// * 只绑 `127.0.0.1`，并要求 token（手机上任何 App 都能连 localhost，
///   所以默认关闭、开启后也要带 token）；
/// * 普通 REST 用起来最省事：`GET /status`、`GET /screenshot.png`、
///   `POST /tap {x,y}`、`POST /swipe {...}`、`POST /key {code}` …
/// * `POST /mcp` 说 JSON-RPC 2.0 的 `tools/list` / `tools/call`，
///   也就是 MCP 的 "streamable HTTP" 形状 —— 现成的 MCP 客户端可以直接接。
class VelaControlServer {
  VelaControlServer(this.state);

  final AppState state;
  HttpServer? _server;

  bool get running => _server != null;
  int get port => _server?.port ?? 0;

  static const defaultPort = 8765;

  /// 访问令牌：随机生成、显示在设置页，改端口/重启会换。
  String token = _randomToken();

  static String _randomToken() {
    final r = math.Random.secure();
    final b = List<int>.generate(16, (_) => r.nextInt(256));
    return b.map((x) => x.toRadixString(16).padLeft(2, '0')).join();
  }

  Future<int> start({int port = defaultPort}) async {
    await stop();
    final s = await HttpServer.bind(InternetAddress.loopbackIPv4, port);
    _server = s;
    s.listen(_handle, onError: (_) {});
    return s.port;
  }

  Future<void> stop() async {
    final s = _server;
    _server = null;
    if (s != null) {
      await s.close(force: true);
    }
  }

  // ------------------------------------------------------------------ 路由

  Future<void> _handle(HttpRequest req) async {
    final res = req.response;
    res.headers.set('content-type', 'application/json; charset=utf-8');
    try {
      final given = req.uri.queryParameters['token'] ??
          req.headers.value('x-vela-token') ??
          '';
      if (given != token) {
        res.statusCode = HttpStatus.unauthorized;
        res.write(jsonEncode({'error': 'token 不对：在 App 的「AI 接口」卡片里看 token'}));
        return;
      }
      final path = req.uri.path;
      if (path == '/mcp') {
        final body = await utf8.decoder.bind(req).join();
        final out = await _mcp(body.isEmpty ? '{}' : body);
        res.write(jsonEncode(out));
        return;
      }
      if (path == '/screenshot.png' || path == '/frame.png') {
        final bytes = await state.bridge.grpc.screenshotPng();
        res.headers.set('content-type', 'image/png');
        res.add(bytes);
        return;
      }
      final args = req.method == 'POST'
          ? await _jsonBody(req)
          : req.uri.queryParameters.map((k, v) => MapEntry(k, v as Object?));
      final out = await _rest(path, args);
      res.write(jsonEncode(out));
    } catch (e) {
      res.statusCode = HttpStatus.internalServerError;
      res.write(jsonEncode({'ok': false, 'error': '$e'}));
    } finally {
      await res.close();
    }
  }

  Future<Map<String, Object?>> _jsonBody(HttpRequest req) async {
    final text = await utf8.decoder.bind(req).join();
    if (text.trim().isEmpty) return const {};
    final v = jsonDecode(text);
    return v is Map ? v.cast<String, Object?>() : const {};
  }

  double _num(Map<String, Object?> a, String k, [double fallback = 0]) {
    final v = a[k];
    if (v is num) return v.toDouble();
    return double.tryParse('$v') ?? fallback;
  }

  /// 与 MCP 工具表**共用**的实现：REST 路径和 MCP 工具名都落到这里。
  Future<Map<String, Object?>> callTool(String name, Map<String, Object?> a) async {
    final bridge = state.bridge;
    switch (name) {
      case 'status':
        final s = bridge.status;
        return {
          'engineRunning': s.running,
          'enginePid': s.pid,
          'avd': state.selected?.avdId,
          'grpcConnected': bridge.grpc.connected,
          'grpc': '${bridge.grpc.host ?? '-'}:${bridge.grpc.port ?? '-'}',
          'frame': '${bridge.frames.width}x${bridge.frames.height}',
          'fps': bridge.frames.fps,
          'devices': bridge.devices.map((d) => d.avdId).toList(),
          'projects': state.projects.projects.map((p) => p.name).toList(),
          'apps': state.projects.installedApps,
        };
      case 'screenshot':
        final bytes = await bridge.grpc.screenshotPng();
        return {
          'format': 'png',
          'width': bridge.frames.width,
          'height': bridge.frames.height,
          'base64': base64Encode(bytes),
        };
      case 'tap':
        await bridge.grpc.tap(_num(a, 'x').round(), _num(a, 'y').round(),
            dwellMs: _num(a, 'dwellMs', 35).round());
        return {'ok': true};
      case 'longPress':
        await bridge.grpc.longPress(_num(a, 'x').round(), _num(a, 'y').round(),
            ms: _num(a, 'ms', 700).round());
        return {'ok': true};
      case 'swipe':
        await bridge.grpc.swipe(
          _num(a, 'x1').round(),
          _num(a, 'y1').round(),
          _num(a, 'x2').round(),
          _num(a, 'y2').round(),
          durationMs: _num(a, 'ms', 300).round(),
        );
        return {'ok': true};
      case 'key':
        await bridge.grpc.sendKey('${a['code'] ?? 'GoHome'}');
        return {'ok': true};
      case 'text':
        await bridge.setClipboard('${a['text'] ?? ''}');
        return {'ok': true};
      case 'battery':
        final level = _num(a, 'level', 100).round();
        final charging = a['charging'] == true;
        await bridge.grpc.setBattery(level,
            status: charging ? 1 : 2, charger: charging ? 1 : 0);
        return {'ok': true};
      case 'gps':
        await bridge.grpc
            .setGps(_num(a, 'lat'), _num(a, 'lon'));
        return {'ok': true};
      case 'engine':
        final action = '${a['action'] ?? 'start'}';
        if (action == 'stop') {
          await bridge.stopEngine();
          return {'ok': true, 'engineRunning': false};
        }
        final avd = '${a['avd'] ?? state.selected?.avdId ?? ''}';
        if (avd.isEmpty) return {'ok': false, 'error': '没有指定 avd，也没选中设备'};
        final d = bridge.devices.where((x) => x.avdId == avd).toList();
        if (d.isNotEmpty) state.select(d.first);
        await state.startSelected();
        return {'ok': true, 'avd': avd, 'engineRunning': bridge.status.running};
      case 'launchApp':
        final pkg = '${a['package'] ?? ''}';
        if (pkg.isEmpty) return {'ok': false, 'error': 'package 不能为空'};
        await bridge.launchPackage(pkg, imageType: state.selected?.imageType);
        return {'ok': true};
      case 'stopApp':
        await state.projects
            .stopApp('${a['package'] ?? ''}', imageType: state.selected?.imageType);
        return {'ok': true};
      case 'build':
        // 与 UI 的「构建」一致：只做 release 构建，产出正式包（不装机）。
        final projects = state.projects.projects;
        final project = '${a['project'] ?? (projects.isEmpty ? '' : projects.first.name)}';
        if (project.isEmpty) return {'ok': false, 'error': '没有工程'};
        final task = '${a['task'] ?? 'release'}' == 'build' ? 'build' : 'release';
        state.projects.buildLog.clear();
        // 构建是分钟级的：这里只**发起**，用 buildLog 轮询。
        unawaited(state.projects.build(project, task: task));
        return {'ok': true, 'started': true, 'project': project, 'task': task};
      case 'push':
        // 与 UI 的「推送」一致：debug 构建 + 装机 + 启动。
        final projects = state.projects.projects;
        final project = '${a['project'] ?? (projects.isEmpty ? '' : projects.first.name)}';
        if (project.isEmpty) return {'ok': false, 'error': '没有工程'};
        state.projects.buildLog.clear();
        unawaited(state.projects.push(
          project,
          device: state.selected?.avdId,
          imageType: state.selected?.imageType,
        ));
        return {'ok': true, 'started': true, 'project': project, 'task': 'build'};
      case 'buildLog':
        final n = _num(a, 'lines', 80).round();
        final log = state.projects.buildLog;
        return {
          'lines': log.length > n ? log.sublist(log.length - n) : log,
          'busy': state.projects.busy,
        };
      default:
        return {'ok': false, 'error': '未知操作: $name'};
    }
  }

  Future<Map<String, Object?>> _rest(String path, Map<String, Object?> a) {
    final op = path.replaceFirst('/', '').replaceAll('/', '_');
    return callTool(op, a);
  }

  // ------------------------------------------------------------------- MCP

  /// MCP 的 "streamable HTTP"：一个 POST 端点，JSON-RPC 2.0。
  /// 支持 `initialize` / `tools/list` / `tools/call`。
  Future<Map<String, Object?>> _mcp(String body) async {
    Object? id;
    try {
      final msg = jsonDecode(body);
      if (msg is! Map) {
        return _rpcError(null, -32700, 'parse error');
      }
      id = msg['id'];
      final method = '${msg['method'] ?? ''}';
      switch (method) {
        case 'initialize':
          return _rpcResult(id, {
            'protocolVersion': '2024-11-05',
            'capabilities': {
              'tools': <String, Object?>{},
            },
            'serverInfo': {'name': 'vortex-vela', 'version': '1.0.0'},
          });
        case 'tools/list':
          return _rpcResult(id, {
            'tools': [
              for (final t in _tools)
                {
                  'name': t.$1,
                  'description': t.$2,
                  'inputSchema': t.$3,
                },
            ],
          });
        case 'tools/call':
          final params = (msg['params'] as Map?)?.cast<String, Object?>() ?? {};
          final name = '${params['name'] ?? ''}';
          final args =
              (params['arguments'] as Map?)?.cast<String, Object?>() ?? {};
          final out = await callTool(name, args);
          return _rpcResult(id, {
            'content': [
              {
                'type': 'text',
                'text': jsonEncode(out),
              },
            ],
            'isError': out['ok'] == false,
          });
        case 'notifications/initialized':
        case 'notifications/cancelled':
          return {}; // 通知没有响应体
        default:
          return _rpcError(id, -32601, 'method not found: $method');
      }
    } catch (e) {
      return _rpcError(id, -32603, '$e');
    }
  }

  static Map<String, Object?> _rpcResult(Object? id, Object result) =>
      {'jsonrpc': '2.0', 'id': id, 'result': result};

  static Map<String, Object?> _rpcError(Object? id, int code, String message) =>
      {
        'jsonrpc': '2.0',
        'id': id,
        'error': {'code': code, 'message': message},
      };

  /// (name, description, inputSchema)
  static final List<(String, String, Map<String, Object?>)> _tools = [
    ('status', '模拟器/客机当前状态：引擎、gRPC、帧尺寸、fps、设备与工程列表', {
      'type': 'object',
      'properties': <String, Object?>{},
    }),
    ('screenshot', '取一帧客机画面（PNG，base64 放在 base64 字段里）', {
      'type': 'object',
      'properties': <String, Object?>{},
    }),
    ('tap', '点按客机屏幕（坐标是客机像素，466x466 这类）', {
      'type': 'object',
      'properties': {
        'x': {'type': 'number'},
        'y': {'type': 'number'},
        'dwellMs': {'type': 'number', 'description': '按住时长，默认 35ms'},
      },
      'required': ['x', 'y'],
    }),
    ('longPress', '长按客机屏幕', {
      'type': 'object',
      'properties': {
        'x': {'type': 'number'},
        'y': {'type': 'number'},
        'ms': {'type': 'number'},
      },
      'required': ['x', 'y'],
    }),
    ('swipe', '在客机屏幕上滑动', {
      'type': 'object',
      'properties': {
        'x1': {'type': 'number'},
        'y1': {'type': 'number'},
        'x2': {'type': 'number'},
        'y2': {'type': 'number'},
        'ms': {'type': 'number'},
      },
      'required': ['x1', 'y1', 'x2', 'y2'],
    }),
    ('key', '按客机按键：GoHome / GoBack / Power 等', {
      'type': 'object',
      'properties': {
        'code': {'type': 'string'},
      },
      'required': ['code'],
    }),
    ('text', '把文本送进客机剪贴板（手表没法打字）', {
      'type': 'object',
      'properties': {
        'text': {'type': 'string'},
      },
      'required': ['text'],
    }),
    ('battery', '注入电量/充电状态', {
      'type': 'object',
      'properties': {
        'level': {'type': 'number'},
        'charging': {'type': 'boolean'},
      },
      'required': ['level'],
    }),
    ('gps', '注入经纬度', {
      'type': 'object',
      'properties': {
        'lat': {'type': 'number'},
        'lon': {'type': 'number'},
      },
      'required': ['lat', 'lon'],
    }),
    ('engine', '启动/停止引擎：action=start|stop，start 可带 avd', {
      'type': 'object',
      'properties': {
        'action': {'type': 'string'},
        'avd': {'type': 'string'},
      },
      'required': ['action'],
    }),
    ('launchApp', '启动客机里已安装的应用', {
      'type': 'object',
      'properties': {
        'package': {'type': 'string'},
      },
      'required': ['package'],
    }),
    ('stopApp', '停止客机里的应用', {
      'type': 'object',
      'properties': {
        'package': {'type': 'string'},
      },
      'required': ['package'],
    }),
    ('build', '发起工程构建（分钟级，异步）：随后用 buildLog 轮询输出。'
        '默认 release 正式构建，传 task="build" 走调试构建', {
      'type': 'object',
      'properties': {
        'project': {'type': 'string'},
        'task': {
          'type': 'string',
          'enum': ['release', 'build'],
          'description': 'release（默认，正式构建）/ build（调试构建）',
        },
      },
    }),
    ('push', '推送：debug 构建 → 装进客机 → 启动（同工程页的「推送」）。'
        '随后用 buildLog 轮询输出', {
      'type': 'object',
      'properties': {
        'project': {'type': 'string'},
      },
    }),
    ('buildLog', '取构建日志尾部', {
      'type': 'object',
      'properties': {
        'lines': {'type': 'number'},
      },
    }),
  ];

  /// 给设置页展示用：接入地址清单。
  List<String> endpointSummary() => [
        'GET  /status?token=…',
        'GET  /screenshot.png?token=…',
        'POST /tap?token=… {"x":233,"y":233}',
        'POST /swipe|/key|/text|/battery|/gps?token=…',
        'POST /engine?token=… {"action":"start","avd":"xiaomi_s4"}',
        'POST /build?token=… {"project":"HyperBoxPro"}  +  GET /buildLog',
        'POST /mcp?token=…  (MCP streamable HTTP: initialize/tools/list/tools/call)',
      ];
}
