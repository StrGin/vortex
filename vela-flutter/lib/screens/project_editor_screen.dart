import 'package:flutter/material.dart';

import '../state/app_state.dart';
import '../state/vela_projects.dart';
import '../theme/app_theme.dart';
import '../widgets/code_editor.dart';
import '../widgets/common.dart';

/// Two-pane source browser + plain-text editor over a project directory.
class ProjectEditorScreen extends StatefulWidget {
  final VelaProject project;
  const ProjectEditorScreen({super.key, required this.project});

  @override
  State<ProjectEditorScreen> createState() => _ProjectEditorScreenState();
}

class _ProjectEditorScreenState extends State<ProjectEditorScreen> {
  List<ProjectFile> files = const [];
  String? selected;
  /// 带语法高亮的控制器（见 widgets/code_editor.dart）。
  final CodeEditingController editor = CodeEditingController();
  bool dirty = false;
  bool loading = true;

  VelaProjects get api => AppScope.of(context).projects;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) => _loadFiles());
  }

  @override
  void dispose() {
    editor.dispose();
    super.dispose();
  }

  Future<void> _loadFiles() async {
    setState(() => loading = true);
    final f = await api.files(widget.project.name);
    if (!mounted) return;
    setState(() {
      files = f.where((e) => !e.isDir).toList()
        ..sort((a, b) => a.path.compareTo(b.path));
      loading = false;
    });
  }

  Future<void> _open(ProjectFile f) async {
    final c = await api.read(widget.project.name, f.path);
    if (!mounted) return;
    setState(() {
      selected = f.path;
      // 语言决定高亮规则（按扩展名）
      editor.language = languageOf(f.path);
      editor.text = c ?? '';
      dirty = false;
    });
  }

  Future<void> _save() async {
    if (selected == null) return;
    final ok = await api.write(widget.project.name, selected!, editor.text);
    if (!mounted) return;
    setState(() => dirty = false);
    ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(ok ? '已保存 $selected' : '保存失败：${api.notice ?? ''}')));
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final narrow = MediaQuery.sizeOf(context).width < 600;

    final tree = _FileTree(
      files: files,
      loading: loading,
      selected: selected,
      scheme: scheme,
      onOpen: _open,
    );

    final pane = selected == null
        ? const EmptyState(
            icon: Icons.description_outlined,
            title: '选一个文件',
            message: '左侧 src 下的 .ux / .js / .json 可以直接改，保存后可以推到手表。',
          )
        : CodeEditor(
            controller: editor,
            path: selected ?? '',
            onSave: dirty ? _save : null,
            onChanged: () {
              if (!dirty) setState(() => dirty = true);
            },
          );

    return PopScope(
      canPop: !dirty,
      onPopInvokedWithResult: (didPop, _) {
        if (didPop || !dirty) return;
        confirmDestructive(
          context,
          title: '放弃未保存的修改？',
          message: '返回会丢失当前文件里还没保存的内容。',
          okLabel: '放弃',
          onOk: () => Navigator.of(context).pop(),
        );
      },
      child: Scaffold(
      appBar: AppBar(
        title: Text(
          widget.project.name,
          style: Theme.of(context).textTheme.titleLarge?.copyWith(
                fontWeight: FontWeight.w600,
              ),
        ),
        centerTitle: false,
        actions: [
          if (dirty)
            const Padding(
              padding: EdgeInsets.symmetric(horizontal: Insets.sm),
              child: Center(
                  child: StatusChip(
                      label: '未保存', icon: Icons.edit, tone: ChipTone.warn)),
            ),
          IconButton(
              onPressed: _loadFiles,
              tooltip: '重新读取',
              icon: const Icon(Icons.folder_outlined)),
          IconButton(
              onPressed: dirty ? _save : null,
              tooltip: '保存',
              icon: const Icon(Icons.save_outlined)),
          const SizedBox(width: Insets.sm),
        ],
      ),
      body: narrow
          ? Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                SizedBox(height: 160, child: tree),
                const SizedBox(height: Insets.sm),
                Expanded(
                  child: Padding(
                    padding: const EdgeInsets.fromLTRB(
                        Insets.lg, 0, Insets.lg, Insets.lg),
                    child: pane,
                  ),
                ),
              ],
            )
          : Padding(
              padding: const EdgeInsets.fromLTRB(
                  Insets.lg, 0, Insets.lg, Insets.lg),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  SizedBox(
                    width: 220,
                    child: SectionCard(
                      padding: const EdgeInsets.all(Insets.sm),
                      children: [Expanded(child: tree)],
                    ),
                  ),
                  const SizedBox(width: Insets.md),
                  Expanded(child: pane),
                ],
              ),
            ),
      floatingActionButton: dirty
          ? FloatingActionButton.extended(
              onPressed: _save,
              icon: const Icon(Icons.save_outlined),
              label: const Text('保存'),
            )
          : null,
      ),
    );
  }
}

class _FileTree extends StatelessWidget {
  const _FileTree({
    required this.files,
    required this.loading,
    required this.selected,
    required this.scheme,
    required this.onOpen,
  });

  final List<ProjectFile> files;
  final bool loading;
  final String? selected;
  final ColorScheme scheme;
  final ValueChanged<ProjectFile> onOpen;

  @override
  Widget build(BuildContext context) {
    if (loading) {
      return const Center(child: CircularProgressIndicator());
    }
    if (files.isEmpty) {
      return Center(
        child: Text(
          '读不到工程文件',
          style: Theme.of(context).textTheme.bodySmall?.copyWith(
                color: scheme.onSurfaceVariant,
              ),
        ),
      );
    }
    return ListView.builder(
      itemCount: files.length,
      itemBuilder: (context, i) {
        final f = files[i];
        final isSelected = selected == f.path;
        return Padding(
          padding: const EdgeInsets.only(bottom: 2),
          child: Material(
            color: isSelected ? scheme.secondaryContainer : Colors.transparent,
            borderRadius: Radii.chipR,
            child: InkWell(
              borderRadius: Radii.chipR,
              onTap: f.editable ? () => onOpen(f) : null,
              child: Padding(
                padding: const EdgeInsets.symmetric(
                  horizontal: Insets.sm,
                  vertical: Insets.sm,
                ),
                child: Row(
                  children: [
                    Icon(
                      f.editable
                          ? Icons.description_outlined
                          : Icons.lock_outline,
                      size: 16,
                      color: f.editable
                          ? (isSelected
                              ? scheme.onSecondaryContainer
                              : scheme.onSurfaceVariant)
                          : scheme.outline,
                    ),
                    const SizedBox(width: Insets.sm),
                    Expanded(
                      child: Text(
                        f.path,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                          fontSize: 12,
                          color: f.editable
                              ? (isSelected
                                  ? scheme.onSecondaryContainer
                                  : scheme.onSurface)
                              : scheme.outline,
                        ),
                      ),
                    ),
                    Text(
                      '${f.size}',
                      style: TextStyle(fontSize: 10, color: scheme.outline),
                    ),
                  ],
                ),
              ),
            ),
          ),
        );
      },
    );
  }
}
