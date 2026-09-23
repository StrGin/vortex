/// Vela device profile + system image catalogue, mirroring
/// `assets/vela/devices.json` (produced by tools/pack-engine.py).
library;

class DeviceProfile {
  final String avdId;
  final String skin;
  final int width;
  final int height;
  final int cornerRadius;
  final String shape;
  final int density;
  final String flavor;
  final String imageType;
  final int ncore;
  final int ramSizeMb;

  const DeviceProfile({
    required this.avdId,
    required this.skin,
    required this.width,
    required this.height,
    required this.cornerRadius,
    required this.shape,
    required this.density,
    required this.flavor,
    required this.imageType,
    required this.ncore,
    required this.ramSizeMb,
  });

  bool get isCircle => shape == 'circle';
  bool get isPill => shape == 'pill-shaped';

  static DeviceProfile fromMap(Map<Object?, Object?> m) => DeviceProfile(
        avdId: (m['avdId'] ?? m['skin'] ?? '') as String,
        skin: (m['skin'] ?? '') as String,
        width: _i(m['width'], 466),
        height: _i(m['height'], 466),
        cornerRadius: _i(m['cornerRadius'] ?? m['corner_radius'], 0),
        shape: _s(m['shapeName'] ?? m['shape'], 'rect'),
        density: _i(m['density'], 320),
        flavor: (m['flavor'] ?? 'watch') as String,
        imageType: (m['imageType'] ?? 'vela-miwear-watch-5.0') as String,
        ncore: _i(m['ncore'], 2),
        ramSizeMb: _i(m['ramSizeMb'], 1024),
      );

  static int _i(Object? v, int dflt) => v is int ? v : int.tryParse('$v') ?? dflt;

  /// Native sends both `shape` (the hw.lcd.shape code) and `shapeName`; the
  /// shape code also decodes in case only the numeric field is present.
  /// Codes come from VelaDevices.shapeCode: 0=rect, 1=circle, 2=pill-shaped.
  static String _s(Object? v, String dflt) {
    if (v is String && v.isNotEmpty) return v;
    if (v is int) return const {0: 'rect', 1: 'circle', 2: 'pill-shaped'}[v] ?? dflt;
    return dflt;
  }
}

class SystemImage {
  final String type;
  final String time;
  final String label;
  final String flavor;
  final int size;
  final bool installed;
  final bool complete;

  const SystemImage({
    required this.type,
    required this.time,
    required this.label,
    required this.flavor,
    required this.size,
    required this.installed,
    required this.complete,
  });

  static SystemImage fromMap(Map<Object?, Object?> m) => SystemImage(
        type: (m['type'] ?? '') as String,
        time: (m['time'] ?? '') as String,
        label: (m['label'] ?? (m['type'] ?? '')) as String,
        flavor: (m['flavor'] ?? 'watch') as String,
        // Java writes `sizeBytes`; `size` is only kept for older payloads.
        size: _int(m['sizeBytes'] ?? m['size']),
        installed: m['installed'] == true,
        complete: m['complete'] == true,
      );

  static int _int(Object? v) => v is int ? v : int.tryParse('$v') ?? 0;
}

class EngineStatus {
  final bool running;
  final String? avd;
  final int pid;
  final int grpcPort;
  final String? error;
  /// Native filesystem layout (engine dir, image dir, avd home, ...).
  final Map<String, String> paths;
  /// True while the native host is busy with a blocking task (image transfer).
  final bool busy;

  const EngineStatus({
    this.running = false,
    this.avd,
    this.pid = 0,
    this.grpcPort = 8554,
    this.error,
    this.paths = const {},
    this.busy = false,
  });

  static EngineStatus fromMap(Map<Object?, Object?> m) => EngineStatus(
        running: m['running'] == true,
        avd: m['avd'] as String?,
        pid: m['pid'] is int ? m['pid'] as int : int.tryParse('${m['pid']}') ?? 0,
        grpcPort: m['grpcPort'] is int ? m['grpcPort'] as int : int.tryParse('${m['grpcPort']}') ?? 8554,
        error: m['error'] as String?,
        paths: (m['paths'] as Map?)?.map((k, v) => MapEntry('$k', '$v')) ?? const {},
        busy: m['busy'] == true,
      );
}

/// 可执行负载的落点，来自 `nativeStatus`：loader 与 exec stub 在 APK 的 native
/// 库目录里（标签 apk_data_file，应用域可直接执行），引擎与工具链本体留在私有目录。
class NativeRuntimeStatus {
  final String nativeDir;
  final bool loaderReady;
  final bool stubReady;

  const NativeRuntimeStatus({
    this.nativeDir = '',
    this.loaderReady = false,
    this.stubReady = false,
  });

  bool get ready => loaderReady && stubReady;

  static NativeRuntimeStatus fromMap(Map<Object?, Object?> m) => NativeRuntimeStatus(
        nativeDir: '${m['nativeDir'] ?? ''}',
        loaderReady: m['loaderReady'] == true,
        stubReady: m['stubReady'] == true,
      );
}

/// node / aiot-toolkit availability, from the `toolchain` event and
/// `toolchainStatus`. Mirrors VelaToolchain#status.
class ToolchainStatus {
  final bool nodeAvailable;
  final String? nodeVersion;
  final bool toolkitInstalled;
  final String? toolkitVersion;
  final String? error;
  /// 现在恒为 local（本进程直接 spawn，loader 在 jniLibs）
  final String execMode;

  const ToolchainStatus({
    this.nodeAvailable = false,
    this.nodeVersion,
    this.toolkitInstalled = false,
    this.toolkitVersion,
    this.error,
    this.execMode = 'unknown',
  });

  static ToolchainStatus fromMap(Map<Object?, Object?> m) => ToolchainStatus(
        nodeAvailable: m['nodeAvailable'] == true,
        nodeVersion: m['nodeVersion'] as String?,
        toolkitInstalled: m['toolkitInstalled'] == true,
        toolkitVersion: m['toolkitVersion'] as String?,
        error: m['error'] as String?,
        execMode: '${m['execMode'] ?? 'unknown'}',
      );
}
