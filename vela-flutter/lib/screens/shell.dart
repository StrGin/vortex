import 'package:flutter/material.dart';
import 'package:liquid_glass_widgets/liquid_glass_widgets.dart';

import 'glass_nav_bar.dart';

import '../state/app_state.dart';
import '../theme/app_theme.dart';

/// One top-level destination.
class ShellPage {
  const ShellPage({
    required this.label,
    required this.icon,
    required this.selectedIcon,
    required this.title,
    this.subtitle = '',
    this.actions = const [],
    required this.body,
  });

  final String label;
  final IconData icon;
  final IconData selectedIcon;
  final String title;
  final String subtitle;
  final List<Widget> actions;
  final Widget body;
}

/// Adaptive app shell.
///
/// The navigation moves to a rail as soon as there is horizontal room, and every page
/// gets a real header (large title + status subtitle) instead of a per-screen AppBar.
class Shell extends StatelessWidget {
  const Shell({super.key, required this.pages});

  final List<ShellPage> pages;

  /// Material's compact/medium boundary, measured in **logical** pixels.
  static const double railBreakpoint = 600;

  @override
  Widget build(BuildContext context) {
    final state = AppScope.of(context);
    final scheme = Theme.of(context).colorScheme;
    final index = state.page.clamp(0, pages.length - 1);
    final page = pages[index];

    return LayoutBuilder(
      builder: (context, c) {
        final wide = c.maxWidth >= railBreakpoint;

        final header = _Header(
          title: page.title,
          subtitle: page.subtitle,
          actions: page.actions,
        );

        final body = Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            header,
            Expanded(
              // 页面切换用淡入淡出 + 轻微上移：切换感明确但不喧哗（MD3 motion）。
              child: AnimatedSwitcher(
                duration: Motion.base,
                switchInCurve: Motion.emphasized,
                switchOutCurve: Motion.standard,
                transitionBuilder: (child, animation) => FadeTransition(
                  opacity: animation,
                  child: SlideTransition(
                    position: Tween<Offset>(
                      begin: const Offset(0, 0.015),
                      end: Offset.zero,
                    ).animate(animation),
                    child: child,
                  ),
                ),
                child: KeyedSubtree(
                  key: ValueKey<int>(index),
                  child: page.body,
                ),
              ),
            ),
          ],
        );

        if (wide) {
          return Scaffold(
            backgroundColor: scheme.surface,
            body: SafeArea(
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  NavigationRail(
                    selectedIndex: index,
                    onDestinationSelected: state.setPage,
                    labelType: NavigationRailLabelType.all,
                    backgroundColor: scheme.surfaceContainer,
                    indicatorColor: scheme.secondaryContainer,
                    destinations: [
                      for (final p in pages)
                        NavigationRailDestination(
                          icon: Icon(p.icon),
                          selectedIcon: Icon(p.selectedIcon),
                          label: Text(p.label),
                        ),
                    ],
                  ),
                  VerticalDivider(width: 1, color: scheme.outlineVariant),
                  Expanded(child: body),
                ],
              ),
            ),
          );
        }

        if (state.liquidGlassNav) {
          // 液态玻璃底栏：MeiloX 的观感 + liquid_glass_widgets 的 premium 管线。
          // GlassScaffold 负责背景采样层、条与内容的 z 序、边缘渐隐和安全区。
          return GlassScaffold(
            backgroundColor: scheme.surface,
            statusBarStyle: GlassStatusBarStyle.none,
            // 关掉内容边缘的渐进模糊：MeiloX 的底栏外面没有这一层，
            // 开着就是"底栏底下还有一层毛玻璃"（用户在真机上看到的就是它）。
            edgeFade: false,
            body: SafeArea(bottom: false, child: body),
            bottomBar: MeiloXGlassBar(
              pages: pages,
              index: index,
              onSelected: state.setPage,
            ),
          );
        }

        return Scaffold(
          backgroundColor: scheme.surface,
          body: SafeArea(
            bottom: false,
            child: body,
          ),
          bottomNavigationBar: NavigationBar(
                  selectedIndex: index,
                  onDestinationSelected: state.setPage,
                  destinations: [
                    for (final p in pages)
                      NavigationDestination(
                        icon: Icon(p.icon),
                        selectedIcon: Icon(p.selectedIcon),
                        label: p.label,
                      ),
                  ],
                ),
        );
      },
    );
  }
}

class _Header extends StatelessWidget {
  const _Header({
    required this.title,
    required this.subtitle,
    required this.actions,
  });

  final String title;
  final String subtitle;
  final List<Widget> actions;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.fromLTRB(
        Insets.lg,
        Insets.lg,
        Insets.md,
        Insets.md,
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.center,
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(
                  title,
                  style: Theme.of(context).textTheme.headlineSmall?.copyWith(
                        fontWeight: FontWeight.w600,
                        color: scheme.onSurface,
                        height: 1.2,
                      ),
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                ),
                if (subtitle.isNotEmpty) ...[
                  const SizedBox(height: 2),
                  Text(
                    subtitle,
                    style: Theme.of(context).textTheme.bodySmall?.copyWith(
                          color: scheme.onSurfaceVariant,
                        ),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                  ),
                ],
              ],
            ),
          ),
          if (actions.isNotEmpty) ...[
            const SizedBox(width: Insets.md),
            Row(mainAxisSize: MainAxisSize.min, children: actions),
          ],
        ],
      ),
    );
  }
}


/// 玻璃底栏占掉的高度：页面底部要多留这么多，最后一条内容才不会被压在玻璃下面。
double liquidGlassNavReserve(BuildContext context) =>
    kGlassNavHeight +
    kGlassNavMargin * 2 +
    Insets.sm /* 视觉呼吸 */ +
    MediaQuery.paddingOf(context).bottom;
