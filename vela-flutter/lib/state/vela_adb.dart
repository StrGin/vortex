/// 纯 Dart 的 ADB 客户端，直连运行中的模拟器在客机侧开放的 adb 桥接。
///
/// 模拟器（Xiaomi 对 Android emulator 的 fork）启动后在 `127.0.0.1` 上监听两个
/// 端口：`5554` 是文本 console（需要 auth token，本文件不用它），`5555` 是 adb
/// transport。手机上我们的 App 是唯一的 adb 客户端（桥接一次只服务一个连接），
/// 所以直接讲裸 adb 协议即可，不需要 PC 上 adb server 那一层。
///
/// 帧格式：24 字节头 + payload；头是 6 个小端 uint32 ——
/// `msg_length`(报文总长 = 24 + data_length) / `msg_id`(消息类型，见 [AdbMsg]) /
/// `arg0` / `arg1` / `data_length` / `data_crc32`。
/// `data_crc32` 是 payload 按 32 位小端字求和（尾部不足 4 字节零补齐计入），
/// **不是** crc32；无 payload 时为 0。收包时不校验 checksum（宽松接收）。
/// 另一种常见头布局把消息类型放在第一个字（type, arg0, arg1, data_length,
/// data_check, reserved）；[connect] 会先按本文件的主布局握手，超时后自动换用
/// 该布局重试一次，收包侧则按 id/data_length 的自洽性逐帧判定（见 [_decodeHeader]）。
///
/// 一个实例只持有一个 socket 和一个流 id 分配器；所有命令串行执行（同一时刻只有
/// 一个在途 service），这样状态机最简单，也贴合桥接「单客户端」的语义。复合方法
/// （[pushDir]、[guestInfo]）不整体持锁，而是依次调用各自持锁的公共方法。
library;

import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

/// 进度回调：已传输字节 / 总字节（总数未知时为 -1）。
typedef AdbProgress = void Function(int done, int total);

class AdbException implements Exception {
  final String message;
  const AdbException(this.message);
  @override
  String toString() => message;
}

/// `sync:` LIST 回复里的一条目录项。
class AdbEntry {
  final String name;
  final int mode;
  final int size;
  final int mtimeMs;
  const AdbEntry(this.name, this.mode, this.size, this.mtimeMs);

  bool get isDir => (mode & 0xF000) == 0x4000;
  bool get isLink => (mode & 0xF000) == 0xA000;
  bool get isFile => !isDir && !isLink;
  @override
  String toString() => '${isDir ? "d" : "-"} $name${size > 0 ? " ($size)" : ""}';
}

class AdbStat {
  final bool exists;
  final int mode;
  final int size;
  final int mtimeMs;
  const AdbStat(this.exists, this.mode, this.size, this.mtimeMs);

  bool get isDir => (mode & 0xF000) == 0x4000;
}

/// adb 消息类型（桥接实际使用的取值）。
class AdbMsg {
  static const sync = 0x01000000;
  static const cnxn = 0x01000001;
  static const auth = 0x01000002;
  static const open = 0x01000003;
  static const clse = 0x01000004;
  static const wrte = 0x01000005;
  static const okay = 0x01000006;
  static const service = 0x01000007;

  static const headerSize = 24;
  static const all = <int>{sync, cnxn, auth, open, clse, wrte, okay, service};
}

Uint8List _u8(String s) => Uint8List.fromList(utf8.encode(s));

class VelaAdb {
  /// version 1.0.63
  static const protocolVersion = 0x01003ff1;
  static const defaultMaxData = 256 * 1024;
  static const features = 'shell_v2,cmd,stat_v2,ls_v2,fixed_push,reverse_v2';
  static const defaultPort = 5555;

  /// sync DATA 记录的最大负载（留出记录头，且远小于 maxdata）。
  static const syncChunk = 64 * 1024 - 16;
  static const _maxPayload = 8 * 1024 * 1024;

  String? host;
  int? port;
  String banner = '';
  int peerVersion = 0;
  int peerMaxData = defaultMaxData;

  /// 最近一次失败原因（可读文本），成功后清零；UI 直接显示它。
  String? lastError;

  Socket? _sock;
  StreamSubscription<Uint8List>? _sub;
  final _ByteQueue _frame = _ByteQueue();
  final Map<int, _AdbStream> _streams = <int, _AdbStream>{};
  Completer<void>? _cnxn;
  Completer<void>? _gate;
  int _nextId = 1;
  bool _idInFirstWord = false;

  bool get isOpen => _sock != null;
  String get describe => 'adb ${host ?? "-"}:${port ?? "-"}';

  int get _maxData {
    final m = peerMaxData;
    if (m < 4096) return 4096;
    return m > _maxPayload ? _maxPayload : m;
  }

  // ------------------------------------------------------------- 连接

  /// 建立 TCP 连接并完成 CNXN 握手。失败抛 [AdbException]。
  Future<void> connect({
    String host = '127.0.0.1',
    int port = defaultPort,
    Duration timeout = const Duration(seconds: 6),
  }) async {
    await close();
    Object? failure;
    for (var attempt = 0; attempt < 2; attempt++) {
      _idInFirstWord = attempt == 1;
      try {
        await _handshake(host, port, timeout);
        lastError = null;
        return;
      } on SocketException catch (e) {
        // 端口没人监听，换头布局也没意义
        failure = e;
        break;
      } catch (e) {
        failure = e;
        await close();
      }
    }
    await close();
    lastError = '$failure';
    throw AdbException('adb 连接 $host:$port 失败：$failure');
  }

  Future<void> _handshake(String host, int port, Duration timeout) async {
    final s = await Socket.connect(host, port, timeout: timeout);
    try {
      s.setOption(SocketOption.tcpNoDelay, true);
    } catch (_) {}
    _sock = s;
    this.host = host;
    this.port = port;
    _frame.clear();
    _streams.clear();
    final done = Completer<void>();
    _cnxn = done;
    _sub = s.listen(_onBytes, onDone: () => _dropped('连接被对端关闭'), onError: (Object e) {
      _dropped('$e');
    });
    // host:: 服务不需要结尾 NUL
    await _raw(AdbMsg.cnxn, protocolVersion, defaultMaxData, _u8('host::features=$features'));
    try {
      await done.future.timeout(timeout);
    } on TimeoutException {
      throw const AdbException('等待 CNXN 超时（5554 是 console，需要 auth token；请用 5555）');
    } finally {
      _cnxn = null;
    }
  }

  Future<void> close() async {
    final s = _sock;
    final sub = _sub;
    _sock = null;
    _sub = null;
    host = null;
    port = null;
    banner = '';
    peerVersion = 0;
    peerMaxData = defaultMaxData;
    _frame.clear();
    _dropped('连接已关闭');
    if (sub != null) {
      try {
        await sub.cancel();
      } catch (_) {}
    }
    if (s != null) {
      try {
        s.destroy();
      } catch (_) {}
    }
  }

  void _dropped(String why) {
    for (final s in _streams.values.toList(growable: false)) {
      s.fail(why);
    }
    _streams.clear();
    final c = _cnxn;
    if (c != null && !c.isCompleted) c.completeError(AdbException(why));
  }

  // ------------------------------------------------------------- shell

  /// `shell:<cmd>`：返回客机输出（stdout+stderr 混流）。
  Future<String> shell(String cmd, {Duration? timeout}) {
    final t = timeout ?? const Duration(seconds: 20);
    return _lock(() async {
      _requireOpen();
      final s = await _open('shell:$cmd', t);
      try {
        try {
          await s.ended.future.timeout(t);
        } on TimeoutException {
          throw AdbException('命令超时（${t.inSeconds}s）：$cmd');
        }
        final out = utf8.decode(s.rx.takeAll(), allowMalformed: true);
        final err = s.error;
        if (err != null) throw AdbException('$cmd: $err');
        return out;
      } finally {
        await _closeStream(s, t);
      }
    });
  }

  /// 客机版本信息：依次试几条命令，取第一条有正经输出的；全失败退化成 `ls /`。
  Future<String> guestInfo({Duration? timeout}) async {
    const probes = <String>[
      'uname -a',
      'uname',
      'cat /proc/version',
      'cat /etc/version',
      'ls /',
    ];
    String? dir;
    for (final p in probes) {
      String out;
      try {
        out = (await shell(p, timeout: timeout)).trim();
      } catch (e) {
        lastError = '$e';
        continue;
      }
      if (out.isEmpty) continue;
      final bad = out.contains('not found') ||
          out.contains('No such file') ||
          out.contains('usage:') ||
          out.contains('command not found');
      if (p == 'ls /') {
        dir = out.replaceAll('\n', ' ');
        if (!bad) break;
        continue;
      }
      if (bad) continue;
      return out.replaceAll('\n', ' / ');
    }
    if (dir != null) return 'nsh；根目录:$dir';
    throw AdbException(lastError ?? '客机未返回版本信息');
  }

  // -------------------------------------------------------------- sync

  /// `sync:` STAT。路径不存在时返回 `exists == false`（不抛）。
  Future<AdbStat> stat(String remotePath, {Duration? timeout}) {
    final t = timeout ?? const Duration(seconds: 15);
    return _lock(() async {
      _requireOpen();
      final s = await _open('sync:', t);
      try {
        final c = _Sync(this, s);
        await c.request('STAT', _u8(remotePath), t);
        final tag = await c.tag(t);
        if (!_tagMatch(tag, 'STAT')) {
          final n = await c.length(t);
          final body = await c.bytes(n, t);
          if (_tagMatch(tag, 'FAIL')) throw AdbException(utf8.decode(body, allowMalformed: true));
          throw AdbException('STAT 回复异常（tag=${_tagName(tag)}），客机可能未启用 sync 服务');
        }
        final n = await c.length(t);
        final body = await c.bytes(n, t);
        if (body.length < 12) {
          throw AdbException('STAT 回复长度异常（$n 字节）');
        }
        final bd = ByteData.sublistView(body);
        final mode = bd.getUint32(0, Endian.little);
        final size = bd.getUint32(4, Endian.little);
        final mtime = bd.getUint32(8, Endian.little);
        if (mode == 0) return const AdbStat(false, 0, 0, 0);
        return AdbStat(true, mode, size, mtime * 1000);
      } finally {
        await _closeStream(s, t);
      }
    });
  }

  /// `sync:` LIST。
  Future<List<AdbEntry>> list(String remotePath, {Duration? timeout}) {
    final t = timeout ?? const Duration(seconds: 20);
    return _lock(() async {
      _requireOpen();
      final s = await _open('sync:', t);
      try {
        final c = _Sync(this, s);
        await c.request('LIST', _u8(remotePath), t);
        final out = <AdbEntry>[];
        while (true) {
          final tag = await c.tag(t);
          if (_tagMatch(tag, 'DONE')) break;
          final n = await c.length(t);
          final body = await c.bytes(n, t);
          if (_tagMatch(tag, 'FAIL')) {
            throw AdbException(utf8.decode(body, allowMalformed: true));
          }
          final isEntry = _tagMatch(tag, 'DENT') || _tagMatch(tag, 'LIST') || _tagMatch(tag, 'STAT');
          if (!isEntry) {
            throw AdbException('LIST 回复异常（tag=${_tagName(tag)}）');
          }
          if (body.length < 12) continue;
          final bd = ByteData.sublistView(body);
          out.add(AdbEntry(
            utf8.decode(body.sublist(12), allowMalformed: true).replaceAll('\x00', ''),
            bd.getUint32(0, Endian.little),
            bd.getUint32(4, Endian.little),
            bd.getUint32(8, Endian.little) * 1000,
          ));
        }
        return out;
      } finally {
        await _closeStream(s, t);
      }
    });
  }

  /// `sync:` SEND：把本地文件推进客机，返回发送的字节数。
  ///
  /// 序列：`SEND "<path>,<mode>"` → 若干 `DATA` → `DONE` → 等 `OKAY`。
  Future<int> pushFile(String localPath, String remotePath,
      {AdbProgress? onProgress, Duration? timeout, String mode = '0644'}) async {
    final f = File(localPath);
    if (!f.existsSync()) throw AdbException('本地文件不存在: $localPath');
    final total = f.lengthSync();
    final t = timeout ?? const Duration(seconds: 30) * (1 + (total ~/ (256 * 1024))).clamp(1, 40);
    return _lock(() async {
      _requireOpen();
      final s = await _open('sync:', t);
      try {
        final c = _Sync(this, s);
        await c.request('SEND', _u8('$remotePath,$mode'), t);
        final raf = await f.open();
        var sent = 0;
        try {
          while (true) {
            final buf = await raf.read(syncChunk);
            if (buf.isEmpty) break;
            await c.data(buf, t);
            sent += buf.length;
            onProgress?.call(sent, total);
            if (buf.length < syncChunk && sent >= total) break;
          }
        } finally {
          await raf.close();
        }
        await c.done(t);
        await c.expectOkay(t, 'push $remotePath');
        return sent;
      } finally {
        await _closeStream(s, t);
      }
    });
  }

  /// 递归推目录：先 `mkdir` 出目录树，再逐个 [pushFile]。返回字节数。
  Future<int> pushDir(String localDir, String remoteDir,
      {AdbProgress? onProgress, Duration? timeout}) async {
    final root = Directory(localDir);
    if (!root.existsSync()) throw AdbException('本地目录不存在: $localDir');
    final dirs = <String>[];
    final files = <File>[];
    var total = 0;
    for (final e in root.listSync(recursive: true, followLinks: false)) {
      final rel = _relative(e.path, root.path);
      if (rel.isEmpty) continue;
      if (e is Directory) {
        dirs.add(rel);
      } else if (e is File) {
        files.add(e);
        total += e.lengthSync();
      }
    }
    dirs.sort((a, b) => a.split('/').length - b.split('/').length);
    for (final d in dirs) {
      // nsh 的 mkdir 不一定支持 -p：失败就退回单级再试一次，仍失败只记 lastError。
      try {
        await shell('mkdir -p "$remoteDir/$d"', timeout: timeout);
      } catch (_) {
        try {
          await shell('mkdir "$remoteDir/$d"', timeout: timeout);
        } catch (e) {
          lastError = 'mkdir $d: $e';
        }
      }
    }
    var sent = 0;
    for (final f in files) {
      final rel = _relative(f.path, root.path);
      final base = sent;
      sent += await pushFile(f.path, '$remoteDir/$rel',
          onProgress: (d, _) => onProgress?.call(base + d, total), timeout: timeout);
    }
    return sent;
  }

  /// `sync:` RECV：把客机文件拉到本地，返回写出的字节数。
  Future<int> pullFile(String remotePath, String localPath,
      {AdbProgress? onProgress, Duration? timeout}) {
    final t = timeout ?? const Duration(seconds: 60);
    return _lock(() async {
      _requireOpen();
      final s = await _open('sync:', t);
      final sink = File(localPath).openWrite();
      try {
        final c = _Sync(this, s);
        await c.request('RECV', _u8(remotePath), t);
        var got = 0;
        while (true) {
          final tag = await c.tag(t);
          final n = await c.length(t);
          final body = await c.bytes(n, t);
          if (_tagMatch(tag, 'RECV')) continue; // 少数实现会先回显请求 tag
          if (_tagMatch(tag, 'DATA')) {
            sink.add(body);
            got += body.length;
            onProgress?.call(got, -1);
            continue;
          }
          if (_tagMatch(tag, 'DONE')) break;
          if (_tagMatch(tag, 'FAIL')) {
            throw AdbException(utf8.decode(body, allowMalformed: true));
          }
          throw AdbException('RECV 回复异常（tag=${_tagName(tag)}）');
        }
        await sink.flush();
        return got;
      } finally {
        try {
          await sink.close();
        } catch (_) {}
        await _closeStream(s, t);
      }
    });
  }

  // ---------------------------------------------------------- 内部管线

  void _requireOpen() {
    if (!isOpen) {
      throw AdbException('adb 未连接（模拟器 adb 端口 ${port ?? defaultPort}，需引擎在跑）');
    }
  }

  /// 串行化：同一时刻只有一个 service 在途。
  Future<R> _lock<R>(Future<R> Function() body) async {
    while (_gate != null) {
      await _gate!.future;
    }
    final g = Completer<void>();
    _gate = g;
    try {
      return await body();
    } finally {
      _gate = null;
      if (!g.isCompleted) g.complete();
    }
  }

  Future<void> _raw(int id, int arg0, int arg1, Uint8List data) async {
    final s = _sock;
    if (s == null) throw const AdbException('adb 未连接');
    s.add(_encode(id, arg0, arg1, data));
    await s.flush();
  }

  Uint8List _encode(int id, int arg0, int arg1, Uint8List data) {
    final out = Uint8List(AdbMsg.headerSize + data.length);
    final h = ByteData.sublistView(out, 0, AdbMsg.headerSize);
    final check = _checksum(data);
    if (_idInFirstWord) {
      // type, arg0, arg1, data_length, data_check, reserved
      h.setUint32(0, id, Endian.little);
      h.setUint32(4, arg0, Endian.little);
      h.setUint32(8, arg1, Endian.little);
      h.setUint32(12, data.length, Endian.little);
      h.setUint32(16, check, Endian.little);
      h.setUint32(20, 0, Endian.little);
    } else {
      // msg_length, msg_id, arg0, arg1, data_length, data_crc32
      h.setUint32(0, AdbMsg.headerSize + data.length, Endian.little);
      h.setUint32(4, id, Endian.little);
      h.setUint32(8, arg0, Endian.little);
      h.setUint32(12, arg1, Endian.little);
      h.setUint32(16, data.length, Endian.little);
      h.setUint32(20, check, Endian.little);
    }
    out.setRange(AdbMsg.headerSize, out.length, data);
    return out;
  }

  /// 逐帧判定布局：id 落在哪个字、data_length 落在哪个字。
  _AdbHeader? _decodeHeader() {
    final w0 = _frame.u32(0);
    final w1 = _frame.u32(4);
    final w2 = _frame.u32(8);
    final w3 = _frame.u32(12);
    final w4 = _frame.u32(16);
    final aOk = AdbMsg.all.contains(w1) && w4 <= _maxPayload; // 主布局
    final bOk = AdbMsg.all.contains(w0) && w3 <= _maxPayload; // type 在第一个字
    final useB = _idInFirstWord ? bOk : (bOk && !aOk);
    if (useB) return _AdbHeader(w0, w1, w2, w3);
    if (aOk) return _AdbHeader(w1, w2, w3, w4);
    if (bOk) return _AdbHeader(w0, w1, w2, w3);
    return null;
  }

  void _onBytes(Uint8List data) {
    _frame.add(data);
    while (true) {
      if (_frame.length < AdbMsg.headerSize) return;
      final hdr = _decodeHeader();
      if (hdr == null) {
        lastError = '无法解析 adb 帧头';
        _dropped(lastError!);
        return;
      }
      if (_frame.length < AdbMsg.headerSize + hdr.dataLength) return; // 等待 payload
      _frame.skip(AdbMsg.headerSize);
      final payload = _frame.take(hdr.dataLength);
      _dispatch(hdr.id, hdr.arg0, hdr.arg1, payload);
    }
  }

  void _dispatch(int id, int arg0, int arg1, Uint8List data) {
    switch (id) {
      case AdbMsg.cnxn:
        peerVersion = arg0;
        peerMaxData = arg1 > 0 ? arg1 : defaultMaxData;
        banner = utf8.decode(data, allowMalformed: true).replaceAll('\x00', '');
        final c = _cnxn;
        if (c != null && !c.isCompleted) c.complete();
        return;
      case AdbMsg.auth:
        const why = '对端要求 adb 认证（该桥接应无需 token）';
        final c = _cnxn;
        if (c != null && !c.isCompleted) {
          c.completeError(const AdbException(why));
        } else {
          lastError = why;
        }
        return;
      case AdbMsg.okay:
      case AdbMsg.wrte:
      case AdbMsg.clse:
        final s = _find(arg0, arg1);
        if (s == null) return;
        if (id == AdbMsg.okay) {
          s.onOkay(arg0, arg1, data);
        } else if (id == AdbMsg.wrte) {
          s.onWrte(arg0, arg1, data);
        } else {
          s.onClse();
        }
        return;
      default:
        lastError = '未知 adb 消息 0x${id.toRadixString(16)}';
    }
  }

  _AdbStream? _find(int arg0, int arg1) {
    final s = _streams[arg1] ?? _streams[arg0];
    if (s == null) return null;
    return s.matches(arg0, arg1) ? s : null;
  }

  Future<_AdbStream> _open(String service, Duration timeout) async {
    final id = _nextId++;
    final s = _AdbStream(this, id, service);
    _streams[id] = s;
    try {
      await _raw(AdbMsg.open, id, 0, _u8('$service\x00'));
      await s.opened.future.timeout(timeout);
      return s;
    } on TimeoutException {
      _streams.remove(id);
      throw AdbException('打开 $service 超时（${timeout.inSeconds}s）');
    } catch (e) {
      _streams.remove(id);
      rethrow;
    }
  }

  Future<void> _closeStream(_AdbStream s, Duration timeout) async {
    _streams.remove(s.localId);
    if (s.closed) return;
    s.closed = true;
    try {
      await _raw(AdbMsg.clse, s.localId, s.remoteId < 0 ? 0 : s.remoteId, Uint8List(0));
    } catch (e) {
      s.fail('$e');
    }
  }
}

class _AdbHeader {
  final int id;
  final int arg0;
  final int arg1;
  final int dataLength;
  const _AdbHeader(this.id, this.arg0, this.arg1, this.dataLength);
}

/// 一条已打开的 service 流。
class _AdbStream {
  final VelaAdb adb;
  final int localId;
  final String service;
  final _ByteQueue rx = _ByteQueue();

  int remoteId = -1;
  bool accepted = false;
  bool closed = false;
  String? error;

  final Completer<void> opened = Completer<void>();
  final Completer<void> ended = Completer<void>();
  Completer<void>? ack;
  Completer<void>? waker;

  _AdbStream(this.adb, this.localId, this.service);

  bool matches(int arg0, int arg1) {
    if (remoteId < 0) return arg0 == localId || arg1 == localId;
    return (arg0 == remoteId && arg1 == localId) || (arg0 == localId && arg1 == remoteId);
  }

  void fail(String why) {
    error ??= why;
    if (!opened.isCompleted) opened.completeError(AdbException('$service: $why'));
    if (!ended.isCompleted) ended.complete();
    _wake();
  }

  void onOkay(int arg0, int arg1, Uint8List data) {
    if (!accepted) {
      accepted = true;
      remoteId = arg0 == localId ? arg1 : arg0;
      if (data.isEmpty) {
        if (!opened.isCompleted) opened.complete();
      } else {
        // 该桥接把 FAIL 表达为「带错误文本的 OKAY」
        if (!opened.isCompleted) {
          opened.completeError(AdbException('$service: ${utf8.decode(data, allowMalformed: true)}'));
        }
      }
      return;
    }
    final a = ack;
    ack = null;
    if (a != null && !a.isCompleted) a.complete();
  }

  void onWrte(int arg0, int arg1, Uint8List data) {
    if (!accepted) {
      accepted = true;
      remoteId = arg0 == localId ? arg1 : arg0;
      if (!opened.isCompleted) opened.complete();
    }
    rx.add(data);
    _wake();
    // 两个方向的 WRTE 都要用 OKAY 确认（arg0=本地 id，arg1=对端 id）。
    adb._raw(AdbMsg.okay, localId, remoteId < 0 ? 0 : remoteId, Uint8List(0)).catchError((Object e) {
      fail('确认 WRTE 失败: $e');
    });
  }

  void onClse() {
    if (closed) return;
    closed = true;
    if (!accepted) {
      fail('对端在 OKAY 之前就关闭了流（服务不被支持？）');
      return;
    }
    if (!ended.isCompleted) ended.complete();
    _wake();
  }

  Future<void> write(Uint8List data, Duration timeout) async {
    var off = 0;
    while (off < data.length) {
      final n = data.length - off > adb._maxData ? adb._maxData : data.length - off;
      final a = Completer<void>();
      ack = a;
      await adb._raw(AdbMsg.wrte, localId, remoteId, Uint8List.sublistView(data, off, off + n));
      off += n;
      try {
        await a.future.timeout(timeout);
      } on TimeoutException {
        ack = null;
        throw AdbException('等待 $service 的 WRTE 确认超时');
      }
    }
  }

  /// 等 rx 里至少 n 字节。
  Future<void> need(int n, Duration timeout) async {
    final end = DateTime.now().add(timeout);
    while (rx.length < n) {
      final err = error;
      if (err != null) throw AdbException('$service: $err');
      if (closed) {
        throw AdbException('$service 数据不足（需要 $n 字节，只有 ${rx.length}）');
      }
      var left = end.difference(DateTime.now());
      if (left.isNegative) throw AdbException('读取 $service 数据超时');
      if (left > const Duration(seconds: 5)) left = const Duration(seconds: 5);
      final w = Completer<void>();
      waker = w;
      await w.future.timeout(left, onTimeout: () => null);
      waker = null;
    }
  }

  void _wake() {
    final w = waker;
    waker = null;
    if (w != null && !w.isCompleted) w.complete();
  }
}

/// `sync:` 子协议：记录 = 4 字节 ASCII tag + 4 字节长度 + payload。
///
/// 记录会被攒进一个缓冲区，满 maxdata 时用一条 WRTE 发出去（并等 OKAY），
/// 这样几百次 DATA 往返不会变成几千次。
class _Sync {
  final VelaAdb adb;
  final _AdbStream s;
  final BytesBuilder _out = BytesBuilder(copy: true);

  _Sync(this.adb, this.s);

  Future<void> request(String tag, Uint8List payload, Duration t) async {
    _record(tag, payload);
    await flush(t);
  }

  Future<void> data(Uint8List payload, Duration t) async {
    if (_out.length + 8 + payload.length > adb._maxData) await flush(t);
    _record('DATA', payload);
    if (_out.length + 16 >= adb._maxData) await flush(t);
  }

  Future<void> done(Duration t) async {
    _record('DONE', Uint8List(0));
    await flush(t);
  }

  void _record(String tag, Uint8List payload) {
    final h = Uint8List(8);
    final bd = ByteData.sublistView(h);
    bd.setUint32(0, _tagOf(tag), Endian.little);
    bd.setUint32(4, payload.length, Endian.little);
    _out.add(h);
    if (payload.isNotEmpty) _out.add(payload);
  }

  Future<void> flush(Duration t) async {
    if (_out.isEmpty) return;
    await s.write(Uint8List.fromList(_out.takeBytes()), t);
  }

  /// peek 4 字节 tag（不消费）。
  Future<int> tag(Duration t) async {
    await s.need(4, t);
    return s.rx.u32(0);
  }

  /// pop 一个 uint32（长度/字段）。
  Future<int> length(Duration t) async {
    await s.need(4, t);
    return s.rx.popU32();
  }

  Future<Uint8List> bytes(int n, Duration t) async {
    if (n <= 0) return Uint8List(0);
    await s.need(n, t);
    return s.rx.take(n);
  }

  Future<void> expectOkay(Duration t, String what) async {
    final tag = await this.tag(t);
    final n = await length(t);
    final body = await bytes(n, t);
    if (_tagMatch(tag, 'OKAY') || _tagMatch(tag, 'DONE')) return;
    throw AdbException(
        '$what 失败: ${utf8.decode(body, allowMalformed: true).trim()}（tag=${_tagName(tag)}；'
        '若客机未启用 sync 服务，请改用原生 installRpk）');
  }
}

// ------------------------------------------------------------------ 工具

/// payload 的 32 位小端字求和（不是 crc32）。
int _checksum(Uint8List d) {
  if (d.isEmpty) return 0;
  final bd = ByteData.sublistView(d);
  var sum = 0;
  final words = d.lengthInBytes >> 2;
  for (var i = 0; i < words; i++) {
    sum = (sum + bd.getUint32(i * 4, Endian.little)) & 0xffffffff;
  }
  final tail = d.lengthInBytes & 3;
  if (tail != 0) {
    var w = 0;
    for (var i = 0; i < tail; i++) {
      w |= d[words * 4 + i] << (8 * i);
    }
    sum = (sum + w) & 0xffffffff;
  }
  return sum;
}

int _tagOf(String tag) {
  var v = 0;
  for (var i = 0; i < 4 && i < tag.length; i++) {
    v |= (tag.codeUnitAt(i) & 0xff) << (8 * i);
  }
  return v;
}

String _tagName(int tag) {
  final b = Uint8List(4);
  ByteData.sublistView(b).setUint32(0, tag, Endian.little);
  return String.fromCharCodes(b.map((c) => c >= 0x20 && c < 0x7f ? c : 0x2e));
}

/// sync 的回复 tag 是请求 tag 的变形。不同实现见过三种写法：
/// 原样（`STAT`）、末位变 2（`STA2`）、整体转一字节（`TAT2`），以及
/// 「请求 tag + 0x00020202」。这里全都认。
bool _tagMatch(int raw, String want) {
  final base = _tagOf(want);
  if (raw == base || raw == ((base + 0x00020202) & 0xffffffff)) return true;
  final got = _tagName(raw);
  if (got == want) return true;
  if (!got.endsWith('2') || want.length != 4) return false;
  final g = got.substring(0, 3);
  return g == want.substring(1) || g == want.substring(0, 3);
}

String _relative(String path, String root) {
  final p = path.replaceAll(r'\', '/');
  var r = root.replaceAll(r'\', '/');
  if (r.endsWith('/')) r = r.substring(0, r.length - 1);
  if (!p.startsWith(r)) return p.startsWith('/') ? p.substring(1) : p;
  var rest = p.substring(r.length);
  if (rest.startsWith('/')) rest = rest.substring(1);
  return rest;
}

/// 极简字节队列：socket 帧缓冲与每条流的 rx 共用。
class _ByteQueue {
  Uint8List _b = Uint8List(0);
  int _r = 0;
  int _w = 0;

  int get length => _w - _r;
  bool get isEmpty => _w == _r;

  void add(List<int> data) {
    if (data.isEmpty) return;
    final src = data is Uint8List ? data : Uint8List.fromList(data);
    final live = length;
    if (_w + src.length > _b.length) {
      var cap = _b.isEmpty ? 4096 : _b.length * 2;
      while (cap < live + src.length) {
        cap *= 2;
      }
      final nb = Uint8List(cap);
      nb.setRange(0, live, _b, _r);
      _b = nb;
      _r = 0;
      _w = live;
    }
    _b.setRange(_w, _w + src.length, src);
    _w += src.length;
  }

  int u32(int off) => ByteData.sublistView(_b, _r + off, _r + off + 4).getUint32(0, Endian.little);

  int popU32() {
    final v = u32(0);
    skip(4);
    return v;
  }

  void skip(int n) {
    _r += n;
    if (_r >= _w) {
      _r = 0;
      _w = 0;
    }
  }

  Uint8List take(int n) {
    if (n <= 0) return Uint8List(0);
    final out = Uint8List.fromList(_b.sublist(_r, _r + n));
    skip(n);
    return out;
  }

  Uint8List takeAll() => take(length);

  void clear() {
    _r = 0;
    _w = 0;
  }
}
