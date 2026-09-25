import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/services.dart';

/// One quick-app project on the phone.
class VelaProject {
  final String name;
  final String package;
  final String path;
  final int updatedMs;
  final int sizeBytes;
  final String? lastRpk;

  const VelaProject({
    required this.name,
    required this.package,
    required this.path,
    required this.updatedMs,
    required this.sizeBytes,
    this.lastRpk,
  });

  // VelaProjects#info writes bytes/updatedAt/rpkPath; the older names stay as
  // fallbacks so a half-updated native side still parses.
  static VelaProject fromMap(Map<Object?, Object?> m) => VelaProject(
        name: '${m['name'] ?? ''}',
        package: '${m['package'] ?? ''}',
        path: '${m['path'] ?? ''}',
        updatedMs: _i(m['updatedAt'] ?? m['updatedMs']),
        sizeBytes: _i(m['bytes'] ?? m['sizeBytes']),
        lastRpk: m['rpkPath'] != null
            ? '${m['rpkPath']}'
            : (m['lastRpk'] == null ? null : '${m['lastRpk']}'),
      );

  static int _i(Object? v) => v is int ? v : int.tryParse('$v') ?? 0;
}

class ProjectFile {
  final String path;
  final int size;
  final bool isDir;
  const ProjectFile(this.path, this.size, this.isDir);

  static ProjectFile fromMap(Map<Object?, Object?> m) => ProjectFile(
        '${m['path'] ?? ''}',
        m['size'] is int ? m['size'] as int : int.tryParse('${m['size']}') ?? 0,
        m['isDir'] == true,
      );

  bool get editable {
    const exts = ['.ux', '.js', '.json', '.css', '.less', '.md', '.txt', '.html'];
    final lower = path.toLowerCase();
    return !isDir && exts.any((e) => lower.endsWith(e)) && size < 512 * 1024;
  }
}

class DevStatus {
  final bool watching;
  final String? project;
  final bool nodeAvailable;
  final bool toolkitInstalled;
  final int lastBuildMs;
  final String? lastError;
  final int adbPort;
  /// "All files access" (MANAGE_EXTERNAL_STORAGE) -- needed to import projects
  /// dropped into /sdcard/Vortex/projects.
  final bool allFiles;
  final String publicDir;

  const DevStatus({
    this.watching = false,
    this.project,
    this.nodeAvailable = false,
    this.toolkitInstalled = false,
    this.lastBuildMs = 0,
    this.lastError,
    this.adbPort = 5555,
    this.allFiles = false,
    this.publicDir = '',
  });

  static DevStatus fromMap(Map<Object?, Object?> m) => DevStatus(
        watching: m['watching'] == true,
        project: m['project'] == null ? null : '${m['project']}',
        nodeAvailable: m['nodeAvailable'] == true,
        toolkitInstalled: m['toolkitInstalled'] == true,
        lastBuildMs: m['lastBuildMs'] is int
            ? m['lastBuildMs'] as int
            : int.tryParse('${m['lastBuildMs']}') ?? 0,
        lastError: m['lastError'] == null ? null : '${m['lastError']}',
        adbPort: m['adbPort'] is int ? m['adbPort'] as int : int.tryParse('${m['adbPort']}') ?? 5555,
        allFiles: m['allFiles'] == true,
        publicDir: '${m['publicDir'] ?? ''}',
      );
}

/// Native project/build/deploy chain.
///
/// Every call degrades to `false`/`null` instead of throwing when the host is not
/// registered (desktop runs), so the UI can show one clear "原生宿主未就绪" state.
class VelaProjects {
  static const control = MethodChannel('velasim/control');

  final List<VelaProject> projects = <VelaProject>[];
  /// Projects dropped into the phone's public edit surface
  /// (`/sdcard/Vortex/projects`) that are not in the private workspace yet.
  List<VelaProject> importableProjects = <VelaProject>[];
  /// 用户在系统文件夹选择器里选的目录，以及它下面能找到的工程。
  String? pickedDir;
  List<VelaProject> pickedProjects = <VelaProject>[];
  List<String> installedApps = <String>[];
  final List<String> buildLog = <String>[];
  DevStatus status = const DevStatus();
  bool busy = false;
  String? notice;
  String? lastRpkPath;

  final void Function() onChange;
  VelaProjects(this.onChange);

  Future<T?> _call<T>(String method, [Map<String, Object?>? args]) async {
    try {
      final r = await control.invokeMethod<Object?>(method, args);
      if (r is String) {
        // native may hand back JSON for the richer payloads
        try {
          final d = jsonDecode(r);
          if (d is T) return d;
        } catch (_) {}
      }
      return r is T ? r : null;
    } on MissingPluginException {
      notice = '原生宿主未就绪（桌面模式没有工程构建链）';
      onChange();
      return null;
    } on PlatformException catch (e) {
      notice = '${e.code}: ${e.message}';
      onChange();
      return null;
    }
  }

  void log(String line) {
    buildLog.add(line);
    if (buildLog.length > 800) buildLog.removeRange(0, buildLog.length - 800);
  }

  /// Feed the event-channel payloads the native side emits while building/installing.
  void onEvent(Map<String, Object?> e) {
    switch (e['type']) {
      case 'build':
        log('${e['phase'] ?? ''} ${e['line'] ?? ''}'.trim());
        break;
      case 'buildDone':
        busy = false;
        lastRpkPath = e['rpkPath'] == null ? null : '${e['rpkPath']}';
        final why = '${e['msg'] ?? e['error'] ?? ''}'.trim();
        log(e['ok'] == true
            ? '✅ 构建成功 (${e['ms']} ms) → $lastRpkPath'
            : '❌ 构建失败${why.isEmpty ? '' : ': $why'}');
        break;
      case 'installed':
        log(e['ok'] == true ? '✅ 已安装并启动: ${e['package']}' : '❌ 安装失败: ${e['msg']}');
        busy = false;
        break;
      case 'watch':
        log('⏳ 检测到变更 ${e['file']}');
        break;
      case 'toolchain':
        final phase = '${e['phase'] ?? ''}'.trim();
        final detail = '${e['detail'] ?? ''}'.trim();
        final error = '${e['error'] ?? ''}'.trim();
        status = DevStatus(
          watching: status.watching,
          project: status.project,
          nodeAvailable: e['nodeAvailable'] == true,
          toolkitInstalled: e['toolkitInstalled'] == true,
          lastBuildMs: status.lastBuildMs,
          adbPort: status.adbPort,
          allFiles: status.allFiles,
          publicDir: status.publicDir,
        );
        if (error.isNotEmpty) {
          log('❌ 工具链: $error');
        } else if (detail.isNotEmpty) {
          log('🔧 ${phase.isEmpty ? '' : '$phase '}$detail'.trim());
        } else {
          log('工具链: node=${e['nodeAvailable']} toolkit=${e['toolkitInstalled']}');
        }
        break;
    }
    onChange();
  }

  Future<void> refresh() async {
    final ps = await _call<List<Object?>>('listProjects');
    if (ps != null) {
      projects
        ..clear()
        ..addAll(ps.whereType<Map<Object?, Object?>>().map(VelaProject.fromMap));
    }
    final imp = await _call<List<Object?>>('projectsImportable');
    if (imp != null) {
      importableProjects = imp
          .whereType<Map<Object?, Object?>>()
          .map(VelaProject.fromMap)
          .toList(growable: false);
    }
    final apps = await _call<List<Object?>>('listInstalledApps');
    if (apps != null) installedApps = apps.map((e) => '$e').toList();
    final st = await _call<Map<Object?, Object?>>('devStatus');
    if (st != null) status = DevStatus.fromMap(st);
    onChange();
  }

  Future<bool> create({
    required String name,
    required String package,
    String deviceType = 'watch',
  }) async {
    final r = await _call<Map<Object?, Object?>>(
        'createProject', {'name': name, 'package': package, 'deviceType': deviceType});
    await refresh();
    return r != null;
  }

  Future<void> delete(String name) =>
      _call('deleteProject', {'name': name}).then((_) => refresh());

  /// Pull a project folder that was copied into the phone's public edit surface
  /// (`/sdcard/Vortex/projects/<name>`) into the private workspace.
  Future<bool> importFromPublic(String name) async {
    final r = await _call<Map<Object?, Object?>>('importProject', {'name': name});
    final files = r?['importedFiles'];
    log(r == null ? '❌ 导入失败: $name' : '✅ 已导入 $name（$files 个文件）');
    await refresh();
    return r != null;
  }

  /// 弹系统文件夹选择器；选中的目录自己也可能是工程，否则列出它下面的工程。
  /// 返回 false 表示用户取消或原生宿主不在。
  Future<bool> pickFolder() async {
    final r = await _call<Map<Object?, Object?>>('pickProjectFolder');
    if (r == null) {
      return false;
    }
    pickedDir = r['path'] == null ? null : '${r['path']}';
    pickedProjects = ((r['projects'] as List?) ?? const [])
        .whereType<Map<Object?, Object?>>()
        .map(VelaProject.fromMap)
        .toList();
    onChange();
    return true;
  }

  /// 从 [path] 导入一个工程（文件夹选择器选出来的那些）。
  Future<bool> importAt(String path, String name) async {
    busy = true;
    onChange();
    final r = await _call<Map<Object?, Object?>>(
        'importProjectAt', {'path': path, 'name': name});
    busy = false;
    log(r == null
        ? '❌ 导入失败: $name'
        : '✅ 已导入 $name（${r['importedFiles']} 个文件）');
    pickedProjects = pickedProjects.where((p) => p.path != path).toList();
    await refresh();
    return r != null;
  }

  void clearPicked() {
    pickedDir = null;
    pickedProjects = const [];
    onChange();
  }

  /// 选中的 zip 会先解成什么工程（撞名要用户确认后再解）。
  String? pendingZipToken;
  String? pendingZipName;
  bool pendingZipExists = false;

  /// 用系统文件选择器挑一个 zip；返回 false 表示用户取消。
  Future<bool> pickZip() async {
    final r = await _call<Map<Object?, Object?>>('pickProjectZip');
    if (r == null) {
      return false;
    }
    pendingZipToken = r['token'] == null ? null : '${r['token']}';
    pendingZipName = '${r['name'] ?? ''}';
    pendingZipExists = r['exists'] == true;
    onChange();
    return pendingZipToken != null;
  }

  /// 解包 [pickZip] 选中的那只 zip。
  Future<bool> importPickedZip() async {
    final token = pendingZipToken;
    if (token == null) {
      return false;
    }
    busy = true;
    onChange();
    final r = await _call<Map<Object?, Object?>>('importPickedZip', {'token': token});
    busy = false;
    final name = '${r?['name'] ?? pendingZipName ?? ''}';
    if (r == null) {
      log('❌ 导入 zip 失败: $name');
    } else {
      log('✅ 已从 zip 导入 $name（${r['importedFiles']} 个文件）');
      if (r['looksLikeProject'] != true) {
        log('⚠️ $name 里没找到 src/manifest.json / app.json，可能不是快应用工程');
      }
    }
    pendingZipToken = null;
    pendingZipName = null;
    pendingZipExists = false;
    await refresh();
    return r != null;
  }

  Future<List<ProjectFile>> files(String name) async {
    final r = await _call<List<Object?>>('projectFiles', {'name': name});
    if (r == null) return const [];
    return r.whereType<Map<Object?, Object?>>().map(ProjectFile.fromMap).toList();
  }

  Future<String?> read(String name, String path) =>
      _call<String>('readProjectFile', {'name': name, 'path': path});

  Future<bool> write(String name, String path, String content) async =>
      await _call<bool>('writeProjectFile', {'name': name, 'path': path, 'content': content}) ==
      true;

  /// 构建工程；成功返回产出的 rpk 绝对路径，失败返回 null。
  ///
  /// [task] 直接交给工具链：`release`（正式构建，默认）或 `build`（调试构建）。
  /// release 走 `production` 编译模式，产物叫 `<包名>.release.<版本>.rpk`；工具链在
  /// 该模式下**强制要求**工程里有 `sign/private.pem` + `sign/certificate.pem`，
  /// 缺失时原生侧会自动补上工具链自带的那套（构建日志里会写明）。
  ///
  /// 返回值给「构建完要不要用其他应用打开」那个询问用；[push] 里那次构建不看它。
  Future<String?> build(String name, {String task = 'release'}) async {
    busy = true;
    log('▶ 构建 $name（${task == 'release' ? 'release' : 'debug'}）');
    onChange();
    final r = await _call<Map<Object?, Object?>>(
        'buildProject', {'name': name, 'task': task});
    // buildDone 事件也会给 rpkPath，但事件可能晚到一步：直接用返回值兜底，
    // 这样紧接着的「推送」一定装的是这次刚构建出来的那份。
    final rpk = r?['rpkPath'];
    if (rpk != null && '$rpk'.trim().isNotEmpty) {
      lastRpkPath = '$rpk';
    }
    busy = false;
    onChange();
    return r?['ok'] == true ? lastRpkPath : null;
  }

  /// 产物文件名（询问框里显示用）。
  static String artifactName(String? rpkPath) {
    if (rpkPath == null || rpkPath.isEmpty) {
      return '';
    }
    final i = rpkPath.lastIndexOf('/');
    return i < 0 ? rpkPath : rpkPath.substring(i + 1);
  }

  Future<void> installAndLaunch(String name,
      {String? rpkPath, String? device, String? imageType}) async {
    busy = true;
    log('▶ 安装并启动 $name${device == null ? '' : '（目标设备 $device）'}');
    onChange();
    await _call<Map<Object?, Object?>>('installRpk', {
      'name': name,
      if (rpkPath != null) 'rpkPath': rpkPath,
      if (imageType != null) 'imageType': imageType,
      'launch': true,
    });
    busy = false;
    onChange();
  }

  /// 推送：**debug 构建** → 装进客机 → 启动（原来「构建并安装启动」的行为）。
  ///
  /// 与「构建」的分工：构建出正式包（release，产物可分享/留存），推送走调试包
  /// （编译快、带调试信息，热更新也是这条链路）。
  Future<void> push(String name, {String? device, String? imageType}) async {
    log('▶ 推送 $name（debug 构建 → 装机 → 启动）'
        '${device == null ? '' : '，目标设备 $device'}');
    await buildInstallLaunch(name,
        device: device, imageType: imageType, task: 'build');
  }

  /// 用其他应用打开最新构建产物（原生侧会先导出到 `/sdcard/Vortex/rpk`，
  /// 再交给系统的「打开方式」选择器）。
  Future<void> openArtifact(String name) async {
    final r = await _call<Map<Object?, Object?>>('openArtifact', {'name': name});
    if (r == null) {
      return;
    }
    if (r['ok'] == true) {
      final where = '${r['exported'] ?? ''}'.trim();
      log('📤 已用其他应用打开 ${r['file'] ?? name}${where.isEmpty ? '' : '（导出到 $where）'}');
    } else {
      log('❌ 打开产物失败：${r['msg'] ?? '未知原因'}');
    }
    onChange();
  }

  /// 构建 + 装机 + 启动。UI 的「推送」走这里（`task` 默认 debug，与推送语义一致）。
  Future<void> buildInstallLaunch(String name,
      {String? device, String? imageType, String task = 'build'}) async {
    await build(name, task: task);
    await installAndLaunch(name,
        rpkPath: lastRpkPath, device: device, imageType: imageType);
    await refresh();
  }

  Future<bool> watch(String? name) async =>
      await _call<bool>('watchProject', {'name': name}) == true;

  Future<void> stopApp(String package, {String? imageType}) async {
    final r = await _call<Map<Object?, Object?>>('stopApp', {
      'package': package,
      if (imageType != null) 'imageType': imageType,
    });
    notice = r == null || r['ok'] == true ? notice : '停止失败: ${r['msg'] ?? package}';
    onChange();
  }

  Future<void> uninstallApp(String package) async {
    final r = await _call<Map<Object?, Object?>>('uninstallApp', {'package': package});
    notice = r == null || r['ok'] == true ? notice : '卸载失败: ${r['msg'] ?? package}';
    onChange();
  }

  Future<void> importRpk(String srcPath, String name) =>
      _call<Map<Object?, Object?>>('importRpkFromStorage', {'srcPath': srcPath, 'name': name})
          .then((_) => refresh());

  Future<void> installToolchain() async {
    busy = true;
    onChange();
    final r = await _call<Map<Object?, Object?>>('installToolchain');
    if (r == null) {
      log('❌ 安装工具链：原生宿主没有响应（看「日志 → 通道」）');
    } else if (r['ok'] == true) {
      log('✅ 工具链就绪（${r['via'] ?? ''} ${r['toolkitVersion'] ?? ''}）'.trim());
    } else {
      log('❌ 安装工具链失败: ${r['error'] ?? r['installed'] ?? '未知原因'}');
    }
    busy = false;
    onChange();
  }

  /// Lets the user pick an .rpk that a file manager already dropped somewhere public.
  static Future<String?> pickRpkFromSdcard() async {
    for (final dir in const ['/sdcard/Download', '/sdcard/']) {
      try {
        final d = Directory(dir);
        if (!await d.exists()) continue;
        final rpk = d
            .listSync()
            .whereType<File>()
            .where((f) => f.path.toLowerCase().endsWith('.rpk'))
            .toList();
        if (rpk.isNotEmpty) return rpk.first.path;
      } catch (_) {}
    }
    return null;
  }
}
