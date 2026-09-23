# Vortex

在 Android 手机上运行 **Vela（小米手表 / 手环）模拟器**的应用。

Vortex 把小米 AIoT IDE 里的模拟器引擎搬到手机本地跑：引擎、QEMU、Node 运行时都在设备私有目录里执行，界面是原生 Flutter，取帧走 gRPC，不依赖 Shizuku、root 或调试授权。

> An Android app that runs Xiaomi's Vela (MiWear) smartwatch emulator on-device. Flutter UI + native host, gRPC frame streaming, no root / Shizuku required.

---

## 目录

```
vela-flutter/       Flutter 应用（UI + Dart 侧状态机 + Android 宿主）
  lib/              Dart：界面、gRPC 客户端、本地控制接口、工程编辑器
  android/app/src/main/java/com/velasim/app/
                    Java 宿主：引擎生命周期、负载解包、AVD、装机、工具链
  android/app/src/main/jniLibs/arm64-v8a/
                    两个 native 件：glibc loader 与 exec stub（见下）
tools/              构建期脚本：打包引擎/工具链负载、编译 exec stub、部署
icon-proposals/     图标设计源文件（SVG + 生成脚本）
```

## 功能

- **引擎生命周期**：启动 / 停止 / 重启，串口日志与引擎日志实时回传
- **画面与输入**：gRPC 流式取帧，支持点击、滑动、长按、按键；表盘尺寸随实际帧自适应
- **传感器 / 电池 / 定位**：心率、步数、温度、电量与充电状态、经纬度注入，含城市预设
- **剪贴板**：双向（推送到手表 / 从手表取回）
- **镜像管理**：下载、导入本地 zip、删除；虚拟设备（AVD）创建与配置
- **工程开发**：创建 / 导入工程、带语法高亮与行号的编辑器、构建、推送安装、应用管理
- **连拍导出**：一次抓 8 帧存到相册目录
- **本地控制接口**：`127.0.0.1` 上的 HTTP + MCP 形状接口，可让脚本或 AI 点表盘、截图、跑构建（默认关闭，开启后仍需 token）

## 构建

前置：Flutter 3.x、JDK 17、Android SDK（compileSdk 34+）。`minSdk 26`，只出 arm64-v8a。

```bash
cd vela-flutter
flutter pub get
flutter build apk --release --target-platform android-arm64
```

### 运行时负载（不在本仓库）

引擎、QEMU、Node 运行时、aiot-toolkit 是**第三方二进制**，体积约 500MB，且版权不属本项目，因此不放进 git。请从 [Releases](../../releases) 下载 `vela-assets.tar`，解到：

```
vela-flutter/android/app/src/main/assets/vela/
```

解压后该目录应包含 `engine/`、`node/`、`glibc/`、`skins/`、`templates/`、`jsc.tar`、`toolkit.tar`、`devices.json`。缺了它 APK 能构建，但引擎起不来。

系统镜像（`*.zip` / `*.img`）同样不随仓库分发，在应用的「设备」页下载或自行导入。

## 为什么需要 native 件

Android 的 `untrusted_app` 域禁止在私有目录 `execve`，但允许 `mmap(PROT_EXEC)`。所以：

1. 只有两个小文件进 `jniLibs`——glibc loader 与一个静态 `exec stub`（`tools/native/vela-exec-stub.c`，`ET_EXEC`、无 `PT_INTERP`，可在应用域直接执行）；
2. 引擎按相对路径调用的 `qemu/*/bin64/*` 换成指向 stub 的软链接，由 stub 用 loader 拉起真正的负载；
3. 负载本体留在私有目录，用 `dlopen` / loader 加载，不需要任何提权。

`tools/build-exec-stub.sh` 负责编译并校验 stub 的 ELF 属性。

## 许可

本项目代码以 **AGPL-3.0** 发布，见 [LICENSE](LICENSE)。

仓库**不包含**任何第三方二进制或系统镜像。模拟器引擎、QEMU、Node.js（nodejs-mobile）、aiot-toolkit、系统镜像的版权归各自权利人所有，Release 中的负载包仅供自行取用，使用时请遵守其原始许可。Vela、小米、MiWear 等名称与商标归小米公司所有，本项目与之无隶属关系。
