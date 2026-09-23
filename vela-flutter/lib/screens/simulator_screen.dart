import 'package:flutter/material.dart';

import '../state/app_state.dart';
import '../state/vela_grpc.dart';
import '../theme/app_theme.dart';
import '../widgets/common.dart';
import '../widgets/sensor_panel.dart';
import '../widgets/watch_view.dart';
import 'shell.dart';

class SimulatorScreen extends StatefulWidget {
  const SimulatorScreen({super.key});

  @override
  State<SimulatorScreen> createState() => _SimulatorScreenState();
}

class _SimulatorScreenState extends State<SimulatorScreen> {
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) AppScope.of(context).projects.refresh();
    });
  }

  @override
  Widget build(BuildContext context) {
    final state = AppScope.of(context);
    final b = state.bridge;
    final connected = b.grpc.connected;

    return ListView(
      padding: EdgeInsets.fromLTRB(
              Insets.lg,
              Insets.xs,
              Insets.lg, Insets.xxl +
              (state.liquidGlassNav ? liquidGlassNavReserve(context) : 0)),
      children: [
        if (state.notice != null) ...[
          NoticeBanner(message: state.notice!, tone: ChipTone.warn),
          const SizedBox(height: Insets.md),
        ],
        SectionCard(
          children: [
            _DevicePickerRow(state: state, connected: connected),
            const SizedBox(height: Insets.md),
            // 表盘高度跟着屏幕走：窄屏不至于顶满，宽屏（rail 布局）也不再是
            // 大卡片里缩着一个小方块。
            SizedBox(
              height: (MediaQuery.sizeOf(context).height * 0.42)
                  .clamp(240.0, 460.0),
              child: WatchView(profile: state.selected),
            ),
            const SizedBox(height: Insets.md),
            _StatusRow(state: state),
          ],
        ),
        const SizedBox(height: Insets.md),
        _ControlPad(state: state),
        const SizedBox(height: Insets.md),
        const SensorPanel(),
        const SizedBox(height: Insets.md),
        _GuestApps(state: state),
      ],
    );
  }
}

/// 表盘上方的设备选择行：点一下弹出已创建的虚拟设备列表。
/// 以前这里是一行纯文本标题，启动入口只存在于「设备」页，两边都别扭。
class _DevicePickerRow extends StatelessWidget {
  const _DevicePickerRow({required this.state, required this.connected});

  final AppState state;
  final bool connected;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final selected = state.selected;
    return InkWell(
      borderRadius: Radii.fieldR,
      onTap: () => _pickDevice(context, state),
      child: Padding(
        padding: const EdgeInsets.symmetric(
            horizontal: Insets.sm, vertical: Insets.sm),
        child: Row(
          children: [
            Icon(Icons.watch_outlined, size: 18, color: scheme.onSurfaceVariant),
            const SizedBox(width: Insets.sm),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(
                    selected?.avdId ?? '选择设备',
                    style: Theme.of(context).textTheme.titleSmall?.copyWith(
                          fontWeight: FontWeight.w600,
                          color: selected == null
                              ? scheme.primary
                              : scheme.onSurface,
                        ),
                  ),
                  Text(
                    selected == null
                        ? '点这里挑一台虚拟设备'
                        : '${selected.width}x${selected.height} · ${selected.imageType}',
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: Theme.of(context).textTheme.bodySmall?.copyWith(
                          color: scheme.onSurfaceVariant,
                        ),
                  ),
                ],
              ),
            ),
            StatusChip(
              label: connected ? '已连接' : '未连接',
              icon: connected ? Icons.link : Icons.link_off,
              tone: connected ? ChipTone.good : ChipTone.neutral,
            ),
            const SizedBox(width: Insets.xs),
            Icon(Icons.expand_more_rounded, size: 20, color: scheme.onSurfaceVariant),
          ],
        ),
      ),
    );
  }
}

/// 设备选择弹层：列出已创建的 AVD；没有设备时引导去「设备」页创建。
Future<void> _pickDevice(BuildContext context, AppState state) async {
  final devices = state.bridge.devices;
  await showModalBottomSheet<void>(
    context: context,
    showDragHandle: true,
    builder: (ctx) {
      final scheme = Theme.of(ctx).colorScheme;
      return SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(
                  Insets.lg, 0, Insets.lg, Insets.sm),
              child: Row(
                children: [
                  Expanded(
                    child: Text('选择设备',
                        style: Theme.of(ctx).textTheme.titleMedium),
                  ),
                  TextButton(
                    onPressed: () {
                      Navigator.of(ctx).pop();
                      state.setPage(1); // 设备页
                    },
                    child: const Text('去设备页管理'),
                  ),
                ],
              ),
            ),
            if (devices.isEmpty)
              Padding(
                padding: const EdgeInsets.fromLTRB(
                    Insets.lg, Insets.sm, Insets.lg, Insets.xl),
                child: Text(
                  '还没有虚拟设备：去「设备」页挑一个机型点「创建」，或先下载系统镜像。',
                  style: Theme.of(ctx).textTheme.bodySmall?.copyWith(
                        color: scheme.onSurfaceVariant,
                      ),
                ),
              )
            else
              for (final d in devices)
                ListTile(
                  leading: Icon(
                    d.avdId == state.selected?.avdId
                        ? Icons.radio_button_checked
                        : Icons.radio_button_unchecked,
                    color: d.avdId == state.selected?.avdId
                        ? scheme.primary
                        : scheme.onSurfaceVariant,
                  ),
                  title: Text(d.avdId),
                  subtitle: Text('${d.width}x${d.height} · ${d.imageType}'),
                  onTap: () {
                    state.select(d);
                    Navigator.of(ctx).pop();
                  },
                ),
          ],
        ),
      );
    },
  );
}

class _StatusRow extends StatefulWidget {
  const _StatusRow({required this.state});
  final AppState state;

  @override
  State<_StatusRow> createState() => _StatusRowState();
}

class _StatusRowState extends State<_StatusRow> {
  /// 解码耗时/引擎推送速率这些诊断项默认收起，避免七个 chip 糊一行。
  bool showDiag = false;

  @override
  Widget build(BuildContext context) {
    final state = widget.state;
    final b = state.bridge;
    // 听 stats（2Hz）而不是每帧：fps/帧数不需要逐帧刷新，
    // 逐帧重建这排 chip 纯属白烧 CPU。
    return AnimatedBuilder(
      animation: b.frames.stats,
      builder: (context, _) => Wrap(
        spacing: Insets.sm,
        runSpacing: Insets.sm,
        children: [
          StatusChip(
            label: b.frames.width > 0
                ? '${b.frames.width}x${b.frames.height}'
                : '无画面',
            icon: Icons.aspect_ratio,
          ),
          StatusChip(
            label: '${b.frames.fps.toStringAsFixed(0)} fps',
            icon: Icons.speed,
            tone: b.frames.fps > 1 ? ChipTone.good : ChipTone.neutral,
          ),
          if (showDiag) ...[
            StatusChip(
              label: '解码 ${b.frames.decodeMs.toStringAsFixed(1)}ms',
              icon: Icons.memory_outlined,
              tone: b.frames.decodeMs > 25 ? ChipTone.warn : ChipTone.neutral,
            ),
            StatusChip(
              label: '引擎 ${b.frames.receivedFps.toStringAsFixed(0)}/s',
              icon: Icons.bolt_outlined,
              tone: b.frames.receivedFps > b.frames.fps + 1
                  ? ChipTone.warn
                  : ChipTone.neutral,
            ),
          ],
          StatusChip(
            label: '帧 ${b.frames.frames}',
            icon: Icons.photo_size_select_actual_outlined,
          ),
          StatusChip(
            label: b.status.running
                ? '引擎 pid ${b.status.pid}'
                : '引擎已停止',
            icon: b.status.running ? Icons.memory : Icons.power_settings_new,
            tone: b.status.running ? ChipTone.good : ChipTone.neutral,
          ),
          StatusChip(
            label: '连接 ${b.grpc.host ?? '-'}:${b.grpc.port ?? '-'}',
            icon: Icons.lan_outlined,
            tone: b.grpc.connected ? ChipTone.good : ChipTone.bad,
          ),
          if (b.pumpError != null)
            StatusChip(label: 'pump ${b.pumpError}', tone: ChipTone.bad),
          if (b.inputError != null)
            StatusChip(label: 'input ${b.inputError}', tone: ChipTone.bad),
          // 诊断开关自己也是一个 chip：点一下展开/收起上面两项。
          GestureDetector(
            onTap: () => setState(() => showDiag = !showDiag),
            child: StatusChip(
              label: showDiag ? '收起诊断' : '诊断',
              icon: showDiag ? Icons.expand_less : Icons.monitor_heart_outlined,
            ),
          ),
        ],
      ),
    );
  }
}

class _ControlPad extends StatelessWidget {
  const _ControlPad({required this.state});
  final AppState state;

  @override
  Widget build(BuildContext context) {
    final b = state.bridge;
    final on = b.grpc.connected;
    final starting = state.engineStarting;
    final hasDevice = state.selected != null;

    Widget btn(String label, IconData icon, VoidCallback? onTap) =>
        FilledButton.tonalIcon(
          onPressed: onTap,
          icon: Icon(icon, size: 18),
          label: Text(label),
        );

    return SectionCard(
      title: '控制',
      icon: Icons.tune,
      trailing: starting
          ? const SizedBox(
              width: 16,
              height: 16,
              child: CircularProgressIndicator(strokeWidth: 2),
            )
          : null,
      children: [
        // 没选设备时只留一条灰字提示，设备选择入口就在上方那一行。
        if (!hasDevice) ...[
          Text(
            '先在顶部选一台设备，再启动引擎。',
            style: Theme.of(context).textTheme.bodySmall?.copyWith(
                  color: Theme.of(context).colorScheme.onSurfaceVariant,
                ),
          ),
        ] else ...[
          Row(
            children: [
              Expanded(
                child: FilledButton.icon(
                  onPressed: starting
                      ? null
                      : (on
                          ? () => _confirmRestart(context)
                          : () => state.startSelected()),
                  icon: Icon(
                    on ? Icons.restart_alt : Icons.play_arrow_rounded,
                    size: 18,
                  ),
                  label: Text(starting ? '启动中…' : (on ? '重新启动' : '启动引擎')),
                ),
              ),
              const SizedBox(width: Insets.sm),
              FilledButton.tonal(
                onPressed: (!on || starting) ? null : () => state.stop(),
                child: const Text('停止'),
              ),
            ],
          ),
        ],
        const SizedBox(height: Insets.sm),
        // 次操作：客机按键，按一下就走。
        Wrap(
          spacing: Insets.sm,
          runSpacing: Insets.sm,
          children: [
            btn('Home', Icons.home_outlined,
                on ? () => b.gesture(() => b.grpc.sendKey('GoHome')) : null),
            // 「返回」已去掉：两套镜像（手表/手环）都不认 GoBack——手表靠系统边缘
            // 右滑，手环没有系统返回，按钮点下去等于没反应。
            btn('电源', Icons.power_settings_new,
                on ? () => b.gesture(() => b.grpc.sendKey('Power')) : null),
            btn('重启手表', Icons.restart_alt, on
                ? () => confirmDestructive(
                      context,
                      title: '重启手表？',
                      message: '手表上正在跑的应用会中断。',
                      okLabel: '重启',
                      onOk: () => b.gesture(() => b.vmState(VelaVm.restart)),
                    )
                : null),
          ],
        ),
      ],
    );
  }

  /// 引擎已经在跑时点「启动」等于重启，先问一声。
  void _confirmRestart(BuildContext context) {
    confirmDestructive(
      context,
      title: '重新启动引擎？',
      message: '当前会话会结束，手表会重新启动，需要几十秒。',
      okLabel: '重启',
      onOk: () => state.startSelected(),
    );
  }
}

/// Quick apps installed inside the guest. Folded in from the old 应用 tab: it is only
/// meaningful while an emulator session is up, which is what this screen is about.
class _GuestApps extends StatelessWidget {
  const _GuestApps({required this.state});
  final AppState state;

  @override
  Widget build(BuildContext context) {
    final p = state.projects;
    final apps = p.installedApps;

    return SectionCard(
      title: '手表应用',
      icon: Icons.apps,
      trailing: IconButton(
        onPressed: p.refresh,
        tooltip: '刷新',
        visualDensity: VisualDensity.compact,
        icon: const Icon(Icons.refresh_rounded, size: 18),
      ),
      children: [
        if (apps.isEmpty)
          EmptyState(
            icon: Icons.apps_outlined,
            title: '还没有安装应用',
            message: p.notice ??
                '引擎未运行时读不到客机应用；先启动引擎，或到「工程」页构建并安装一个。',
          )
        else
          for (final pkg in apps)
            ListTile(
              contentPadding: EdgeInsets.zero,
              leading: const Icon(Icons.widgets_outlined),
              title: Text(pkg, style: Theme.of(context).textTheme.bodyMedium),
              trailing: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  IconButton(
                    tooltip: '启动',
                    icon: const Icon(Icons.play_arrow_rounded),
                    onPressed: () => state.bridge.launchPackage(pkg,
                        imageType: state.selected?.imageType),
                  ),
                  IconButton(
                    tooltip: '停止',
                    icon: const Icon(Icons.stop_rounded),
                    onPressed: () => p.stopApp(pkg, imageType: state.selected?.imageType),
                  ),
                  IconButton(
                    tooltip: '卸载',
                    icon: const Icon(Icons.delete_outline),
                    onPressed: () => confirmDestructive(
                      context,
                      title: '卸载 $pkg？',
                      message: '手表上的这个应用会被删除。',
                      okLabel: '卸载',
                      onOk: () => p.uninstallApp(pkg),
                    ),
                  ),
                ],
              ),
            ),
      ],
    );
  }
}
