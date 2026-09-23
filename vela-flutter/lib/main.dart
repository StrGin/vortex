import 'dart:ui' show PlatformDispatcher;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:liquid_glass_widgets/liquid_glass_widgets.dart';

import 'screens/devices_screen.dart';
import 'screens/logs_screen.dart';
import 'screens/onboarding_screen.dart';
import 'screens/projects_screen.dart';
import 'screens/settings_screen.dart';
import 'screens/shell.dart';
import 'screens/simulator_screen.dart';
import 'state/app_state.dart';
import 'theme/app_theme.dart';

void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  // Edge-to-edge: let the app draw behind the status and navigation bars so the system
  // bar areas show the app's own surface instead of the Android theme's black band.
  SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);
  // 液态玻璃底栏的 shader：启动时预载（纯磁盘→内存，不画东西）。
  await LiquidGlassWidgets.initialize();
  runApp(
    LiquidGlassWidgets.wrap(
      // MaterialApp 必须给这个回调，否则玻璃的明暗跟 App 的 ThemeMode 对不上。
      brightnessResolver: Theme.maybeBrightnessOf,
      child: const VelaApp(),
    ),
  );
}

class VelaApp extends StatefulWidget {
  const VelaApp({super.key});
  @override
  State<VelaApp> createState() => _VelaAppState();
}

class _VelaAppState extends State<VelaApp> with WidgetsBindingObserver {
  final AppState state = AppState();

  /// Seed used before (or instead of) the platform accent. Matches the Vela green the
  /// app has always used as its fallback.
  static const Color _fallbackSeed = Color(0xFF3BA776);

  Color? _systemAccent;
  Brightness _brightness = Brightness.dark;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _brightness = PlatformDispatcher.instance.platformBrightness;
    state.addListener(_onState);
    state.boot();
    _loadAccent();
  }

  void _onState() => setState(() {});

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    state.removeListener(_onState);
    state.dispose();
    super.dispose();
  }

  @override
  void didChangePlatformBrightness() {
    final next = PlatformDispatcher.instance.platformBrightness;
    if (next != _brightness) setState(() => _brightness = next);
  }

  Future<void> _loadAccent() async {
    final accent = await state.bridge.systemAccent();
    if (!mounted || accent == null) return;
    setState(() => _systemAccent = accent);
  }

  @override
  Widget build(BuildContext context) {
    final isDark = _brightness == Brightness.dark;
    // Material You: adopt the wallpaper accent on Android 12+, the same way the Java
    // side resolved android.R.color.system_accent1_* before this UI existed.
    final seed = _systemAccent ?? _fallbackSeed;

    return AppScope(
      state: state,
      child: MaterialApp(
        title: 'Vortex',
        debugShowCheckedModeBanner: false,
        theme: buildVelaTheme(
          ColorScheme.fromSeed(seedColor: seed, brightness: Brightness.light),
        ),
        darkTheme: buildVelaTheme(
          ColorScheme.fromSeed(seedColor: seed, brightness: Brightness.dark),
        ),
        themeMode: ThemeMode.system,
        // 包要求 MaterialApp 下有个 Material 祖先（否则玻璃里的文字会带下划线）
        builder: (context, child) =>
            Material(type: MaterialType.transparency, child: child ?? const SizedBox.shrink()),
        home: AnnotatedRegion<SystemUiOverlayStyle>(
          // Transparent bars: the Scaffold's scheme.surface paints behind them, so the
          // status bar area matches the app instead of showing a black bar.
          value: SystemUiOverlayStyle(
            statusBarColor: Colors.transparent,
            systemNavigationBarColor: Colors.transparent,
            statusBarIconBrightness:
                isDark ? Brightness.light : Brightness.dark,
            systemNavigationBarIconBrightness:
                isDark ? Brightness.light : Brightness.dark,
            statusBarBrightness: isDark ? Brightness.dark : Brightness.light,
          ),
          child: Builder(builder: _buildShell),
        ),
      ),
    );
  }

  Widget _buildShell(BuildContext context) {
    // 首次启动（或设置页点了「重看引导」）先过一遍引导。
    if (state.needsOnboarding) {
      return OnboardingScreen(state: state);
    }
    final b = state.bridge;
    final proj = state.projects;
    final running = b.status.running;

    return Shell(
      pages: [
        ShellPage(
          label: '模拟器',
          icon: Icons.watch_outlined,
          selectedIcon: Icons.watch_rounded,
          title: '模拟器',
          subtitle: state.selected == null
              ? '未选择虚拟设备'
              : '${state.selected!.avdId}${running ? ' · 运行中' : ''}',
          actions: [
            IconButton(
              onPressed: b.grpc.connected ? () => b.forceFrame() : null,
              tooltip: '重新取帧',
              icon: const Icon(Icons.refresh_rounded),
            ),
            IconButton(
              onPressed: b.grpc.connected ? () => state.snapshot() : null,
              tooltip: '保存截图',
              icon: const Icon(Icons.photo_camera_outlined),
            ),
            IconButton(
              onPressed: b.grpc.connected ? () => state.burstCapture() : null,
              tooltip: '连拍 8 张（每 300ms 一张）',
              icon: const Icon(Icons.burst_mode_outlined),
            ),
          ],
          body: const SimulatorScreen(),
        ),
        ShellPage(
          label: '设备',
          icon: Icons.devices_other_outlined,
          selectedIcon: Icons.devices_other_rounded,
          title: '设备与镜像',
          subtitle: '档案 ${b.devices.length} · 虚拟设备 ${b.avds.length}'
              ' · 镜像 ${b.images.where((e) => e.complete).length}/${b.images.length}',
          actions: [
            IconButton(
              onPressed: state.boot,
              tooltip: '刷新',
              icon: const Icon(Icons.refresh_rounded),
            ),
          ],
          body: const DevicesScreen(),
        ),
        ShellPage(
          label: '工程',
          icon: Icons.code_outlined,
          selectedIcon: Icons.code_rounded,
          title: '工程',
          subtitle: proj.projects.isEmpty
              ? '用官方模板新建一个快应用工程'
              : '${proj.projects.length} 个 · '
                  '${proj.status.nodeAvailable && proj.status.toolkitInstalled ? '工具链就绪' : '工具链未装'}',
          actions: [
            IconButton(
              onPressed: proj.refresh,
              tooltip: '刷新',
              icon: const Icon(Icons.refresh_rounded),
            ),
          ],
          body: const ProjectsScreen(),
        ),
        ShellPage(
          label: '日志',
          icon: Icons.terminal_outlined,
          selectedIcon: Icons.terminal_rounded,
          title: '日志',
          subtitle: '引擎 / 串口 / 传输通道',
          body: const LogsScreen(),
        ),
        ShellPage(
          label: '设置',
          icon: Icons.settings_outlined,
          selectedIcon: Icons.settings_rounded,
          title: '设置',
          subtitle: '引擎、运行时与路径',
          body: const SettingsScreen(),
        ),
      ],
    );
  }
}
