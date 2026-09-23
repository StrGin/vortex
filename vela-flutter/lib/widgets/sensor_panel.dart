import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../state/app_state.dart';
import '../state/vela_bridge.dart';
import '../state/vela_grpc.dart';
import '../theme/app_theme.dart';
import 'common.dart';

/// Injects sensor / GPS / battery values into the guest over gRPC, mirroring the
/// emulator's own extended-controls panel.
class SensorPanel extends StatefulWidget {
  const SensorPanel({super.key});

  @override
  State<SensorPanel> createState() => _SensorPanelState();
}

class _SensorPanelState extends State<SensorPanel> {
  double heart = 72;
  double battery = 100;
  double steps = 4321;
  double temp = 36.5;
  double lat = 39.9042;
  double lon = 116.4074;
  bool charging = false;

  /// 默认展开：这些注入项藏起来就没人知道有（之前默认收起，用户根本不知道能注入）。
  bool open = true;

  late final TextEditingController _latCtl =
      TextEditingController(text: lat.toStringAsFixed(4));
  late final TextEditingController _lonCtl =
      TextEditingController(text: lon.toStringAsFixed(4));

  @override
  void dispose() {
    _latCtl.dispose();
    _lonCtl.dispose();
    super.dispose();
  }

  void _setCoords(double la, double lo) {
    setState(() {
      lat = la;
      lon = lo;
      _latCtl.text = la.toStringAsFixed(4);
      _lonCtl.text = lo.toStringAsFixed(4);
    });
  }

  Future<void> _apply(VelaBridge b, Future<void> Function(VelaBridge) fn) async {
    if (!b.grpc.connected) {
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(const SnackBar(content: Text('未连接模拟器')));
      }
      return;
    }
    try {
      await fn(b);
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(const SnackBar(
            duration: Duration(milliseconds: 900), content: Text('已发送')));
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text('$e')));
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final b = AppScope.of(context).bridge;
    final scheme = Theme.of(context).colorScheme;

    return SectionCard(
      title: '传感器 / 电池 / 定位',
      icon: Icons.sensors,
      trailing: IconButton(
        tooltip: open ? '收起' : '展开',
        visualDensity: VisualDensity.compact,
        icon: Icon(open ? Icons.expand_less : Icons.expand_more, size: 20),
        onPressed: () => setState(() => open = !open),
      ),
      children: [
        AnimatedCrossFade(
          duration: Motion.base,
          crossFadeState:
              open ? CrossFadeState.showSecond : CrossFadeState.showFirst,
          firstChild: Text(
            '心率、步数、温度、电量与定位，按需注入客机。',
            style: Theme.of(context).textTheme.bodySmall?.copyWith(
                  color: scheme.onSurfaceVariant,
                ),
          ),
          secondChild: Column(
            children: [
              _slider('心率 bpm', 30, 200, heart, (v) => heart = v,
                  () => _apply(b, (x) => x.sensor(VelaSensor.heartRate, [heart]))),
              _slider('步数', 0, 30000, steps, (v) => steps = v,
                  () => _apply(b,
                      (x) => x.sensor(VelaSensor.acceleration, [steps / 100, 0, 0]))),
              _slider('温度 °C', 20, 45, temp, (v) => temp = v,
                  () => _apply(b, (x) => x.sensor(VelaSensor.temperature, [temp]))),
              _slider(
                  '电量 %',
                  0,
                  100,
                  battery,
                  (v) => battery = v,
                  () => _apply(
                      b,
                      (x) => x.battery(battery.round(),
                          status: charging ? 1 : 2, charger: charging ? 1 : 0))),
              SwitchListTile(
                contentPadding: EdgeInsets.zero,
                dense: true,
                title: const Text('充电中'),
                value: charging,
                onChanged: (v) {
                  setState(() => charging = v);
                  _apply(
                      b,
                      (x) => x.battery(battery.round(),
                          status: v ? 1 : 2, charger: v ? 1 : 0));
                },
              ),
              Align(
                alignment: Alignment.centerLeft,
                child: Wrap(
                  spacing: Insets.sm,
                  children: [
                    for (final pct in const [5, 20, 50, 100])
                      ActionChip(
                        label: Text('$pct%'),
                        onPressed: () {
                          setState(() => battery = pct.toDouble());
                          _apply(
                              b,
                              (x) => x.battery(pct,
                                  status: charging ? 1 : 2,
                                  charger: charging ? 1 : 0));
                        },
                      ),
                  ],
                ),
              ),
              // 定位：坐标用输入框（滑块调不出精确坐标），再配几个城市预设。
              Row(
                children: [
                  Expanded(
                    child: TextField(
                      controller: _latCtl,
                      keyboardType: const TextInputType.numberWithOptions(
                          decimal: true, signed: true),
                      decoration: const InputDecoration(
                          labelText: '纬度', isDense: true),
                      onSubmitted: (v) =>
                          setState(() => lat = double.tryParse(v) ?? lat),
                    ),
                  ),
                  const SizedBox(width: Insets.sm),
                  Expanded(
                    child: TextField(
                      controller: _lonCtl,
                      keyboardType: const TextInputType.numberWithOptions(
                          decimal: true, signed: true),
                      decoration: const InputDecoration(
                          labelText: '经度', isDense: true),
                      onSubmitted: (v) =>
                          setState(() => lon = double.tryParse(v) ?? lon),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: Insets.sm),
              Align(
                alignment: Alignment.centerLeft,
                child: Wrap(
                  spacing: Insets.sm,
                  runSpacing: Insets.sm,
                  children: [
                    for (final city in const [
                      ('北京', 39.9042, 116.4074),
                      ('上海', 31.2304, 121.4737),
                      ('深圳', 22.5431, 114.0579),
                      ('纽约', 40.7128, -74.0060),
                    ])
                      ActionChip(
                        label: Text(city.$1),
                        onPressed: () => _setCoords(city.$2, city.$3),
                      ),
                  ],
                ),
              ),
              const SizedBox(height: Insets.sm),
              Align(
                alignment: Alignment.centerLeft,
                child: Wrap(
                  spacing: Insets.sm,
                  runSpacing: Insets.sm,
                  children: [
                    FilledButton.tonal(
                        onPressed: () {
                          final la = double.tryParse(_latCtl.text) ?? lat;
                          final lo = double.tryParse(_lonCtl.text) ?? lon;
                          setState(() {
                            lat = la;
                            lon = lo;
                          });
                          _apply(b, (x) => x.gps(la, lo));
                        },
                        child: const Text('发送定位')),
                    OutlinedButton(
                        onPressed: () => _apply(b, (x) => x.vmState(VelaVm.paused)),
                        child: const Text('暂停手表')),
                    OutlinedButton(
                        onPressed: () => _apply(b, (x) => x.vmState(VelaVm.running)),
                        child: const Text('恢复手表')),
                    OutlinedButton(
                        onPressed: () => _apply(b, (x) => x.vmState(VelaVm.restart)),
                        child: const Text('重启手表')),
                  ],
                ),
              ),
              const Divider(height: Insets.xl),
              // 剪贴板：手表上打不了字，靠这个把手机里的文本送过去。
              Row(
                children: [
                  Expanded(
                    child: OutlinedButton.icon(
                      icon: const Icon(Icons.upload_outlined, size: 18),
                      label: const Text('发送剪贴板'),
                      onPressed: () async {
                        final data = await Clipboard.getData(Clipboard.kTextPlain);
                        final text = data?.text ?? '';
                        if (text.isEmpty) {
                          if (mounted) {
                            ScaffoldMessenger.of(context).showSnackBar(
                                const SnackBar(content: Text('手机剪贴板是空的')));
                          }
                          return;
                        }
                        await _apply(b, (x) => x.setClipboard(text));
                      },
                    ),
                  ),
                  const SizedBox(width: Insets.sm),
                  Expanded(
                    child: OutlinedButton.icon(
                      icon: const Icon(Icons.download_outlined, size: 18),
                      label: const Text('取回剪贴板'),
                      onPressed: () async {
                        if (!b.grpc.connected) {
                          if (mounted) {
                            ScaffoldMessenger.of(context).showSnackBar(
                                const SnackBar(content: Text('未连接模拟器')));
                          }
                          return;
                        }
                        try {
                          final text = await b.grpc.getClipboard();
                          if (text.isEmpty) {
                            if (mounted) {
                              ScaffoldMessenger.of(context).showSnackBar(
                                  const SnackBar(content: Text('手表剪贴板是空的')));
                            }
                            return;
                          }
                          await Clipboard.setData(ClipboardData(text: text));
                          if (mounted) {
                            ScaffoldMessenger.of(context).showSnackBar(
                                const SnackBar(content: Text('已复制到手机剪贴板')));
                          }
                        } catch (e) {
                          if (mounted) {
                            ScaffoldMessenger.of(context)
                                .showSnackBar(SnackBar(content: Text('$e')));
                          }
                        }
                      },
                    ),
                  ),
                ],
              ),
            ],
          ),
        ),
      ],
    );
  }

  Widget _slider(String label, double min, double max, double value,
      ValueChanged<double> onChanged, VoidCallback apply) {
    return Row(
      children: [
        SizedBox(
          width: 68,
          child: Text(label, style: const TextStyle(fontSize: 12)),
        ),
        Expanded(
          child: Slider(
            min: min,
            max: max,
            value: value.clamp(min, max),
            onChanged: (v) => setState(() => onChanged(v)),
          ),
        ),
        SizedBox(
          width: 52,
          child: Text(
            value.toStringAsFixed(1),
            textAlign: TextAlign.right,
            style: const TextStyle(fontSize: 12),
          ),
        ),
        IconButton(
          tooltip: '发送',
          visualDensity: VisualDensity.compact,
          icon: const Icon(Icons.send_outlined, size: 18),
          onPressed: apply,
        ),
      ],
    );
  }
}
