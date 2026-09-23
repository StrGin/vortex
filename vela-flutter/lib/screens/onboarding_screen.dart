import 'package:flutter/material.dart';

import '../state/app_state.dart';
import '../theme/app_theme.dart';

/// 首次启动的进入引导：四页，说明"这是什么 / 镜像怎么来 / 怎么启动与操作 /
/// 工程怎么跑"，最后一页直接开始使用。设置页可以重看。
class OnboardingScreen extends StatefulWidget {
  const OnboardingScreen({super.key, required this.state});

  final AppState state;

  @override
  State<OnboardingScreen> createState() => _OnboardingScreenState();
}

class _OnboardingScreenState extends State<OnboardingScreen> {
  final PageController _pages = PageController();
  int _index = 0;

  static const _slides = <_Slide>[
    _Slide(
      icon: Icons.watch_rounded,
      title: 'Vortex',
      body: '在手机上跑小米 Vela 手表/手环模拟器。\n'
          '不需要电脑，也不需要 root。',
    ),
    _Slide(
      icon: Icons.download_rounded,
      title: '先准备一个设备镜像',
      body: '系统镜像要单独下载（约 220–380 MB）。\n'
          '到「设备」页点「下载」，再挑一个机型创建虚拟设备。',
    ),
    _Slide(
      icon: Icons.touch_app_rounded,
      title: '启动之后就能直接操作',
      body: '「模拟器」页点「启动引擎」→ 表盘出图（首次引导几十秒）。\n'
          '画面里可以直接点按、拖动、长按；下面一排 Home / 返回 / 电源 / 重启对应手表的按键。',
    ),
    _Slide(
      icon: Icons.terminal_rounded,
      title: '写点自己的应用',
      body: '「工程」页新建工程 → 安装工具链 → 构建 → 安装到手表。\n'
          '装好后应用会直接出现在手表上。',
    ),
  ];

  @override
  void dispose() {
    _pages.dispose();
    super.dispose();
  }

  Future<void> _finish({bool jumpToDevices = false}) async {
    await widget.state.finishOnboarding();
    if (jumpToDevices) {
      widget.state.setPage(1);
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final last = _index == _slides.length - 1;

    return Scaffold(
      backgroundColor: scheme.surface,
      body: SafeArea(
        child: Column(
          children: [
            Align(
              alignment: Alignment.centerRight,
              child: Padding(
                padding: const EdgeInsets.fromLTRB(Insets.lg, Insets.sm, Insets.md, 0),
                child: TextButton(
                  onPressed: () => _finish(),
                  child: const Text('跳过'),
                ),
              ),
            ),
            Expanded(
              child: PageView.builder(
                controller: _pages,
                itemCount: _slides.length,
                onPageChanged: (i) => setState(() => _index = i),
                itemBuilder: (context, i) {
                  final s = _slides[i];
                  return SingleChildScrollView(
                    padding: const EdgeInsets.fromLTRB(
                        Insets.xxl, Insets.lg, Insets.xxl, Insets.lg),
                    child: Column(
                      mainAxisAlignment: MainAxisAlignment.center,
                      crossAxisAlignment: CrossAxisAlignment.center,
                      children: [
                        Container(
                          width: 112,
                          height: 112,
                          decoration: BoxDecoration(
                            color: scheme.primaryContainer,
                            shape: BoxShape.circle,
                          ),
                          child: Icon(s.icon,
                              size: 52, color: scheme.onPrimaryContainer),
                        ),
                        const SizedBox(height: Insets.xxl),
                        Text(
                          s.title,
                          textAlign: TextAlign.center,
                          style: theme.textTheme.headlineSmall?.copyWith(
                            fontWeight: FontWeight.w600,
                          ),
                        ),
                        const SizedBox(height: Insets.md),
                        Text(
                          s.body,
                          textAlign: TextAlign.center,
                          style: theme.textTheme.bodyMedium?.copyWith(
                            color: scheme.onSurfaceVariant,
                            height: 1.5,
                          ),
                        ),
                      ],
                    ),
                  );
                },
              ),
            ),
            _Dots(count: _slides.length, index: _index),
            Padding(
              padding: const EdgeInsets.fromLTRB(
                  Insets.xl, Insets.lg, Insets.xl, Insets.xl),
              child: Row(
                children: [
                  if (!last)
                    Expanded(
                      child: OutlinedButton(
                        onPressed: () => _finish(jumpToDevices: true),
                        child: const Text('先去下载镜像'),
                      ),
                    ),
                  if (!last) const SizedBox(width: Insets.md),
                  Expanded(
                    child: FilledButton(
                      onPressed: () {
                        if (last) {
                          _finish();
                        } else {
                          _pages.nextPage(
                            duration: Motion.base,
                            curve: Motion.emphasized,
                          );
                        }
                      },
                      child: Text(last ? '开始使用' : '下一步'),
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _Slide {
  const _Slide({required this.icon, required this.title, required this.body});
  final IconData icon;
  final String title;
  final String body;
}

/// M3 风格的页码圆点：当前页是拉长的胶囊。
class _Dots extends StatelessWidget {
  const _Dots({required this.count, required this.index});
  final int count;
  final int index;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Row(
      mainAxisAlignment: MainAxisAlignment.center,
      children: [
        for (var i = 0; i < count; i++)
          AnimatedContainer(
            duration: Motion.base,
            curve: Motion.emphasized,
            margin: const EdgeInsets.symmetric(horizontal: Insets.xs),
            width: i == index ? 22 : 8,
            height: 8,
            decoration: BoxDecoration(
              color: i == index
                  ? scheme.primary
                  : scheme.onSurfaceVariant.withValues(alpha: 0.35),
              borderRadius: BorderRadius.circular(4),
            ),
          ),
      ],
    );
  }
}
