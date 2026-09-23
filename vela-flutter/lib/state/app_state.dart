import 'dart:io';

import 'package:flutter/widgets.dart';

import '../models/vela_models.dart';
import 'control_server.dart';
import 'vela_bridge.dart';
import 'vela_projects.dart';

class AppState extends ChangeNotifier {
  final VelaBridge bridge = VelaBridge();
  late final VelaProjects projects = VelaProjects(() => notifyListeners());

  DeviceProfile? selected;
  bool verboseEngine = false;

  /// 底栏的液态玻璃（实验性外观，默认关）。设置页 → 外观 里开，开关会被记住。
  bool liquidGlassNav = false;

  // ── 引擎性能旋钮（都会被记住）：起引擎时传给 VelaEngine.Options ──
  /// 关掉音频设备（少一个 virtio-snd + 宿主音频后端，实测能省一点 CPU）。
  bool engineNoAudio = false;

  /// 追加到引擎命令行的参数（高级用法），例如 `-gpu off`、`-smp 1`。
  String engineExtraArgs = '';

  void setEngineNoAudio(bool v) {
    if (engineNoAudio == v) return;
    engineNoAudio = v;
    bridge.setUiPref('engine_no_audio', v ? '1' : '0');
    notifyListeners();
  }

  void setEngineExtraArgs(String v) {
    if (engineExtraArgs == v) return;
    engineExtraArgs = v;
    bridge.setUiPref('engine_extra_args', v);
    notifyListeners();
  }
  /// Active shell destination. Lives here rather than in the shell's State so a page
  /// can send the user somewhere else (e.g. 设备 → 模拟器 after a start).
  int page = 0;
  int grpcPort =
      int.tryParse(const String.fromEnvironment('VELA_GRPC_PORT', defaultValue: '8554')) ?? 8554;
  String remoteHost = const String.fromEnvironment('VELA_GRPC_HOST', defaultValue: '127.0.0.1');
  bool useRemoteEndpoint = false;
  String? notice;

  AppState() {
    bridge.listenEvents(onEvent);
  }

  void onEvent(Map<String, Object?> e) {
    final t = e['type'];
    switch (t) {
      case 'status':
        bridge.status = EngineStatus.fromMap(e);
        break;
      case 'devices':
        bridge.applyDeviceEvent(e);
        break;
      case 'images':
        bridge.applyImageEvent(e);
        break;
      case 'native':
        bridge.native = NativeRuntimeStatus.fromMap(e);
        break;
      case 'toolchain':
        bridge.toolchain = ToolchainStatus.fromMap(e);
        projects.onEvent(e);
        break;
      case 'log':
        bridge.addLog('${e['line']}');
        break;
      case 'build':
      case 'buildDone':
      case 'installed':
      case 'watch':
        projects.onEvent(e);
        break;
      case 'download':
        bridge.trackDownload(e);
        bridge.addLog(
            '[download] ${e['imageType'] ?? e['name']} ${e['done']}/${e['total']} ${e['phase']}');
        break;
    }
    notifyListeners();
  }

  /// 首次启动显示进入引导；看过之后写进原生 prefs。
  bool needsOnboarding = false;

  /// 设置页「重看引导」。
  Future<void> showOnboarding() async {
    needsOnboarding = true;
    notifyListeners();
  }

  Future<void> finishOnboarding() async {
    needsOnboarding = false;
    notifyListeners();
    await bridge.setUiPref('onboarding_done', 'true');
  }

  Future<void> boot() async {
    await bridge.refreshAll();
    try {
      needsOnboarding = !await bridge.onboardingDone();
      liquidGlassNav = await bridge.uiPref('glass_nav') == '1';
      final sf = await bridge.uiPref('stream_frames');
      if (sf == '0') bridge.streamFrames = false;
      engineNoAudio = await bridge.uiPref('engine_no_audio') == '1';
      controlEnabled = await bridge.uiPref('control_enabled') == '1';
      final cp = int.tryParse(await bridge.uiPref('control_port'));
      if (cp != null && cp > 0) controlPort = cp;
      if (controlEnabled) await _startControl();
      engineExtraArgs = await bridge.uiPref('engine_extra_args');
    } catch (_) {
      needsOnboarding = false; // 桌面/无原生宿主时不弹
    }
    try {
      await bridge.refreshNative();
      await bridge.refreshToolchain();
    } catch (_) {
      // 单个探测失败不该拖死整个启动流程（状态卡会显示为未知）。
    }
    if (bridge.devices.isNotEmpty) {
      final keep = selected == null
          ? null
          : bridge.devices.where((d) => d.avdId == selected!.avdId).toList();
      select(keep != null && keep.isNotEmpty
          ? keep.first
          : bridge.devices.firstWhere((d) => d.avdId == 'xiaomi_s4',
              orElse: () => bridge.devices.first));
    }
    // Desktop / no plugin: talk straight to an emulator someone else started.
    useRemoteEndpoint = !bridge.nativeAvailable;
    notifyListeners();
    // No native host (desktop, or the plugin is missing): the only useful thing to
    // do is attach to an emulator that is already running.
    if (useRemoteEndpoint) {
      try {
        await bridge.connect(host: remoteHost, port: grpcPort);
      } catch (_) {
        // leave disconnected; Settings has an explicit connect button
      }
    }
  }

  void select(DeviceProfile d) {
    selected = d;
    bridge.expectedWidth = d.width;
    bridge.expectedHeight = d.height;
    notifyListeners();
  }

  void setLiquidGlassNav(bool v) {
    if (liquidGlassNav == v) {
      return;
    }
    liquidGlassNav = v;
    // 记住开关：否则每次重装/重启都要重新打开。
    bridge.setUiPref('glass_nav', v ? '1' : '0');
    notifyListeners();
  }

  void setPage(int i) {
    if (i == page) return;
    page = i;
    notice = null;
    notifyListeners();
  }

  Future<void> run(String? message) async {
    notice = message;
    notifyListeners();
  }

  /// 引擎启动过程中为 true（解包 + 拉起 + 等 gRPC），控制区据此显示进度。
  bool engineStarting = false;

  Future<void> startSelected() async {
    final d = selected;
    if (d == null || engineStarting) return;
    engineStarting = true;
    notifyListeners();
    try {
      if (!useRemoteEndpoint) {
        final launch = await bridge.startEngine(
          d.avdId,
          grpcPort: grpcPort,
          verbose: verboseEngine,
          noAudio: engineNoAudio,
          extraArgs: engineExtraArgs.trim().isEmpty ? null : engineExtraArgs.trim(),
        );
        final reason = _launchReason(launch);
        if (reason != null && launch?['running'] != true) {
          await run('启动失败：$reason');
          notifyListeners();
          return;
        }
        // 引擎进程起来之后 gRPC 还要等一会儿才监听（客机启动 + 引擎自己起服务），
        // 所以这里要重试：早先只试一次，端口还没开就判「未就绪」，表盘再也出不来。
        Object? lastError;
        var connected = false;
        for (var attempt = 0; attempt < 40; attempt++) {
          try {
            await bridge.connect(host: '127.0.0.1', port: grpcPort);
            connected = true;
            break;
          } catch (e) {
            lastError = e;
            if (attempt == 0) {
              await run('等待引擎的 gRPC 端口 $grpcPort …');
              notifyListeners();
            }
            await Future<void>.delayed(const Duration(seconds: 2));
          }
        }
        if (!connected) {
          // The engine answered but nothing serves gRPC on the port: report the
          // engine's own reason, which is actionable, not the transport error.
          await run('引擎未就绪：${reason ?? '$lastError'}');
          notifyListeners();
          return;
        }
      } else {
        await bridge.connect(host: remoteHost, port: grpcPort);
      }
      await run('引擎已连接 ${bridge.grpc.host}:${bridge.grpc.port}');
    } catch (e) {
      await run('启动失败: $e');
    }
    engineStarting = false;
    notifyListeners();
  }

  /// Why a launch failed, in the clearest wording the host gave us.
  String? _launchReason(Map<Object?, Object?>? launch) {
    if (launch == null) return null;
    final err = launch['error'];
    if (err is String && err.isNotEmpty) return err;
    final problems = launch['problems'];
    if (problems is List && problems.isNotEmpty) return problems.map((e) => '$e').join('；');
    final avdProblems = launch['avdProblems'];
    if (avdProblems is List && avdProblems.isNotEmpty) {
      return avdProblems.map((e) => '$e').join('；');
    }
    return null;
  }

  Future<void> stop() async {
    try {
      await bridge.disconnect();
      if (!useRemoteEndpoint) await bridge.stopEngine();
    } catch (e) {
      await run('停止失败: $e');
    }
    notifyListeners();
  }


  /// Saves the current framebuffer as a PNG. Uses the native files dir when the
  /// host is present, otherwise the Dart temp dir.
  Future<void> snapshot() async {
    try {
      final paths = await bridge.paths() ?? bridge.status.paths;
      final base = paths.entries
          .map((e) => e.value.toString())
          .firstWhere((v) => v.contains('/'), orElse: () => '');
      final dir = base.isEmpty ? Directory.systemTemp.path : '$base/snapshots';
      final f = await bridge.saveSnapshot(dir, selected?.avdId ?? 'vela');
      await run('截图已保存: $f');
    } catch (e) {
      await run('截图失败: $e');
    }
  }

  /// 内置的 AI/脚本控制接口（HTTP + MCP 形状）。默认关；开关、端口与 token 都持久化。
  late final VelaControlServer control = VelaControlServer(this);
  bool controlEnabled = false;
  int controlPort = VelaControlServer.defaultPort;

  Future<void> setControlEnabled(bool v) async {
    if (controlEnabled == v) return;
    controlEnabled = v;
    bridge.setUiPref('control_enabled', v ? '1' : '0');
    if (v) {
      await _startControl();
    } else {
      await control.stop();
    }
    notifyListeners();
  }

  Future<void> setControlPort(int p) async {
    if (p == controlPort || p < 1 || p > 65535) return;
    controlPort = p;
    bridge.setUiPref('control_port', '$p');
    if (controlEnabled) await _startControl();
    notifyListeners();
  }

  Future<void> _startControl() async {
    try {
      await control.start(port: controlPort);
      await run('AI 接口已启动：http://127.0.0.1:${control.port}/mcp');
    } catch (e) {
      controlEnabled = false;
      bridge.setUiPref('control_enabled', '0');
      await run('AI 接口启动失败（端口被占？）：$e');
    }
  }

  /// 连拍：按固定间隔连存若干张 PNG，用来记录一段动画/交互。
  ///
  /// 存在公共目录 `/sdcard/Vortex/captures/<时间戳>/`（有「所有文件访问」时），
  /// 否则退回应用私有目录 —— 两条路的最终路径都会报给用户。
  Future<void> burstCapture({int frames = 8, int intervalMs = 300}) async {
    if (!bridge.grpc.connected) {
      await run('未连接模拟器，无法连拍');
      return;
    }
    final stamp = DateTime.now().millisecondsSinceEpoch;
    final publicDir = '/sdcard/Vortex/captures/$stamp';
    var dir = publicDir;
    try {
      await Directory(publicDir).create(recursive: true);
    } catch (_) {
      final paths = await bridge.paths() ?? bridge.status.paths;
      final base = paths.entries
          .map((e) => e.value.toString())
          .firstWhere((v) => v.contains('/'), orElse: () => '');
      dir = base.isEmpty ? Directory.systemTemp.path : '$base/captures/$stamp';
    }
    var saved = 0;
    try {
      for (var i = 0; i < frames; i++) {
        await bridge.saveSnapshot(dir, selected?.avdId ?? 'vela');
        saved++;
        await Future<void>.delayed(Duration(milliseconds: intervalMs));
      }
      await run('连拍完成：$saved 张 → $dir');
    } catch (e) {
      await run('连拍中断（已存 $saved 张）：$e');
    }
  }

  Future<void> createAndSelect(String avdId) async {
    try {
      final r = await bridge.createAvd(avdId);
      final d = bridge.devices.where((x) => x.avdId == avdId).toList();
      if (d.isNotEmpty) select(d.first);
      final problems = r?['problems'];
      final why = problems is List && problems.isNotEmpty
          ? problems.map((e) => '$e').join('；')
          : null;
      await run(why == null ? '已创建虚拟设备 $avdId' : '已创建 $avdId，但还不能启动：$why');
    } catch (e) {
      await run('创建失败: $e');
    }
    notifyListeners();
  }

  Future<void> startAvd(String avdId) async {
    final d = bridge.devices.where((x) => x.avdId == avdId).toList();
    if (d.isNotEmpty) select(d.first);
    await startSelected();
  }

  Future<void> connectRemote() async {
    try {
      final v = await bridge.connect(host: remoteHost, port: grpcPort);
      await run('已连接 $remoteHost:$grpcPort ($v)');
    } catch (e) {
      await run('连接失败: $e');
    }
    notifyListeners();
  }

  /// Re-runs node / aiot-toolkit detection (after authorizing or installing).
  Future<void> recheckToolchain() async {
    await bridge.refreshToolchain();
    final t = bridge.toolchain;
    await run(t.nodeAvailable
        ? 'node ${t.nodeVersion ?? ''}${t.toolkitInstalled ? '，aiot-toolkit 就绪' : '，aiot-toolkit 缺失'}'
        : (t.error ?? 'node 仍不可用'));
    notifyListeners();
  }

  /// Unpacks / installs the bundled aiot-toolkit, then reports what is ready.
  Future<void> installToolchain() async {
    await projects.installToolchain();
    await bridge.refreshToolchain();
    final t = bridge.toolchain;
    if (!t.nodeAvailable) {
      await run(t.error ?? '安装完成，但 node 仍不可用');
    } else if (!t.toolkitInstalled) {
      await run(t.error ?? 'aiot-toolkit 安装失败');
    } else {
      await run('工具链就绪：node ${t.nodeVersion ?? ''}，aiot-toolkit ${t.toolkitVersion ?? ''}');
    }
    notifyListeners();
  }

  @override
  void dispose() {
    control.stop();
    bridge.dispose();
    super.dispose();
  }
}

class AppScope extends InheritedNotifier<AppState> {
  const AppScope({super.key, required AppState state, required super.child})
      : super(notifier: state);

  static AppState of(BuildContext context) =>
      context.dependOnInheritedWidgetOfExactType<AppScope>()!.notifier!;
}
