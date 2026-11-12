import 'package:flutter/material.dart';
import 'package:flutter/widget_previews.dart';
import 'package:flutter_preview_sample/screens/settings_page.dart';
import 'package:flutter_preview_sample/widgets/primary_button.dart';
import 'package:flutter_preview_sample/widgets/profile_card.dart';
import 'package:flutter_preview_sample/widgets/stat_tile.dart';

/// Compositions of the sample widgets, rendered by the plugin, for the Marketplace page.
const String showcaseGroup = 'Showcase';

/// Light, dark and large-text versions of the same widgets side by side.
@Preview(group: showcaseGroup, name: 'Variants', size: Size(1200, double.infinity))
Widget variantsShowcase() {
  return ColoredBox(
    color: const Color(0xFFF4F5F8),
    child: Padding(
      padding: const EdgeInsets.all(24),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          Expanded(child: _variant('Light', ThemeData(brightness: Brightness.light), 1.0)),
          const SizedBox(width: 24),
          Expanded(child: _variant('Dark', ThemeData(brightness: Brightness.dark), 1.0)),
          const SizedBox(width: 24),
          Expanded(child: _variant('Text scale 1.3', ThemeData(brightness: Brightness.light), 1.3)),
        ],
      ),
    ),
  );
}

/// A full page next to the components it is made of.
@Preview(group: showcaseGroup, name: 'Page and components', size: Size(1200, double.infinity))
Widget pageShowcase() {
  return ColoredBox(
    color: const Color(0xFFF4F5F8),
    child: Padding(
      padding: const EdgeInsets.all(28),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          _framed('Settings page', const SizedBox(width: 390, height: 600, child: SettingsPage(name: 'Maya Patel', role: 'Product designer'))),
          const SizedBox(width: 32),
          Expanded(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: <Widget>[
                _framed('Profile card', const Padding(
                  padding: EdgeInsets.all(12),
                  child: ProfileCard(name: 'Maya Patel', role: 'Product designer', online: true),
                )),
                const SizedBox(height: 24),
                Row(
                  children: <Widget>[
                    Expanded(child: _framed('Stat tile', const Padding(
                      padding: EdgeInsets.all(12),
                      child: StatTile(label: 'Active users', value: '12,480', changePercent: 8.4),
                    ))),
                    const SizedBox(width: 24),
                    Expanded(child: _framed('Stat tile · down', const Padding(
                      padding: EdgeInsets.all(12),
                      child: StatTile(label: 'Churn', value: '2.1%', changePercent: -0.6),
                    ))),
                  ],
                ),
                const SizedBox(height: 24),
                _framed('Button', Padding(
                  padding: const EdgeInsets.all(16),
                  child: PrimaryButton(label: 'Continue', icon: Icons.arrow_forward, onPressed: () {}),
                )),
                const SizedBox(height: 24),
                _framed('Button · disabled', const Padding(
                  padding: EdgeInsets.all(16),
                  child: PrimaryButton(label: 'Continue', onPressed: null),
                )),
              ],
            ),
          ),
        ],
      ),
    ),
  );
}

Widget _variant(String label, ThemeData theme, double textScale) {
  return _framed(
    label,
    Theme(
      data: theme,
      child: Builder(
        builder: (BuildContext context) => MediaQuery(
          data: MediaQuery.of(context).copyWith(textScaler: TextScaler.linear(textScale)),
          child: Material(
            color: theme.colorScheme.surface,
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: <Widget>[
                  const ProfileCard(name: 'Maya Patel', role: 'Product designer', online: true),
                  const SizedBox(height: 16),
                  PrimaryButton(label: 'Continue', icon: Icons.arrow_forward, onPressed: () {}),
                ],
              ),
            ),
          ),
        ),
      ),
    ),
  );
}

Widget _framed(String label, Widget child) {
  return Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    mainAxisSize: MainAxisSize.min,
    children: <Widget>[
      Padding(
        padding: const EdgeInsets.only(left: 4, bottom: 8),
        child: Text(label, style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w600, color: Color(0xFF494C57))),
      ),
      DecoratedBox(
        decoration: BoxDecoration(
          color: Colors.white,
          borderRadius: BorderRadius.circular(14),
          border: Border.all(color: const Color(0xFFDFE1E5)),
          boxShadow: const <BoxShadow>[BoxShadow(color: Color(0x14000000), blurRadius: 12, offset: Offset(0, 4))],
        ),
        child: ClipRRect(borderRadius: BorderRadius.circular(13), child: child),
      ),
    ],
  );
}
