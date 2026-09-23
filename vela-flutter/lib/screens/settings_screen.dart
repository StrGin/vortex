import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../models/vela_models.dart';
import '../state/app_state.dart';
import '../theme/app_theme.dart';
import '../widgets/common.dart';
import 'shell.dart';

class SettingsScreen extends StatefulWidget {
  const SettingsScreen({super.key});
  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  late final TextEditingController host = TextEditingController();
  late final TextEditingController port = TextEditingController();
  late final TextEditingController _extraArgs =
      TextEditingController(text: AppScope.of(context).engineExtraArgs);

  /// 记录上次同步过来的值：只有外部真的变了才覆写输入框，否则键盘弹出 /
  /// 旋屏触发的 didChangeDependencies 会把用户正在敲的内容清掉。
  String _syncedHost = '';
  int _syncedPort = -1;
  bool _dirty = false;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final s = AppScope.of(context);
    if (!_dirty || (s.remoteHost != _syncedHost || s.grpcPort != _syncedPort)) {
      host.text = s.remoteHost;
      port.text = '${s.grpcPort}';
      _syncedHost = s.remoteHost;
      _syncedPort = s.grpcPort;
    }
  }

  @override
  void dispose() {
    host.dispose();
    port.dispose();
    _extraArgs.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final s = AppScope.of(context);
    final b = s.bridge;

    return ListView(
      padding: EdgeInsets.fromLTRB(
              Insets.lg,
              Insets.xs,
              Insets.lg, Insets.xxl +
              (s.liquidGlassNav ? liquidGlassNavReserve(context) : 0)),
      children: [
        SectionCard(
          title: '连接方式',
          icon: Icons.hub_outlined,
          trailing: StatusChip(
            label: s.useRemoteEndpoint ? '外部模拟器' : '本机引擎',
            icon: s.useRemoteEndpoint ? Icons.lan : Icons.phone_android,
            tone: s.useRemoteEndpoint ? ChipTone.warn : ChipTone.good,
          ),
          children: [
            SwitchListTile(
              contentPadding: EdgeInsets.zero,
              value: s.useRemoteEndpoint,
              title: const Text('连接外部模拟器'),
              subtitle: const Text('不启动本机引擎，直接连电脑上模拟器的 gRPC 端口'),
              onChanged: (v) => setState(() => s.useRemoteEndpoint = v),
            ),
            const SizedBox(height: Insets.md),
            TextField(
              controller: host,
              decoration: const InputDecoration(labelText: 'gRPC 主机'),
              onChanged: (v) {
                _dirty = true;
                s.remoteHost = v;
              },
            ),
            const SizedBox(height: Insets.md),
            TextField(
              controller: port,
              keyboardType: TextInputType.number,
              decoration: const InputDecoration(
                labelText: 'gRPC 端口',
                helperText: '电脑上模拟器的 gRPC 端口，默认 8554',
              ),
              onChanged: (v) {
                _dirty = true;
                s.grpcPort = int.tryParse(v) ?? 8554;
              },
            ),
            const SizedBox(height: Insets.lg),
            Align(
              alignment: Alignment.centerLeft,
              child: FilledButton.icon(
                onPressed: () {
                  _dirty = false;
                  s.connectRemote();
                },
                icon: const Icon(Icons.link, size: 18),
                label: const Text('连接并取帧'),
              ),
            ),
          ],
        ),
        const SizedBox(height: Insets.md),
        _NativeRuntimeCard(state: s),
        const SizedBox(height: Insets.md),
        _ToolchainCard(state: s),
        const SizedBox(height: Insets.md),
        SectionCard(
          title: '引擎',
          icon: Icons.memory,
          children: [
            SwitchListTile(
              contentPadding: EdgeInsets.zero,
              value: s.verboseEngine,
              title: const Text('详细引擎日志'),
              subtitle: const Text('排查启动问题时打开，日志会多很多'),
              onChanged: (v) => setState(() => s.verboseEngine = v),
            ),
            const SizedBox(height: Insets.md),
            Row(
              children: [
                Expanded(
                  child: Text('取帧间隔',
                      style: Theme.of(context).textTheme.bodyMedium),
                ),
                StatusChip(label: '${b.frameIntervalMs} ms'),
              ],
            ),
            Slider(
              min: 25,
              max: 500,
              divisions: 19,
              value: b.frameIntervalMs.clamp(25, 500).toDouble(),
              onChanged: (v) => setState(() {
                b.frameIntervalMs = v.round();
                b.startPump();
              }),
            ),
            SwitchListTile(
              contentPadding: EdgeInsets.zero,
              value: s.engineNoAudio,
              title: const Text('关闭引擎音频'),
              subtitle: const Text('手表不需要发声时打开，可省一点电'),
              onChanged: (v) => setState(() => s.setEngineNoAudio(v)),
            ),
            TextField(
              controller: _extraArgs,
              decoration: const InputDecoration(
                labelText: '引擎附加参数',
                helperText: '追加给引擎的参数，如 -gpu off；重启引擎后生效',
              ),
              onChanged: s.setEngineExtraArgs,
            ),
            const SizedBox(height: Insets.md),
            SwitchListTile(
              contentPadding: EdgeInsets.zero,
              value: b.streamFrames,
              title: const Text('流式取帧'),
              subtitle: const Text('引擎主动推画面，更省电、延迟更低；关掉则定时拉取'),
              onChanged: (v) => setState(() => b.setStreamFrames(v)),
            ),
            Text(
              '间隔越小越流畅，代价是 gRPC 取帧的 CPU 占用。',
              style: Theme.of(context).textTheme.bodySmall?.copyWith(
                    color: Theme.of(context).colorScheme.onSurfaceVariant,
                  ),
            ),
          ],
        ),
        const SizedBox(height: Insets.md),
        SectionCard(
          title: '外观',
          icon: Icons.blur_on_outlined,
          trailing: StatusChip(
            label: s.liquidGlassNav ? '液态玻璃' : '默认',
            icon: s.liquidGlassNav ? Icons.blur_on : Icons.blur_linear,
            tone: s.liquidGlassNav ? ChipTone.good : ChipTone.neutral,
          ),
          children: [
            SwitchListTile(
              contentPadding: EdgeInsets.zero,
              value: s.liquidGlassNav,
              title: const Text('底栏液态玻璃'),
              subtitle: const Text('底栏改成悬浮半透明，带滑动指示块'),
              onChanged: (v) => setState(() => s.setLiquidGlassNav(v)),
            ),
            InfoRow(
              label: '进入引导',
              value: '重看一遍',
              icon: Icons.school_outlined,
              onTap: () => s.showOnboarding(),
            ),
          ],
        ),
        const SizedBox(height: Insets.md),
        _ControlApiCard(state: s),
        const SizedBox(height: Insets.md),
        SectionCard(
          title: '诊断',
          icon: Icons.health_and_safety_outlined,
          children: [
            InfoRow(
              label: '本机服务',
              value: b.nativeAvailable ? '可用' : '未注册',
              icon: b.nativeAvailable ? Icons.check_circle : Icons.cancel,
            ),
            InfoRow(
              label: '运行环境',
              value: _nativeLabel(b.native),
              icon: Icons.security,
            ),
            InfoRow(
              label: '工具链执行',
              value: _execModeLabel(b.toolchain.execMode),
              icon: Icons.terminal,
            ),
            InfoRow(
              label: '引擎',
              value: b.status.running ? '运行中 pid ${b.status.pid}' : '已停止',
              icon: Icons.memory,
            ),
            InfoRow(
              label: 'gRPC',
              value: '${b.grpc.host ?? '-'}:${b.grpc.port ?? '-'}',
              icon: Icons.lan_outlined,
            ),
          ],
        ),
        const SizedBox(height: Insets.md),
        _PathsCard(state: s),
      ],
    );
  }
}

String _nativeLabel(NativeRuntimeStatus n) {
  if (n.ready) return '内置（jniLibs）';
  return n.loaderReady || n.stubReady ? '负载不完整' : '负载缺失';
}

String _execModeLabel(String mode) => switch (mode) {
      'local' => '本进程直接执行',
      _ => '未知',
    };

/// 运行环境：引擎与工具链的可执行负载随 APK 分发（jniLibs → /data/app/**/lib/arm64，
/// 应用域允许执行），私有目录里只放数据。所以不再需要 Shizuku，也不再需要 debuggable。
class _NativeRuntimeCard extends StatelessWidget {
  const _NativeRuntimeCard({required this.state});
  final AppState state;

  @override
  Widget build(BuildContext context) {
    final n = state.bridge.native;
    final scheme = Theme.of(context).colorScheme;
    return SectionCard(
      title: '运行环境',
      icon: Icons.security,
      trailing: StatusChip(
        label: _nativeLabel(n),
        tone: n.ready ? ChipTone.good : ChipTone.bad,
      ),
      children: [
        Text(
          '引擎与工具链（node / aiot-toolkit）的 loader 与 trampoline 随 APK 装在 '
          '/data/app/<包名>/lib/arm64 下；其余文件留在应用私有目录，由 loader 直接读。',
          style: Theme.of(context).textTheme.bodySmall?.copyWith(
                color: scheme.onSurfaceVariant,
              ),
        ),
        const SizedBox(height: Insets.md),
        InfoRow(
          label: 'loader',
          value: n.loaderReady ? '就位' : '缺失',
          icon: n.loaderReady ? Icons.check_circle : Icons.cancel,
        ),
        InfoRow(
          label: 'exec trampoline',
          value: n.stubReady ? '就位' : '缺失',
          icon: n.stubReady ? Icons.check_circle : Icons.cancel,
        ),
        InfoRow(
          label: '路径',
          value: n.nativeDir.isEmpty ? '-' : n.nativeDir,
          icon: Icons.folder_outlined,
        ),
      ],
    );
  }
}

/// node + aiot-toolkit detection, with the reason it failed.
class _ToolchainCard extends StatelessWidget {
  const _ToolchainCard({required this.state});
  final AppState state;

  @override
  Widget build(BuildContext context) {
    final t = state.bridge.toolchain;
    final scheme = Theme.of(context).colorScheme;
    return SectionCard(
      title: '工具链',
      icon: Icons.terminal,
      trailing: StatusChip(
        label: t.toolkitInstalled
            ? '就绪'
            : t.nodeAvailable
                ? '待装 toolkit'
                : '不可用',
        tone: t.nodeAvailable ? ChipTone.good : ChipTone.warn,
      ),
      children: [
        InfoRow(
          label: 'node',
          value: t.nodeAvailable ? (t.nodeVersion ?? '可用') : '缺失',
          icon: t.nodeAvailable ? Icons.check_circle : Icons.cancel,
        ),
        InfoRow(
          label: 'aiot-toolkit',
          value: t.toolkitInstalled ? (t.toolkitVersion ?? '已安装') : '缺失',
          icon: t.toolkitInstalled ? Icons.check_circle : Icons.cancel,
        ),
        InfoRow(label: '执行方式', value: _execModeLabel(t.execMode), icon: Icons.bolt),
        if (t.error != null && t.error!.isNotEmpty) ...[
          const SizedBox(height: Insets.sm),
          Text(
            t.error!,
            style: Theme.of(context).textTheme.bodySmall?.copyWith(
                  color: scheme.onSurfaceVariant,
                ),
          ),
        ],
        const SizedBox(height: Insets.md),
        Align(
          alignment: Alignment.centerLeft,
          child: OutlinedButton.icon(
            onPressed: () => state.recheckToolchain(),
            icon: const Icon(Icons.refresh, size: 18),
            label: const Text('重新检测'),
          ),
        ),
      ],
    );
  }
}

class _PathsCard extends StatelessWidget {
  const _PathsCard({required this.state});
  final AppState state;

  @override
  Widget build(BuildContext context) {
    return FutureBuilder(
      future: state.bridge.nativeAvailable
          ? state.bridge.paths()
          : Future.value(null),
      builder: (context, snap) {
        if (snap.connectionState == ConnectionState.waiting &&
            state.bridge.status.paths.isEmpty) {
          return const SectionCard(
            title: '路径',
            icon: Icons.folder_outlined,
            children: [
              Padding(
                padding: EdgeInsets.symmetric(vertical: Insets.md),
                child: LinearProgressIndicator(minHeight: 2),
              ),
            ],
          );
        }
        final data = (snap.data ?? state.bridge.status.paths)
            .cast<Object?, Object?>();
        return SectionCard(
          title: '路径',
          icon: Icons.folder_outlined,
          children: [
            if (data.isEmpty)
              Text(
                '原生宿主不可用（桌面模式），没有本机路径可显示。',
                style: Theme.of(context).textTheme.bodySmall?.copyWith(
                      color: Theme.of(context).colorScheme.onSurfaceVariant,
                    ),
              )
            else
              // 路径很长、还经常要贴给别人排障：点一下直接复制。
              for (final e in data.entries)
                InfoRow(
                  label: '${e.key}',
                  value: '${e.value}',
                  onTap: () async {
                    await Clipboard.setData(ClipboardData(text: '${e.value}'));
                    if (context.mounted) {
                      ScaffoldMessenger.of(context).showSnackBar(
                        SnackBar(content: Text('已复制 ${e.key}')),
                      );
                    }
                  },
                ),
          ],
        );
      },
    );
  }
}

/// 内置 AI/脚本控制接口（HTTP + MCP 形状）：给别的 AI、脚本或电脑上的 MCP 客户端用。
class _ControlApiCard extends StatefulWidget {
  const _ControlApiCard({required this.state});
  final AppState state;

  @override
  State<_ControlApiCard> createState() => _ControlApiCardState();
}

class _ControlApiCardState extends State<_ControlApiCard> {
  late final TextEditingController _port =
      TextEditingController(text: '${widget.state.controlPort}');

  @override
  void dispose() {
    _port.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final s = widget.state;
    final running = s.control.running;
    return SectionCard(
      title: 'AI 接口（MCP / HTTP）',
      icon: Icons.smart_toy_outlined,
      trailing: StatusChip(
        label: running ? '监听 ${s.control.port}' : '已关闭',
        tone: running ? ChipTone.good : ChipTone.neutral,
      ),
      children: [
        SwitchListTile(
          contentPadding: EdgeInsets.zero,
          value: s.controlEnabled,
          title: const Text('启用本地控制接口'),
          subtitle: const Text('给 AI 或脚本用：截屏、点按、构建都在这个接口里'),
          onChanged: (v) => s.setControlEnabled(v),
        ),
        if (s.controlEnabled) ...[
          Row(
            children: [
              SizedBox(
                width: 96,
                child: TextField(
                  controller: _port,
                  keyboardType: TextInputType.number,
                  decoration: const InputDecoration(labelText: '端口', isDense: true),
                  onSubmitted: (v) =>
                      s.setControlPort(int.tryParse(v) ?? s.controlPort),
                ),
              ),
              const SizedBox(width: Insets.sm),
              Expanded(
                child: SelectableText(
                  'token: ${s.control.token}',
                  style: Theme.of(context).textTheme.bodySmall,
                ),
              ),
            ],
          ),
          const SizedBox(height: Insets.sm),
          Align(
            alignment: Alignment.centerLeft,
            child: Wrap(
              spacing: Insets.sm,
              children: [
                FilledButton.tonalIcon(
                  icon: const Icon(Icons.copy_all_outlined, size: 18),
                  label: const Text('复制 MCP 地址'),
                  onPressed: () async {
                    final url =
                        'http://127.0.0.1:${s.control.port}/mcp?token=${s.control.token}';
                    await Clipboard.setData(ClipboardData(text: url));
                    if (context.mounted) {
                      ScaffoldMessenger.of(context).showSnackBar(
                          SnackBar(content: Text('已复制 $url')));
                    }
                  },
                ),
              ],
            ),
          ),
          const SizedBox(height: Insets.sm),
          for (final line in s.control.endpointSummary())
            Text(
              line,
              style: Theme.of(context).textTheme.bodySmall?.copyWith(
                    fontFamily: 'monospace',
                    color: Theme.of(context).colorScheme.onSurfaceVariant,
                  ),
            ),
          const SizedBox(height: Insets.sm),
          Text(
            '示例：curl -H "x-vela-token: ${s.control.token}" '
            'http://127.0.0.1:${s.control.port}/status',
            style: Theme.of(context).textTheme.bodySmall?.copyWith(
                  color: Theme.of(context).colorScheme.onSurfaceVariant,
                ),
          ),
        ],
      ],
    );
  }
}
