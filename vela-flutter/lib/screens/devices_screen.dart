import 'package:flutter/material.dart';

import '../models/vela_models.dart';
import '../state/app_state.dart';
import '../theme/app_theme.dart';
import '../widgets/common.dart';
import 'shell.dart';

/// Device profiles (from assets/vela/devices.json), the AVDs created from them, and
/// the system-image catalogue they need.
///
/// The image list lives here rather than in its own tab: an image is only ever
/// downloaded to satisfy a device, so the two belong on one screen.
class DevicesScreen extends StatelessWidget {
  const DevicesScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final state = AppScope.of(context);
    final b = state.bridge;

    if (b.devices.isEmpty) {
      return const EmptyState(
        icon: Icons.devices_other_outlined,
        title: '没有设备档案',
        message: '读不到设备列表，稍后重试或重启 App。\n'
            '桌面模式下这一屏只能连接外部模拟器。',
      );
    }

    return ListView(
      padding: EdgeInsets.fromLTRB(
              Insets.lg,
              Insets.xs,
              Insets.lg, Insets.xxl +
              (state.liquidGlassNav ? liquidGlassNavReserve(context) : 0)),
      children: [
        // 离线/别人拷来的镜像包：以前只能从小米 CDN 下载，现在可以从本地 zip 装。
        Align(
          alignment: Alignment.centerLeft,
          child: OutlinedButton.icon(
            icon: const Icon(Icons.folder_open_outlined, size: 18),
            label: const Text('从本地 zip 导入'),
            onPressed: () => _importLocalZip(context, state),
          ),
        ),
        const SizedBox(height: Insets.md),
        _AvdsCard(state: state),
        const SizedBox(height: Insets.md),
        _ProfilesCard(state: state),
        const SizedBox(height: Insets.md),
        _ImagesCard(state: state),
      ],
    );
  }
}

class _AvdsCard extends StatelessWidget {
  const _AvdsCard({required this.state});
  final AppState state;

  @override
  Widget build(BuildContext context) {
    final b = state.bridge;
    final scheme = Theme.of(context).colorScheme;
    return SectionCard(
      title: '已创建的虚拟设备',
      icon: Icons.smartphone_outlined,
      trailing: StatusChip(label: '${b.avds.length}'),
      children: [
        if (b.avds.isEmpty)
          Text(
            '还没有虚拟设备。在下面挑一个档案，点「创建」。',
            style: Theme.of(context).textTheme.bodySmall?.copyWith(
                  color: scheme.onSurfaceVariant,
                ),
          )
        else
          for (final id in b.avds)
            ListTile(
              contentPadding: EdgeInsets.zero,
              leading: Icon(
                b.status.running && b.status.avd == id
                    ? Icons.play_circle_fill_rounded
                    : Icons.radio_button_unchecked,
                color: b.status.running && b.status.avd == id
                    ? scheme.primary
                    : scheme.onSurfaceVariant,
              ),
              title: Text(id),
              subtitle: Text(
                b.status.running && b.status.avd == id ? '运行中' : '已停止',
                style: Theme.of(context).textTheme.bodySmall,
              ),
              trailing: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  IconButton(
                    tooltip: '启动',
                    icon: const Icon(Icons.play_arrow_rounded),
                    onPressed: () => state.startAvd(id),
                  ),
                  IconButton(
                    tooltip: '清空数据',
                    icon: const Icon(Icons.cleaning_services_outlined),
                    onPressed: () => confirmDestructive(
                      context,
                      title: '清空 $id 的数据？',
                      message: '设备里的装机记录、登录状态和设置都会清掉，下次启动从镜像重新铺一份。'
                          '设备起不来、卡在开机界面时用它；清完第一次启动会慢一些（要初始化存储）。',
                      okLabel: '清空',
                      onOk: () => b.wipeAvd(id),
                    ),
                  ),
                  IconButton(
                    tooltip: '删除',
                    icon: const Icon(Icons.delete_outline),
                    onPressed: () => confirmDestructive(
                      context,
                      title: '删除虚拟设备 $id？',
                      message: '设备目录会被删除，已下载的镜像不受影响。',
                      onOk: () => b.deleteAvd(id).then((_) => state.boot()),
                    ),
                  ),
                ],
              ),
              onTap: () => state.startAvd(id),
            ),
      ],
    );
  }
}

class _ProfilesCard extends StatelessWidget {
  const _ProfilesCard({required this.state});
  final AppState state;

  @override
  Widget build(BuildContext context) {
    final b = state.bridge;
    final scheme = Theme.of(context).colorScheme;
    return SectionCard(
      title: '设备档案',
      icon: Icons.list_alt,
      children: [
        for (final d in b.devices)
          _ProfileTile(state: state, d: d, scheme: scheme),
      ],
    );
  }
}

class _ProfileTile extends StatelessWidget {
  const _ProfileTile({
    required this.state,
    required this.d,
    required this.scheme,
  });

  final AppState state;
  final DeviceProfile d;
  final ColorScheme scheme;

  @override
  Widget build(BuildContext context) {
    final b = state.bridge;
    final selected = state.selected?.avdId == d.avdId;
    final image = b.images.where((e) => e.type == d.imageType).toList();
    final have = image.isNotEmpty && image.first.complete;
    final created = b.avds.contains(d.avdId);

    return AnimatedContainer(
      duration: Motion.fast,
      curve: Motion.standard,
      margin: const EdgeInsets.only(bottom: Insets.sm),
      decoration: BoxDecoration(
        color: selected ? scheme.secondaryContainer : scheme.surfaceContainerHigh,
        borderRadius: Radii.fieldR,
      ),
      child: InkWell(
        borderRadius: Radii.fieldR,
        onTap: () => state.select(d),
        child: Padding(
          padding: const EdgeInsets.all(Insets.md),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Icon(
                    d.isCircle
                        ? Icons.radio_button_unchecked
                        : d.isPill
                            ? Icons.blur_on
                            : Icons.crop_square,
                    size: 20,
                    color: selected
                        ? scheme.onSecondaryContainer
                        : scheme.onSurfaceVariant,
                  ),
                  const SizedBox(width: Insets.sm),
                  Expanded(
                    child: Text(
                      d.avdId,
                      style: Theme.of(context).textTheme.titleSmall?.copyWith(
                            color: selected
                                ? scheme.onSecondaryContainer
                                : scheme.onSurface,
                          ),
                    ),
                  ),
                  if (selected)
                    StatusChip(
                      label: '当前',
                      icon: Icons.check,
                      tone: ChipTone.good,
                    ),
                ],
              ),
              const SizedBox(height: Insets.sm),
              Wrap(
                spacing: Insets.sm,
                runSpacing: Insets.sm,
                children: [
                  StatusChip(label: '${d.width}x${d.height}'),
                  StatusChip(label: d.shape),
                  StatusChip(label: '${d.density} dpi'),
                  StatusChip(
                    label: d.imageType,
                    icon: have ? Icons.verified : Icons.cloud_download_outlined,
                    tone: have ? ChipTone.good : ChipTone.warn,
                  ),
                ],
              ),
              const SizedBox(height: Insets.sm),
              Row(
                mainAxisAlignment: MainAxisAlignment.end,
                children: [
                  if (!created)
                    FilledButton.tonal(
                      onPressed: () => state.createAndSelect(d.avdId),
                      child: const Text('创建'),
                    )
                  else
                    TextButton(
                      onPressed: () => state.startAvd(d.avdId),
                      child: const Text('启动'),
                    ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _ImagesCard extends StatelessWidget {
  const _ImagesCard({required this.state});
  final AppState state;

  @override
  Widget build(BuildContext context) {
    final b = state.bridge;
    final images = b.images;
    final scheme = Theme.of(context).colorScheme;

    return SectionCard(
      title: '系统镜像',
      icon: Icons.cloud_outlined,
      trailing: StatusChip(
        label: '${images.where((e) => e.complete).length}/${images.length} 已装',
      ),
      children: [
        Text(
          '镜像按需下载（150–430 MB）；已经有包也可以用下面的「从本地 zip 导入」。',
          style: Theme.of(context).textTheme.bodySmall?.copyWith(
                color: scheme.onSurfaceVariant,
              ),
        ),
        const SizedBox(height: Insets.md),
        if (images.isEmpty)
          Text(
            '暂时读不到镜像列表',
            style: Theme.of(context).textTheme.bodySmall?.copyWith(
                  color: scheme.onSurfaceVariant,
                ),
          )
        else
          for (final im in images)
            _ImageTile(state: state, im: im, scheme: scheme),
      ],
    );
  }
}

class _ImageTile extends StatelessWidget {
  const _ImageTile({
    required this.state,
    required this.im,
    required this.scheme,
  });

  final AppState state;
  final SystemImage im;
  final ColorScheme scheme;

  @override
  Widget build(BuildContext context) {
    final b = state.bridge;
    final progress = b.downloadProgress[im.type];
    final usedBy = b.devices
        .where((d) => d.imageType == im.type)
        .map((d) => d.avdId)
        .toList();

    return Padding(
      padding: const EdgeInsets.only(bottom: Insets.md),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(
                im.complete
                    ? Icons.verified
                    : progress != null
                        ? Icons.downloading
                        : Icons.cloud_outlined,
                size: 18,
                color: im.complete ? scheme.primary : scheme.onSurfaceVariant,
              ),
              const SizedBox(width: Insets.sm),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(im.label,
                        style: Theme.of(context).textTheme.bodyMedium),
                    const SizedBox(height: 2),
                    Text(
                      '${im.type} · ${im.time}'
                      '${im.size > 0 ? ' · ${(im.size / 1048576).toStringAsFixed(0)} MB' : ''}',
                      style: Theme.of(context).textTheme.bodySmall?.copyWith(
                            color: scheme.onSurfaceVariant,
                          ),
                    ),
                    if (usedBy.isNotEmpty)
                      Text(
                        '用于 ${usedBy.join(', ')}',
                        style: Theme.of(context).textTheme.bodySmall?.copyWith(
                              color: scheme.onSurfaceVariant,
                            ),
                      ),
                  ],
                ),
              ),
              if (im.complete)
                IconButton(
                  tooltip: '删除',
                  icon: const Icon(Icons.delete_outline),
                  onPressed: () => confirmDestructive(
                    context,
                    title: '删除镜像 ${im.type}？',
                    message: '用这个镜像的设备将无法启动；需要时可以重新下载。',
                    onOk: () => b.deleteImage(im.type),
                  ),
                )
              else
                FilledButton.tonal(
                  onPressed: progress == null
                      ? () => b.downloadImage(im.type)
                      : null,
                  child: Text(
                    progress == null ? '下载' : '${(progress * 100).round()}%',
                  ),
                ),
            ],
          ),
          if (progress != null) ...[
            const SizedBox(height: Insets.sm),
            ClipRRect(
              borderRadius: Radii.chipR,
              child: LinearProgressIndicator(value: progress, minHeight: 4),
            ),
          ],
        ],
      ),
    );
  }
}

/// 扫描 /sdcard 上常见的镜像 zip，选一个装进设备镜像目录。
Future<void> _importLocalZip(BuildContext context, AppState state) async {
  final zips = await state.bridge.localZips();
  if (!context.mounted) return;
  if (zips.isEmpty) {
    ScaffoldMessenger.of(context).showSnackBar(const SnackBar(
        content: Text('没找到 zip：把镜像包放到 /sdcard/Download 或 /sdcard/Vortex 下再试'),
        duration: Duration(seconds: 4)));
    return;
  }
  final picked = await showDialog<Map<Object?, Object?>>(
    context: context,
    builder: (ctx) => SimpleDialog(
      title: const Text('选择本地镜像 zip'),
      children: [
        for (final z in zips)
          SimpleDialogOption(
            onPressed: () => Navigator.of(ctx).pop(z),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                Text('${z['label'] ?? z['type']}'
                    '${z['known'] == true ? '' : '（不在官方目录里）'}'),
                Text(
                  '${z['name']} · ${((z['sizeBytes'] as num?) ?? 0) ~/ (1024 * 1024)} MB',
                  style: Theme.of(ctx).textTheme.bodySmall,
                ),
              ],
            ),
          ),
      ],
    ),
  );
  if (picked == null || !context.mounted) return;
  final path = '${picked['path']}';
  final r = await state.bridge.importImage(path);
  if (!context.mounted) return;
  if (r?['ok'] == true) {
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(
        content: Text('已导入 ${r?['type']}（${r?['entries']} 个文件）'),
        duration: const Duration(seconds: 3)));
  } else {
    ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('导入失败：${r?['error'] ?? '未知错误'}')));
  }
}
