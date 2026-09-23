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

  Future<void> build(String name) async {
    busy = true;
    log('▶ 构建 $name');
    onChange();
    await _call<Map<Object?, Object?>>('buildProject', {'name': name});
    // The native side may finish synchronously or stream buildDone events.
    busy = false;
    onChange();
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

  Future<void> buildInstallLaunch(String name,
      {String? device, String? imageType}) async {
    await build(name);
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
