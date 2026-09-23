import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../state/app_state.dart';
import '../theme/app_theme.dart';
import '../widgets/common.dart';
import 'shell.dart';

class LogsScreen extends StatefulWidget {
  const LogsScreen({super.key});
  @override
  State<LogsScreen> createState() => _LogsScreenState();
}

class _LogsScreenState extends State<LogsScreen> {
  int tab = 0;
  List<String> lines = const [];
  bool autoRefresh = true;

  Future<void> load() async {
    final state = AppScope.of(context);
    final l = switch (tab) {
      1 => await state.bridge.fetchSerialLog(),
      _ => await state.bridge.fetchLogs(),
    };
    if (!mounted) return;
    setState(() => lines = l.isEmpty ? state.bridge.logLines : l);
  }

  Timer? _auto;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      load();
      _auto = Timer.periodic(const Duration(seconds: 2), (_) {
        if (autoRefresh) load();
      });
    });
  }

  @override
  void dispose() {
    _auto?.cancel();
    super.dispose();
  }

  /// 自动刷新开关：刷屏时想细看某一行，可以先关掉。
  void _toggleAuto() {
    setState(() => autoRefresh = !autoRefresh);
  }

  @override
  Widget build(BuildContext context) {
    final state = AppScope.of(context);
    final b = state.bridge;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        const SizedBox(height: Insets.xs),
        Padding(
          padding: const EdgeInsets.fromLTRB(Insets.lg, 0, Insets.lg, Insets.md),
          child: SegmentedButton<int>(
            segments: const [
              ButtonSegment(value: 0, label: Text('引擎')),
              ButtonSegment(value: 1, label: Text('串口')),
              ButtonSegment(value: 2, label: Text('通道')),
            ],
            selected: {tab},
            showSelectedIcon: false,
            onSelectionChanged: (s) {
              setState(() => tab = s.first);
              load();
            },
          ),
        ),
        Expanded(
          child: Padding(
            padding: const EdgeInsets.fromLTRB(Insets.lg, 0, Insets.lg, 0),
            child: tab == 2
                ? _ChannelInfo(state: state)
                : LogPane(lines: lines),
          ),
        ),
        Padding(
          // 玻璃底栏打开时这一行会被压在下面，留出占位（其它四页都这么做）。
          padding: EdgeInsets.fromLTRB(
            Insets.lg,
            Insets.md,
            Insets.lg,
            Insets.md +
                (state.liquidGlassNav ? liquidGlassNavReserve(context) : 0),
          ),
          child: Row(
            children: [
              FilledButton.tonalIcon(
                onPressed: load,
                icon: const Icon(Icons.refresh_rounded, size: 18),
                label: const Text('刷新'),
              ),
              const SizedBox(width: Insets.sm),
              OutlinedButton.icon(
                onPressed: _toggleAuto,
                icon: Icon(
                  autoRefresh
                      ? Icons.pause_circle_outline
                      : Icons.play_circle_outline,
                  size: 18,
                ),
                label: Text(autoRefresh ? '暂停' : '续播'),
              ),
              const SizedBox(width: Insets.sm),
              OutlinedButton.icon(
                onPressed: lines.isEmpty
                    ? null
                    : () async {
                        await Clipboard.setData(
                            ClipboardData(text: lines.join('\n')));
                        if (context.mounted) {
                          ScaffoldMessenger.of(context).showSnackBar(
                              const SnackBar(content: Text('已复制到剪贴板')));
                        }
                      },
                icon: const Icon(Icons.copy_all_outlined, size: 18),
                label: const Text('复制'),
              ),
              const Spacer(),
              StatusChip(label: '${lines.length} 条'),
              const SizedBox(width: Insets.sm),
              StatusChip(
                label: b.nativeAvailable ? '本机服务' : '服务未就绪',
                tone: b.nativeAvailable ? ChipTone.good : ChipTone.warn,
              ),
            ],
          ),
        ),
      ],
    );
  }
}

class _ChannelInfo extends StatelessWidget {
  const _ChannelInfo({required this.state});
  final AppState state;

  @override
  Widget build(BuildContext context) {
    final b = state.bridge;
    final f = b.frames;
    return ListView(
      children: [
        SectionCard(
          title: 'gRPC 通道',
          icon: Icons.lan_outlined,
          children: [
            InfoRow(
              label: 'endpoint',
              value: '${b.grpc.host ?? '-'}:${b.grpc.port ?? '-'}',
              icon: Icons.link,
            ),
            InfoRow(
              label: '状态',
              value: b.grpc.connected ? '已连接' : '未连接',
              icon: b.grpc.connected ? Icons.check_circle : Icons.cancel,
            ),
            InfoRow(
              label: '取帧间隔',
              value: '${b.frameIntervalMs} ms',
              icon: Icons.timer_outlined,
            ),
            InfoRow(
              label: 'pump',
              value: b.pumpError ?? 'ok',
              icon: Icons.sync,
            ),
          ],
        ),
        const SizedBox(height: Insets.md),
        SectionCard(
          title: '画面',
          icon: Icons.image_outlined,
          children: [
            InfoRow(
              label: '分辨率',
              value: f.width > 0 ? '${f.width}x${f.height}' : '-',
              icon: Icons.aspect_ratio,
            ),
            InfoRow(label: '帧数', value: '${f.frames}', icon: Icons.tag),
            InfoRow(
              label: 'fps',
              value: f.fps.toStringAsFixed(2),
              icon: Icons.speed,
            ),
            InfoRow(label: 'input', value: b.inputError ?? 'ok', icon: Icons.touch_app_outlined),
          ],
        ),
        const SizedBox(height: Insets.md),
        SectionCard(
          title: '本机服务',
          icon: Icons.memory,
          children: [
            InfoRow(
              label: '可用',
              value: b.nativeAvailable ? 'yes' : 'missing plugin',
              icon: b.nativeAvailable ? Icons.check_circle : Icons.cancel,
            ),
            InfoRow(
              label: 'last error',
              value: b.lastNativeError ?? '-',
              icon: Icons.report_outlined,
            ),
            InfoRow(
              label: '运行环境',
              value: b.native.ready ? 'jniLibs 就位' : '负载缺失',
              icon: b.native.ready ? Icons.check_circle : Icons.lock_outline,
            ),
            InfoRow(
              label: '工具链',
              value: b.toolchain.nodeAvailable
                  ? 'node ${b.toolchain.nodeVersion ?? ''}'
                  : 'node 缺失 (${b.toolchain.execMode})',
              icon: b.toolchain.nodeAvailable ? Icons.check_circle : Icons.cancel,
            ),
            InfoRow(
              label: '引擎',
              value: b.status.running ? 'pid ${b.status.pid}' : '已停止',
              icon: Icons.memory,
            ),
          ],
        ),
        const SizedBox(height: Insets.md),
      ],
    );
  }
}
