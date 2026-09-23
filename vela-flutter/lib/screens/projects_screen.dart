import 'package:flutter/material.dart';

import '../state/app_state.dart';
import '../state/vela_projects.dart';
import '../theme/app_theme.dart';
import '../widgets/common.dart';
import 'project_editor_screen.dart';
import 'shell.dart';

class ProjectsScreen extends StatefulWidget {
  const ProjectsScreen({super.key});
  @override
  State<ProjectsScreen> createState() => _ProjectsScreenState();
}

class _ProjectsScreenState extends State<ProjectsScreen>
    with WidgetsBindingObserver {
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) AppScope.of(context).projects.refresh();
    });
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  /// Coming back from the system pages (all-files access, app details) should
  /// re-read the toolchain/public-surface state without a manual pull.
  @override
  void didChangeAppLifecycleState(AppLifecycleState lifecycle) {
    if (lifecycle == AppLifecycleState.resumed && mounted) {
      AppScope.of(context).projects.refresh();
    }
  }

  Future<void> _create() async {
    final state = AppScope.of(context);
    final nameC = TextEditingController();
    final pkgC = TextEditingController(text: 'com.example.');
    String deviceType = 'watch';
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('新建快应用工程'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
                controller: nameC,
                decoration: const InputDecoration(labelText: '工程名')),
            const SizedBox(height: Insets.md),
            TextField(
                controller: pkgC,
                decoration: const InputDecoration(labelText: '包名')),
            const SizedBox(height: Insets.md),
            StatefulBuilder(
              builder: (ctx, setS) => DropdownButtonFormField<String>(
                initialValue: deviceType,
                isExpanded: true,
                decoration: const InputDecoration(labelText: '设备类型'),
                items: const [
                  DropdownMenuItem(value: 'watch', child: Text('watch 手表')),
                  DropdownMenuItem(value: 'band', child: Text('band 手环')),
                  DropdownMenuItem(value: 'phone', child: Text('phone 手机')),
                ],
                onChanged: (v) => setS(() => deviceType = v ?? 'watch'),
              ),
            ),
          ],
        ),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(ctx, false),
              child: const Text('取消')),
          FilledButton(
              onPressed: () async {
                if (nameC.text.trim().isEmpty) return;
                Navigator.pop(ctx, true);
                await state.projects.create(
                  name: nameC.text.trim(),
                  package: pkgC.text.trim(),
                  deviceType: deviceType,
                );
              },
              child: const Text('创建')),
        ],
      ),
    );
    if (ok == true) await state.projects.refresh();
  }

  @override
  Widget build(BuildContext context) {
    final state = AppScope.of(context);
    final p = state.projects;

    return ListView(
      padding: EdgeInsets.fromLTRB(
              Insets.lg,
              Insets.xs,
              Insets.lg, Insets.xxl +
              (state.liquidGlassNav ? liquidGlassNavReserve(context) : 0)),
      children: [
        _ToolchainCard(state: state, projects: p, onCreate: _create),
        if (p.notice != null) ...[
          const SizedBox(height: Insets.md),
          NoticeBanner(message: p.notice!, tone: ChipTone.bad),
        ],
        const SizedBox(height: Insets.md),
        if (p.projects.isEmpty)
          const EmptyState(
            icon: Icons.code_off,
            title: '还没有工程',
            message: '点下面的「新建工程」，选个模板就能开工。',
          )
        else
          SectionCard(
            title: '工程',
            icon: Icons.folder_outlined,
            trailing: StatusChip(label: '${p.projects.length}'),
            children: [
              for (final pr in p.projects)
                _ProjectTile(projects: p, project: pr),
            ],
          ),
        if (p.importableProjects.isNotEmpty || !p.status.allFiles) ...[
          const SizedBox(height: Insets.md),
          SectionCard(
            title: '从编辑面导入',
            icon: Icons.move_to_inbox_outlined,
            trailing: p.importableProjects.isEmpty
                ? null
                : StatusChip(label: '${p.importableProjects.length}'),
            children: [
              Padding(
                padding: const EdgeInsets.fromLTRB(Insets.lg, 0, Insets.lg, Insets.xs),
                child: Text(
                  '把电脑上的工程拷到手机的 '
                  '${p.status.publicDir.isEmpty ? '/sdcard/Vortex/projects' : p.status.publicDir} '
                  '下（仓库里的 tools/push-project.sh 一条命令搞定），这里就会出现，导入后可直接构建。',
                  style: Theme.of(context).textTheme.bodySmall,
                ),
              ),
              if (!p.status.allFiles)
                ListTile(
                  leading: const Icon(Icons.folder_shared_outlined),
                  title: const Text('需要「所有文件访问」'),
                  subtitle: Text(
                    'Android 11+ 读 /sdcard 需要它。点「去授权」打开系统页面，回来会自动刷新。',
                    style: Theme.of(context).textTheme.bodySmall,
                  ),
                  trailing: FilledButton.tonal(
                    onPressed: () => state.bridge.openSettings(),
                    child: const Text('去授权'),
                  ),
                ),
              for (final pr in p.importableProjects)
                ListTile(
                  leading: const Icon(Icons.widgets_outlined),
                  title: Text(pr.name),
                  subtitle: Text(
                    pr.package.isEmpty ? pr.path : pr.package,
                    style: Theme.of(context).textTheme.bodySmall,
                  ),
                  trailing: FilledButton.tonal(
                    onPressed:
                        p.busy ? null : () => p.importFromPublic(pr.name),
                    child: const Text('导入'),
                  ),
                ),
            ],
          ),
        ],
        const SizedBox(height: Insets.md),
        SectionCard(
          title: '构建输出',
          icon: Icons.terminal_outlined,
          padding: const EdgeInsets.fromLTRB(
              Insets.lg, Insets.lg, Insets.lg, Insets.lg),
          children: [
            LogPane(lines: p.buildLog, height: 180),
          ],
        ),
      ],
    );
  }
}

class _ToolchainCard extends StatelessWidget {
  const _ToolchainCard({
    required this.state,
    required this.projects,
    required this.onCreate,
  });

  final AppState state;
  final VelaProjects projects;
  final VoidCallback onCreate;

  @override
  Widget build(BuildContext context) {
    final s = projects.status;
    final ready = s.nodeAvailable && s.toolkitInstalled;

    return SectionCard(
      title: '构建工具链',
      icon: Icons.build_outlined,
      trailing: StatusChip(
        label: ready ? '就绪' : '未就绪',
        tone: ready ? ChipTone.good : ChipTone.warn,
      ),
      children: [
        Wrap(
          spacing: Insets.sm,
          runSpacing: Insets.sm,
          children: [
            StatusChip(
              label: s.nodeAvailable ? 'node 就绪' : 'node 缺失',
              icon: s.nodeAvailable ? Icons.check : Icons.close,
              tone: s.nodeAvailable ? ChipTone.good : ChipTone.bad,
            ),
            StatusChip(
              label: s.toolkitInstalled ? 'aiot-toolkit 已装' : 'aiot-toolkit 未装',
              icon: s.toolkitInstalled ? Icons.check : Icons.close,
              tone: s.toolkitInstalled ? ChipTone.good : ChipTone.bad,
            ),
            StatusChip(
              label: 'adb :${s.adbPort}',
              icon: Icons.lan_outlined,
              tone: s.adbPort > 0 ? ChipTone.good : ChipTone.neutral,
            ),
          ],
        ),
        if (!ready) ...[
          const SizedBox(height: Insets.md),
          Align(
            alignment: Alignment.centerLeft,
            child: FilledButton.tonalIcon(
              onPressed: projects.busy ? null : state.installToolchain,
              icon: const Icon(Icons.download_outlined, size: 18),
              label: const Text('安装工具链'),
            ),
          ),
        ],
        const SizedBox(height: Insets.md),
        Align(
          alignment: Alignment.centerLeft,
          child: FilledButton.icon(
            onPressed: onCreate,
            icon: const Icon(Icons.add, size: 18),
            label: const Text('新建工程'),
          ),
        ),
      ],
    );
  }
}

class _ProjectTile extends StatelessWidget {
  const _ProjectTile({required this.projects, required this.project});
  final VelaProjects projects;
  final VelaProject project;

  @override
  Widget build(BuildContext context) {
    final watching =
        projects.status.watching && projects.status.project == project.name;
    return ExpansionTile(
      shape: const RoundedRectangleBorder(borderRadius: Radii.fieldR),
      collapsedShape:
          const RoundedRectangleBorder(borderRadius: Radii.fieldR),
      leading: Icon(
        watching ? Icons.sync_rounded : Icons.folder_outlined,
        color: watching ? Theme.of(context).colorScheme.primary : null,
      ),
      title: Row(
        children: [
          Expanded(child: Text(project.name)),
          if (watching)
            const StatusChip(
              label: '热更新中',
              icon: Icons.sync,
              tone: ChipTone.good,
            ),
        ],
      ),
      subtitle: Text(
        '${project.package}\n${project.path}',
        maxLines: 2,
        overflow: TextOverflow.ellipsis,
        style: Theme.of(context).textTheme.bodySmall,
      ),
      childrenPadding: const EdgeInsets.fromLTRB(
        Insets.md, 0, Insets.md, Insets.md),
      children: [
        Wrap(
          spacing: Insets.sm,
          runSpacing: Insets.sm,
          children: [
            FilledButton.tonalIcon(
              onPressed: projects.busy
                  ? null
                  : () async {
                      // 模拟器一次只跑一个客机，日志里带上目标设备，避免装错都不知道。
                      final st = AppScope.of(context);
                      final profile = st.selected;
                      await projects.buildInstallLaunch(project.name,
                          device: profile?.avdId, imageType: profile?.imageType);
                      if ((profile?.imageType ?? '').startsWith('vela-pre')) {
                        // 4.0 之前的镜像启动后屏幕是熄的：点一下电源键才看得见画面。
                        await st.bridge.key('Power');
                      }
                    },
              icon: const Icon(Icons.rocket_launch_outlined, size: 18),
              label: const Text('构建并安装启动'),
            ),
            OutlinedButton.icon(
              onPressed: () => Navigator.push(
                context,
                MaterialPageRoute(
                    builder: (_) => ProjectEditorScreen(project: project)),
              ),
              icon: const Icon(Icons.edit_outlined, size: 18),
              label: const Text('编辑源码'),
            ),
            OutlinedButton.icon(
              onPressed: projects.busy
                  ? null
                  : () => watching
                      ? projects.watch(null)
                      : projects.watch(project.name),
              icon: Icon(
                  watching ? Icons.stop_circle_outlined : Icons.sync_outlined,
                  size: 18),
              label: Text(watching ? '停止热更新' : '热更新'),
            ),
            IconButton(
              tooltip: '删除工程',
              icon: const Icon(Icons.delete_outline),
              onPressed: projects.busy ? null : () => projects.delete(project.name),
            ),
            if (projects.busy)
              const Padding(
                padding: EdgeInsets.all(Insets.sm),
                child: SizedBox(
                  width: 20,
                  height: 20,
                  child: CircularProgressIndicator(strokeWidth: 2),
                ),
              ),
          ],
        ),
      ],
    );
  }
}
