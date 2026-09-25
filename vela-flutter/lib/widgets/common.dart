import 'package:flutter/material.dart';

import '../theme/app_theme.dart';

/// Titled surface used by every screen, so cards read consistently.
class SectionCard extends StatelessWidget {
  const SectionCard({
    super.key,
    this.title,
    this.icon,
    this.trailing,
    required this.children,
    this.padding = Insets.card,
  });

  final String? title;
  final IconData? icon;
  final Widget? trailing;
  final List<Widget> children;
  final EdgeInsets padding;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Container(
      decoration: BoxDecoration(
        color: scheme.surfaceContainerLow,
        borderRadius: Radii.cardR,
        border: Border.all(color: scheme.outlineVariant, width: 1),
      ),
      padding: padding,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: [
          if (title != null) ...[
            Row(
              children: [
                if (icon != null) ...[
                  Icon(icon, size: 16, color: scheme.onSurfaceVariant),
                  const SizedBox(width: Insets.sm),
                ],
                Expanded(
                  child: Text(
                    title!,
                    style: Theme.of(context).textTheme.labelMedium?.copyWith(
                          color: scheme.onSurfaceVariant,
                          fontWeight: FontWeight.w600,
                          letterSpacing: 0.2,
                        ),
                  ),
                ),
                ?trailing,
              ],
            ),
            const SizedBox(height: Insets.md),
          ],
          ...children,
        ],
      ),
    );
  }
}

enum ChipTone { neutral, good, warn, bad }

/// Status pill. Kept as one widget so "ready / pending / failed" reads the same
/// everywhere instead of each screen picking its own colours.
class StatusChip extends StatelessWidget {
  const StatusChip({
    super.key,
    required this.label,
    this.icon,
    this.tone = ChipTone.neutral,
  });

  final String label;
  final IconData? icon;
  final ChipTone tone;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final (bg, fg) = switch (tone) {
      ChipTone.good => (scheme.primaryContainer, scheme.onPrimaryContainer),
      ChipTone.warn => (scheme.tertiaryContainer, scheme.onTertiaryContainer),
      ChipTone.bad => (scheme.errorContainer, scheme.onErrorContainer),
      ChipTone.neutral => (
          scheme.surfaceContainerHighest,
          scheme.onSurfaceVariant
        ),
    };

    return ConstrainedBox(
      // 提示语里会出现原生长错误串；不给上限时 Wrap 会被撑爆。
      constraints: const BoxConstraints(maxWidth: 320),
      child: Container(
      padding: const EdgeInsets.symmetric(horizontal: Insets.md, vertical: 6),
      decoration: BoxDecoration(color: bg, borderRadius: Radii.chipR),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (icon != null) ...[
            Icon(icon, size: 14, color: fg),
            const SizedBox(width: 6),
          ],
          Flexible(
            child: Text(
              label,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: Theme.of(context).textTheme.labelSmall?.copyWith(
                    color: fg,
                    fontWeight: FontWeight.w600,
                  ),
            ),
          ),
        ],
      ),
      ),
    );
  }
}

/// 页内提示条（信息/警告/错误）。原先 sim 与 projects 各写了一份，这里合一。
class NoticeBanner extends StatelessWidget {
  const NoticeBanner({
    super.key,
    required this.message,
    this.tone = ChipTone.neutral,
    this.icon,
    this.action,
  });

  final String message;
  final ChipTone tone;
  final IconData? icon;
  final Widget? action;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final (bg, fg) = switch (tone) {
      ChipTone.good => (scheme.primaryContainer, scheme.onPrimaryContainer),
      ChipTone.warn => (scheme.tertiaryContainer, scheme.onTertiaryContainer),
      ChipTone.bad => (scheme.errorContainer, scheme.onErrorContainer),
      ChipTone.neutral => (
          scheme.surfaceContainerHigh,
          scheme.onSurfaceVariant
        ),
    };
    final glyph = icon ??
        switch (tone) {
          ChipTone.bad => Icons.error_outline,
          ChipTone.warn => Icons.info_outline,
          _ => Icons.info_outline,
        };
    return Container(
      padding: const EdgeInsets.all(Insets.md),
      decoration: BoxDecoration(color: bg, borderRadius: Radii.fieldR),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(glyph, size: 18, color: fg),
          const SizedBox(width: Insets.sm),
          Expanded(
            child: Text(
              message,
              style: Theme.of(context).textTheme.bodySmall?.copyWith(color: fg),
            ),
          ),
          if (action != null) ...[
            const SizedBox(width: Insets.sm),
            action!,
          ],
        ],
      ),
    );
  }
}

class EmptyState extends StatelessWidget {
  const EmptyState({
    super.key,
    required this.icon,
    required this.title,
    this.message,
    this.action,
  });

  final IconData icon;
  final String title;
  final String? message;
  final Widget? action;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(Insets.xxl),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Container(
              width: 64,
              height: 64,
              decoration: BoxDecoration(
                color: scheme.surfaceContainerHigh,
                shape: BoxShape.circle,
              ),
              child: Icon(icon, size: 30, color: scheme.onSurfaceVariant),
            ),
            const SizedBox(height: Insets.lg),
            Text(title, style: Theme.of(context).textTheme.titleMedium),
            if (message != null) ...[
              const SizedBox(height: Insets.sm),
              Text(
                message!,
                textAlign: TextAlign.center,
                style: Theme.of(context).textTheme.bodySmall?.copyWith(
                      color: scheme.onSurfaceVariant,
                    ),
              ),
            ],
            if (action != null) ...[
              const SizedBox(height: Insets.xl),
              action!,
            ],
          ],
        ),
      ),
    );
  }
}

/// Key/value row with a hairline divider — used by 设置 and 镜像.
class InfoRow extends StatelessWidget {
  const InfoRow({
    super.key,
    required this.label,
    required this.value,
    this.icon,
    this.onTap,
  });

  final String label;
  final String value;
  final IconData? icon;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return InkWell(
      onTap: onTap,
      borderRadius: Radii.fieldR,
      child: Padding(
        padding: const EdgeInsets.symmetric(
          horizontal: Insets.sm,
          vertical: Insets.md,
        ),
        child: Row(
          children: [
            if (icon != null) ...[
              Icon(icon, size: 18, color: scheme.onSurfaceVariant),
              const SizedBox(width: Insets.md),
            ],
            // Fixed width rather than Flexible: a long value used to squeeze this
            // column, so stacked rows started their labels at different x and the
            // block looked ragged. Values are the part allowed to ellipsise.
            SizedBox(
              width: 96,
              child: Text(
                label,
                style: Theme.of(context).textTheme.bodyMedium,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
              ),
            ),
            const SizedBox(width: Insets.md),
            Expanded(
              child: Text(
                value,
                textAlign: TextAlign.right,
                style: Theme.of(context).textTheme.bodySmall?.copyWith(
                      color: scheme.onSurfaceVariant,
                    ),
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
              ),
            ),
            if (onTap != null) ...[
              const SizedBox(width: Insets.xs),
              Icon(
                Icons.chevron_right_rounded,
                size: 18,
                color: scheme.onSurfaceVariant,
              ),
            ],
          ],
        ),
      ),
    );
  }
}

/// Monospace log pane with a hairline frame, shared by 日志 and 工程.
class LogPane extends StatelessWidget {
  const LogPane({
    super.key,
    required this.lines,
    this.height,
    this.padding = const EdgeInsets.all(Insets.md),
  });

  final List<String> lines;
  final double? height;
  final EdgeInsets padding;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final body = lines.isEmpty
        ? Padding(
            padding: padding,
            child: Text(
              '（空）',
              style: Theme.of(context).textTheme.bodySmall?.copyWith(
                    color: scheme.onSurfaceVariant,
                    fontFamily: 'monospace',
                    height: 1.35,
                  ),
            ),
          )
        : ListView.builder(
            reverse: true,
            padding: padding,
            itemCount: lines.length,
            itemBuilder: (context, i) => Text(
              lines[lines.length - 1 - i],
              style: Theme.of(context).textTheme.bodySmall?.copyWith(
                    fontFamily: 'monospace',
                    height: 1.35,
                  ),
            ),
          );

    final frame = Container(
      decoration: BoxDecoration(
        color: scheme.surfaceContainerHighest,
        borderRadius: Radii.fieldR,
      ),
      clipBehavior: Clip.antiAlias,
      child: body,
    );

    return height == null ? frame : SizedBox(height: height, child: frame);
  }
}

/// 非破坏性操作的询问框：给一个主操作 + 一个"算了"。
///
/// 和 [confirmDestructive] 的区别只在配色与默认措辞——这里的主按钮是常规色，
/// 因为像"构建完要不要用其他应用打开产物"这种问法并不危险。
Future<void> confirmAction(
  BuildContext context, {
  required String title,
  String? message,
  String okLabel = '好',
  String cancelLabel = '不用了',
  required VoidCallback onOk,
}) async {
  final ok = await showDialog<bool>(
    context: context,
    builder: (ctx) => AlertDialog(
      title: Text(title),
      content: message == null ? null : Text(message),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(ctx).pop(false),
          child: Text(cancelLabel),
        ),
        FilledButton(
          onPressed: () => Navigator.of(ctx).pop(true),
          child: Text(okLabel),
        ),
      ],
    ),
  );
  if (ok == true) onOk();
}

/// 破坏性操作的统一确认框（删除设备/镜像/工程、重启手表…）。
Future<void> confirmDestructive(
  BuildContext context, {
  required String title,
  String? message,
  String okLabel = '删除',
  required VoidCallback onOk,
}) async {
  final scheme = Theme.of(context).colorScheme;
  final ok = await showDialog<bool>(
    context: context,
    builder: (ctx) => AlertDialog(
      title: Text(title),
      content: message == null ? null : Text(message),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(ctx).pop(false),
          child: const Text('取消'),
        ),
        FilledButton(
          style: FilledButton.styleFrom(
            backgroundColor: scheme.errorContainer,
            foregroundColor: scheme.onErrorContainer,
          ),
          onPressed: () => Navigator.of(ctx).pop(true),
          child: Text(okLabel),
        ),
      ],
    ),
  );
  if (ok == true) onOk();
}
