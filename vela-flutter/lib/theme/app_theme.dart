import 'package:flutter/material.dart';

/// Vela design tokens.
///
/// Screens pull from these instead of inventing numbers, so spacing and radii stay
/// consistent as screens are added. Ported from the sibling SimpSim app, which is
/// where the look of this UI comes from.
class Insets {
  const Insets._();

  /// 4dp base grid. Every gap in the app is one of these.
  static const double xs = 4;
  static const double sm = 8;
  static const double md = 12;
  static const double lg = 16;
  static const double xl = 20;
  static const double xxl = 24;
  static const double xxxl = 32;

  /// Standard screen padding.
  static const EdgeInsets screen = EdgeInsets.symmetric(
    horizontal: lg,
    vertical: md,
  );

  /// Padding inside a card.
  static const EdgeInsets card = EdgeInsets.all(lg);
}

class Radii {
  const Radii._();

  static const double chip = 8;
  static const double field = 12;
  static const double card = 16;
  static const double button = 20;
  static const double pill = 999;
  static const double sheet = 28;

  static const BorderRadius chipR = BorderRadius.all(Radius.circular(chip));
  static const BorderRadius fieldR = BorderRadius.all(Radius.circular(field));
  static const BorderRadius cardR = BorderRadius.all(Radius.circular(card));
  static const BorderRadius buttonR = BorderRadius.all(Radius.circular(button));
  static const BorderRadius sheetR = BorderRadius.vertical(
    top: Radius.circular(sheet),
  );
}

/// Shared motion vocabulary, so nothing feels bolted on.
class Motion {
  const Motion._();

  static const Duration fast = Duration(milliseconds: 120);
  static const Duration base = Duration(milliseconds: 220);
  static const Duration slow = Duration(milliseconds: 320);

  static const Curve emphasized = Curves.easeOutCubic;
  static const Curve standard = Curves.easeInOutCubic;
}

/// Builds the app theme from a [ColorScheme].
///
/// On Android 12+ the caller seeds the scheme with the real system accent palette so
/// the app matches the user's wallpaper.
ThemeData buildVelaTheme(ColorScheme scheme) {
  final base = ThemeData(useMaterial3: true, colorScheme: scheme);

  return base.copyWith(
    scaffoldBackgroundColor: scheme.surface,
    canvasColor: scheme.surface,
    splashFactory: InkSparkle.splashFactory,

    // Flat app bar: the shell draws its own large title, and a tinted bar on top of a
    // tinted surface is what makes stacked screens look unlayered.
    appBarTheme: AppBarTheme(
      backgroundColor: scheme.surface,
      surfaceTintColor: Colors.transparent,
      elevation: 0,
      scrolledUnderElevation: 0,
      centerTitle: false,
      titleTextStyle: base.textTheme.titleLarge?.copyWith(
        color: scheme.onSurface,
        fontWeight: FontWeight.w600,
      ),
      iconTheme: IconThemeData(color: scheme.onSurfaceVariant),
    ),

    navigationBarTheme: NavigationBarThemeData(
      backgroundColor: scheme.surfaceContainer,
      surfaceTintColor: Colors.transparent,
      elevation: 0,
      height: 72,
      labelBehavior: NavigationDestinationLabelBehavior.alwaysShow,
      indicatorColor: scheme.secondaryContainer,
      indicatorShape: const StadiumBorder(),
      labelTextStyle: WidgetStateProperty.resolveWith((states) {
        final selected = states.contains(WidgetState.selected);
        return base.textTheme.labelMedium?.copyWith(
          color: selected ? scheme.onSurface : scheme.onSurfaceVariant,
          fontWeight: selected ? FontWeight.w600 : FontWeight.w500,
        );
      }),
      iconTheme: WidgetStateProperty.resolveWith((states) {
        final selected = states.contains(WidgetState.selected);
        return IconThemeData(
          size: 24,
          color:
              selected ? scheme.onSecondaryContainer : scheme.onSurfaceVariant,
        );
      }),
    ),

    cardTheme: CardThemeData(
      color: scheme.surfaceContainer,
      surfaceTintColor: Colors.transparent,
      elevation: 0,
      margin: EdgeInsets.zero,
      shape: const RoundedRectangleBorder(borderRadius: Radii.cardR),
    ),

    filledButtonTheme: FilledButtonThemeData(
      style: FilledButton.styleFrom(
        minimumSize: const Size(64, 44),
        padding: const EdgeInsets.symmetric(horizontal: Insets.xl),
        textStyle: base.textTheme.labelLarge?.copyWith(
          fontWeight: FontWeight.w600,
        ),
        shape: const RoundedRectangleBorder(borderRadius: Radii.buttonR),
      ),
    ),

    outlinedButtonTheme: OutlinedButtonThemeData(
      style: OutlinedButton.styleFrom(
        minimumSize: const Size(56, 44),
        padding: const EdgeInsets.symmetric(horizontal: Insets.lg),
        foregroundColor: scheme.onSurface,
        side: BorderSide(color: scheme.outlineVariant),
        shape: const RoundedRectangleBorder(borderRadius: Radii.buttonR),
      ),
    ),

    textButtonTheme: TextButtonThemeData(
      style: TextButton.styleFrom(
        minimumSize: const Size(48, 40),
        shape: const RoundedRectangleBorder(borderRadius: Radii.buttonR),
      ),
    ),

    inputDecorationTheme: InputDecorationTheme(
      filled: true,
      fillColor: scheme.surfaceContainerHigh,
      hintStyle: base.textTheme.bodyMedium?.copyWith(
        color: scheme.onSurfaceVariant,
      ),
      contentPadding: const EdgeInsets.symmetric(
        horizontal: Insets.lg,
        vertical: Insets.md,
      ),
      border: const OutlineInputBorder(
        borderRadius: Radii.fieldR,
        borderSide: BorderSide.none,
      ),
      enabledBorder: const OutlineInputBorder(
        borderRadius: Radii.fieldR,
        borderSide: BorderSide.none,
      ),
      focusedBorder: OutlineInputBorder(
        borderRadius: Radii.fieldR,
        borderSide: BorderSide(color: scheme.primary, width: 2),
      ),
    ),

    dividerTheme: DividerThemeData(
      color: scheme.outlineVariant,
      thickness: 1,
      space: 1,
    ),

    listTileTheme: ListTileThemeData(
      shape: const RoundedRectangleBorder(borderRadius: Radii.fieldR),
      iconColor: scheme.onSurfaceVariant,
    ),

    chipTheme: ChipThemeData(
      backgroundColor: scheme.surfaceContainerHigh,
      side: BorderSide.none,
      shape: const RoundedRectangleBorder(borderRadius: Radii.chipR),
      labelStyle: base.textTheme.labelMedium,
    ),

    snackBarTheme: SnackBarThemeData(
      behavior: SnackBarBehavior.floating,
      backgroundColor: scheme.inverseSurface,
      contentTextStyle: base.textTheme.bodyMedium?.copyWith(
        color: scheme.onInverseSurface,
      ),
      shape: const RoundedRectangleBorder(borderRadius: Radii.fieldR),
    ),

    bottomSheetTheme: BottomSheetThemeData(
      backgroundColor: scheme.surfaceContainerLow,
      surfaceTintColor: Colors.transparent,
      shape: const RoundedRectangleBorder(borderRadius: Radii.sheetR),
      showDragHandle: true,
    ),

    progressIndicatorTheme: ProgressIndicatorThemeData(
      color: scheme.primary,
      linearTrackColor: scheme.surfaceContainerHighest,
    ),

    expansionTileTheme: ExpansionTileThemeData(
      shape: const RoundedRectangleBorder(borderRadius: Radii.fieldR),
      collapsedShape: const RoundedRectangleBorder(borderRadius: Radii.fieldR),
      tilePadding: const EdgeInsets.symmetric(horizontal: Insets.md),
    ),

    dialogTheme: DialogThemeData(
      backgroundColor: scheme.surfaceContainerHigh,
      surfaceTintColor: Colors.transparent,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.all(Radius.circular(Insets.xxl)),
      ),
    ),

    sliderTheme: SliderThemeData(
      trackHeight: 4,
      activeTrackColor: scheme.primary,
      inactiveTrackColor: scheme.surfaceContainerHighest,
      thumbColor: scheme.primary,
    ),

    switchTheme: SwitchThemeData(
      thumbColor: WidgetStateProperty.resolveWith((states) => states
              .contains(WidgetState.selected)
          ? scheme.onPrimary
          : scheme.outline),
      trackColor: WidgetStateProperty.resolveWith((states) => states
              .contains(WidgetState.selected)
          ? scheme.primary
          : scheme.surfaceContainerHighest),
      trackOutlineColor: const WidgetStatePropertyAll(Colors.transparent),
    ),
  );
}
