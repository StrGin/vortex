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
        const SizedBox(height: Insets.md),
        // 这张卡常显：以前没东西可导入时整块不渲染，用户以为 App 根本没有导入功能。
        SectionCard(
          title: '导入工程',
          icon: Icons.move_to_inbox_outlined,
          trailing: (p.pickedProjects.length + p.importableProjects.length) == 0
              ? null
              : StatusChip(
                  label: '${p.pickedProjects.length + p.importableProjects.length}'),
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(Insets.lg, 0, Insets.lg, Insets.xs),
              child: Text(
                '点「导入 zip」挑一个工程压缩包，会自动解压（GitHub 下载的那层目录会剥掉）；'
                '或者点「选择文件夹」直接挑手机里已解开的工程目录。'
                '也可以把工程拷到 '
                '${p.status.publicDir.isEmpty ? '/sdcard/Vortex/projects' : p.status.publicDir} '
                '下，这里会自动列出来。',
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
            if (p.pickedDir != null)
              ListTile(
                dense: true,
                leading: const Icon(Icons.folder_open_outlined, size: 20),
                title: Text(
                  p.pickedDir!,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: Theme.of(context).textTheme.bodySmall,
                ),
                trailing: TextButton(
                  onPressed: p.busy ? null : p.clearPicked,
                  child: const Text('清除'),
                ),
              ),
            for (final pr in p.pickedProjects)
              _ImportTile(
                project: pr,
                exists: p.projects.any((x) => x.name == pr.name),
                busy: p.busy,
                onImport: () => _import(context, p, pr.path, pr.name),
              ),
            for (final pr in p.importableProjects)
              _ImportTile(
                project: pr,
                exists: p.projects.any((x) => x.name == pr.name),
                busy: p.busy,
                onImport: () => _import(context, p, null, pr.name),
              ),
            Padding(
              padding: const EdgeInsets.fromLTRB(Insets.lg, Insets.xs, Insets.lg, 0),
              child: Wrap(
                spacing: Insets.sm,
                runSpacing: Insets.sm,
                children: [
                  FilledButton.icon(
                    onPressed: p.busy ? null : () => _importZip(context, p),
                    icon: const Icon(Icons.folder_zip_outlined, size: 18),
                    label: const Text('导入 zip'),
                  ),
                  FilledButton.tonalIcon(
                    onPressed: p.busy
                        ? null
                        : () async {
                            final ok = await p.pickFolder();
                            if (!ok && context.mounted) {
                              ScaffoldMessenger.of(context).showSnackBar(
                                  const SnackBar(content: Text('没有选文件夹')));
                            }
                          },
                    icon: const Icon(Icons.folder_open, size: 18),
                    label: const Text('选择文件夹'),
                  ),
                  TextButton.icon(
                    onPressed: p.busy ? null : () => p.refresh(),
                    icon: const Icon(Icons.refresh, size: 18),
                    label: const Text('刷新'),
                  ),
                ],
              ),
            ),
          ],
        ),
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
            // 构建（release）：产物落在工程 dist/<包名>.release.<版本>.rpk。
            // 构建成功后主动问一句要不要用其他应用打开（「推送」那次构建不问）。
            FilledButton.tonalIcon(
              onPressed: projects.busy
                  ? null
                  : () async {
                      final rpk = await projects.build(project.name);
                      if (!context.mounted || rpk == null) {
                        return;
                      }
                      final name = VelaProjects.artifactName(rpk);
                      confirmAction(
                        context,
                        title: '构建完成',
                        message: '已生成 $name\n\n要用其他应用打开它吗？',
                        okLabel: '用其他应用打开',
                        onOk: () => projects.openArtifact(project.name),
                      );
                    },
              icon: const Icon(Icons.build_outlined, size: 18),
              label: const Text('构建'),
            ),
            // 推送：debug 构建 → 装进手表 → 启动。
            FilledButton.tonalIcon(
              onPressed: projects.busy
                  ? null
                  : () async {
                      // 模拟器一次只跑一个客机，日志里带上目标设备，避免装错都不知道。
                      final st = AppScope.of(context);
                      final profile = st.selected;
                      await projects.push(project.name,
                          device: profile?.avdId,
                          imageType: profile?.imageType);
                      if ((profile?.imageType ?? '').startsWith('vela-pre')) {
                        // 4.0 之前的镜像启动后屏幕是熄的：点一下电源键才看得见画面。
                        await st.bridge.key('Power');
                      }
                    },
              icon: const Icon(Icons.upload_outlined, size: 18),
              label: const Text('推送'),
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
              tooltip: '用其他应用打开产物',
              icon: const Icon(Icons.open_in_new),
              onPressed: projects.busy
                  ? null
                  : () => projects.openArtifact(project.name),
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

/// 一条待导入的工程；同名已在工作区里时提示会覆盖。
class _ImportTile extends StatelessWidget {
  const _ImportTile({
    required this.project,
    required this.exists,
    required this.busy,
    required this.onImport,
  });

  final VelaProject project;
  final bool exists;
  final bool busy;
  final VoidCallback onImport;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return ListTile(
      leading: const Icon(Icons.widgets_outlined),
      title: Text(project.name),
      subtitle: Text(
        exists
            ? '工作区已有同名工程，导入会覆盖它的源码'
            : (project.package.isEmpty ? project.path : project.package),
        maxLines: 2,
        overflow: TextOverflow.ellipsis,
        style: Theme.of(context).textTheme.bodySmall?.copyWith(
              color: exists ? scheme.error : scheme.onSurfaceVariant,
            ),
      ),
      trailing: FilledButton.tonal(
        onPressed: busy ? null : onImport,
        child: const Text('导入'),
      ),
    );
  }
}

/// [path] 为空表示走编辑面（/sdcard/Vortex/projects）那条老路。
Future<void> _import(
    BuildContext context, VelaProjects p, String? path, String name) async {
  if (p.projects.any((x) => x.name == name)) {
    confirmDestructive(
      context,
      title: '覆盖 $name？',
      message: '工作区里已有同名工程，导入会覆盖它的源码文件（build、dist 等产物目录跳过，node_modules 会一起带过来）。',
      okLabel: '覆盖导入',
      onOk: () {
        path == null ? p.importFromPublic(name) : p.importAt(path, name);
      },
    );
    return;
  }
  path == null ? await p.importFromPublic(name) : await p.importAt(path, name);
}

/// 选 zip → 自动解压；撞名时先确认再解。
Future<void> _importZip(BuildContext context, VelaProjects p) async {
  final ok = await p.pickZip();
  if (!context.mounted) {
    return;
  }
  if (!ok) {
    ScaffoldMessenger.of(context)
        .showSnackBar(const SnackBar(content: Text('没有选 zip')));
    return;
  }
  if (p.pendingZipExists) {
    confirmDestructive(
      context,
      title: '覆盖 ${p.pendingZipName}？',
      message: '工作区里已有同名工程，解压会覆盖它的源码文件（build、dist 等产物目录跳过，node_modules 会一起解出来）。',
      okLabel: '覆盖导入',
      onOk: () => p.importPickedZip(),
    );
    return;
  }
  await p.importPickedZip();
}
