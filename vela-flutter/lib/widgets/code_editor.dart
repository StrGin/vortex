import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../theme/app_theme.dart';

/// 按扩展名猜语言，决定高亮规则。
String languageOf(String path) {
  final p = path.toLowerCase();
  final dot = p.lastIndexOf('.');
  final ext = dot < 0 ? '' : p.substring(dot + 1);
  switch (ext) {
    case 'js':
    case 'jsc':
    case 'mjs':
    case 'cjs':
      return 'js';
    case 'ts':
      return 'ts';
    case 'json':
      return 'json';
    case 'css':
    case 'less':
    case 'scss':
      return 'css';
    case 'html':
    case 'htm':
    case 'wxml':
    case 'ux':
      return 'html';
    case 'xml':
      return 'xml';
    case 'md':
      return 'md';
    default:
      return 'text';
  }
}

/// 极简语法高亮：一条合并正则 + 命名分组，一次扫完。
///
/// 不做 AST（那是 IDE 的活），只按词法着色 —— 对改 Vela 快应用的脚本足够，
/// 而且没有引入任何编辑器依赖（整包 APK 已经 478MB，能不加就不加）。
class CodeEditingController extends TextEditingController {
  CodeEditingController({super.text, String? language})
      : language = language ?? 'text',
        super();

  String language;

  static const _keywords = [
    'var', 'let', 'const', 'function', 'return', 'if', 'else', 'for', 'while',
    'do', 'switch', 'case', 'default', 'break', 'continue', 'new', 'delete',
    'typeof', 'instanceof', 'in', 'of', 'this', 'class', 'extends', 'super',
    'import', 'export', 'from', 'try', 'catch', 'finally', 'throw', 'async',
    'await', 'yield', 'null', 'undefined', 'true', 'false',
  ];

  static final _jsRule = _build(
    keywords: _keywords,
    lineComment: '//',
    blockComment: true,
  );

  static final _cssRule = _build(
    keywords: const ['important', 'media', 'import', 'keyframes', 'supports'],
    lineComment: null,
    blockComment: true,
    propertyLike: true,
  );

  static final _htmlRule = _build(
    keywords: const [],
    lineComment: null,
    blockComment: true,
    tagLike: true,
  );

  static RegExp _build({
    required List<String> keywords,
    String? lineComment,
    bool blockComment = false,
    bool propertyLike = false,
    bool tagLike = false,
  }) {
    final parts = <String>[];
    if (blockComment) parts.add(r'(?<block>/\*[\s\S]*?\*/)');
    if (lineComment != null) parts.add('(?<line>${RegExp.escape(lineComment)}[^\\n]*)');
    parts.add(r'''(?<str>'(?:[^'\\\n]|\\.)*'|"(?:[^"\\\n]|\\.)*"|`(?:[^`\\]|\\.)*`)''');
    if (tagLike) parts.add(r'(?<tag></?[A-Za-z][\w-]*)');
    if (propertyLike) parts.add(r'(?<prop>[-a-zA-Z]+)(?=\s*:)');
    if (keywords.isNotEmpty) {
      parts.add('(?<kw>\\b(?:${keywords.join('|')})\\b)');
    }
    parts.add(r'(?<num>\b\d+(?:\.\d+)?\b)');
    parts.add(r'(?<id>[A-Za-z_$][\w$]*)(?=\s*\()'); // 函数名
    return RegExp(parts.join('|'));
  }

  TextStyle _color(String what, ColorScheme scheme, bool dark) {
    switch (what) {
      case 'block':
      case 'line':
        return TextStyle(
            color: scheme.onSurfaceVariant.withValues(alpha: 0.7),
            fontStyle: FontStyle.italic);
      case 'str':
        return TextStyle(color: dark ? const Color(0xFF9ECE6A) : const Color(0xFF2E7D32));
      case 'kw':
        return TextStyle(
            color: dark ? const Color(0xFFBB9AF7) : const Color(0xFF7B1FA2),
            fontWeight: FontWeight.w600);
      case 'num':
        return TextStyle(color: dark ? const Color(0xFFFF9E64) : const Color(0xFFB26A00));
      case 'tag':
        return TextStyle(color: dark ? const Color(0xFF7AA2F7) : const Color(0xFF1565C0));
      case 'prop':
        return TextStyle(color: dark ? const Color(0xFF73DACA) : const Color(0xFF00695C));
      case 'id':
        return TextStyle(color: dark ? const Color(0xFFE0AF68) : const Color(0xFF8D6E00));
      default:
        return const TextStyle();
    }
  }

  RegExp get _rule {
    switch (language) {
      case 'js':
      case 'ts':
      case 'json':
        return _jsRule;
      case 'css':
        return _cssRule;
      case 'html':
      case 'xml':
        return _htmlRule;
      default:
        return _jsRule;
    }
  }

  @override
  TextSpan buildTextSpan({
    required BuildContext context,
    TextStyle? style,
    required bool withComposing,
  }) {
    final theme = Theme.of(context);
    final dark = theme.brightness == Brightness.dark;
    final base = style ?? theme.textTheme.bodySmall ?? const TextStyle();
    if (language == 'text' || language == 'md') {
      return TextSpan(style: base, text: text);
    }
    final scheme = theme.colorScheme;
    final spans = <TextSpan>[];
    var last = 0;
    for (final m in _rule.allMatches(text)) {
      if (m.start > last) {
        spans.add(TextSpan(text: text.substring(last, m.start), style: base));
      }
      final name = m.groupNames.firstWhere(
        (g) => m.namedGroup(g) != null,
        orElse: () => '',
      );
      spans.add(TextSpan(
        text: m.group(0),
        style: base.merge(_color(name, scheme, dark)),
      ));
      last = m.end;
    }
    if (last < text.length) {
      spans.add(TextSpan(text: text.substring(last), style: base));
    }
    return TextSpan(style: base, children: spans);
  }
}

/// 带行号、Tab 缩进、自动缩进与 Ln/Col 的代码编辑器（纯 Flutter，无第三方依赖）。
class CodeEditor extends StatefulWidget {
  const CodeEditor({
    super.key,
    required this.controller,
    required this.path,
    this.onSave,
    this.onChanged,
  });

  final CodeEditingController controller;
  final String path;
  final VoidCallback? onSave;

  /// 内容变化（已含自动缩进）——调用方用它标记"未保存"。
  final VoidCallback? onChanged;

  @override
  State<CodeEditor> createState() => _CodeEditorState();
}

class _CodeEditorState extends State<CodeEditor> {
  final ScrollController _scroll = ScrollController();
  static const _lineHeight = 18.0;

  @override
  void initState() {
    super.initState();
    widget.controller.addListener(_onChanged);
    _scroll.addListener(_onScroll);
  }

  @override
  void didUpdateWidget(CodeEditor old) {
    super.didUpdateWidget(old);
    if (old.controller != widget.controller) {
      old.controller.removeListener(_onChanged);
      widget.controller.addListener(_onChanged);
    }
    if (old.path != widget.path) {
      widget.controller.language = languageOf(widget.path);
    }
  }

  @override
  void dispose() {
    widget.controller.removeListener(_onChanged);
    _scroll.dispose();
    super.dispose();
  }

  void _onChanged() {
    if (mounted) setState(() {});
  }

  void _onScroll() {
    if (mounted) setState(() {});
  }

  int get _lineCount => '\n'.allMatches(widget.controller.text).length + 1;

  (int, int) get _caret {
    final sel = widget.controller.selection;
    final pos = sel.isValid ? sel.baseOffset : 0;
    final before = widget.controller.text.substring(0, pos.clamp(0, widget.controller.text.length));
    final line = '\n'.allMatches(before).length + 1;
    final col = before.length - before.lastIndexOf('\n');
    return (line, col);
  }

  /// Tab / Shift+Tab：插或删两个空格（快应用源码就是两空格缩进）。
  KeyEventResult _onKey(FocusNode node, KeyEvent e) {
    if (e is! KeyDownEvent) return KeyEventResult.ignored;
    final c = widget.controller;
    if (e.logicalKey == LogicalKeyboardKey.tab) {
      final sel = c.selection;
      if (!sel.isValid) return KeyEventResult.handled;
      final add = !HardwareKeyboard.instance.isShiftPressed;
      if (add) {
        final t = c.text;
        c.text = t.substring(0, sel.start) + '  ' + t.substring(sel.end);
        c.selection = TextSelection.collapsed(offset: sel.start + 2);
      } else {
        final t = c.text;
        final lineStart = t.lastIndexOf('\n', sel.start - 1) + 1;
        final head = t.substring(lineStart, sel.start);
        final drop = head.endsWith('  ') ? 2 : (head.endsWith(' ') ? 1 : 0);
        if (drop > 0) {
          c.text = t.substring(0, sel.start - drop) + t.substring(sel.start);
          c.selection = TextSelection.collapsed(offset: sel.start - drop);
        }
      }
      return KeyEventResult.handled;
    }
    if ((e.logicalKey == LogicalKeyboardKey.keyS) &&
        (HardwareKeyboard.instance.isControlPressed ||
            HardwareKeyboard.instance.isMetaPressed)) {
      widget.onSave?.call();
      return KeyEventResult.handled;
    }
    return KeyEventResult.ignored;
  }

  /// 回车时沿用上一行的缩进（写 JSON/CSS 时最省事的一招）。
  void _autoIndent(String value) {
    if (!value.endsWith('\n')) return;
    final c = widget.controller;
    final lines = c.text.split('\n');
    // 光标所在行的前一行
    final sel = c.selection;
    final idx = sel.isValid ? sel.baseOffset : c.text.length;
    final before = c.text.substring(0, idx);
    final lineIdx = '\n'.allMatches(before).length;
    if (lineIdx <= 0 || lineIdx > lines.length) return;
    final prev = lines[lineIdx - 1];
    final indent = RegExp(r'^\s*').firstMatch(prev)?.group(0) ?? '';
    if (indent.isEmpty) return;
    c.text = c.text.substring(0, idx) + indent + c.text.substring(idx);
    c.selection = TextSelection.collapsed(offset: idx + indent.length);
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final (line, col) = _caret;
    final editorStyle = TextStyle(
      fontFamily: 'monospace',
      fontSize: 13,
      height: _lineHeight / 13,
      color: scheme.onSurface,
    );
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Expanded(
          child: Container(
            decoration: BoxDecoration(
              color: scheme.surfaceContainerHighest,
              borderRadius: Radii.fieldR,
            ),
            clipBehavior: Clip.antiAlias,
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                // 行号槽：跟着 TextField 的滚动偏移一起平移（同一行高，天然对齐）。
                SizedBox(
                  width: 44,
                  child: ClipRect(
                    child: Transform.translate(
                      offset: Offset(0, -(_scroll.hasClients ? _scroll.offset : 0.0)),
                      child: Padding(
                        padding: const EdgeInsets.fromLTRB(0, Insets.sm, Insets.sm, 0),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.end,
                          children: [
                            for (var i = 1; i <= _lineCount; i++)
                              SizedBox(
                                height: _lineHeight,
                                child: Text(
                                  '$i',
                                  style: editorStyle.copyWith(
                                    color: i == line
                                        ? scheme.primary
                                        : scheme.onSurfaceVariant
                                            .withValues(alpha: 0.55),
                                  ),
                                ),
                              ),
                          ],
                        ),
                      ),
                    ),
                  ),
                ),
                VerticalDivider(width: 1, color: scheme.outlineVariant),
                Expanded(
                  child: Focus(
                    onKeyEvent: _onKey,
                    child: TextField(
                      controller: widget.controller,
                      scrollController: _scroll,
                      maxLines: null,
                      expands: true,
                      style: editorStyle,
                      textAlignVertical: TextAlignVertical.top,
                      keyboardType: TextInputType.multiline,
                      decoration: const InputDecoration(
                        border: InputBorder.none,
                        isDense: true,
                        contentPadding: EdgeInsets.all(Insets.sm),
                      ),
                      onChanged: (v) {
                        _autoIndent(v);
                        widget.onChanged?.call();
                        setState(() {});
                      },
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),
        Padding(
          padding: const EdgeInsets.fromLTRB(Insets.sm, Insets.xs, Insets.sm, 0),
          child: Row(
            children: [
              Text(
                'Ln $line, Col $col',
                style: Theme.of(context).textTheme.labelSmall?.copyWith(
                      color: scheme.onSurfaceVariant,
                    ),
              ),
              const Spacer(),
              Text(
                languageOf(widget.path).toUpperCase(),
                style: Theme.of(context).textTheme.labelSmall?.copyWith(
                      color: scheme.onSurfaceVariant,
                    ),
              ),
            ],
          ),
        ),
      ],
    );
  }
}
