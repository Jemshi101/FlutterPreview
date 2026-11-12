import 'package:flutter/material.dart';
import 'package:flutter/widget_previews.dart';
import 'package:flutter_preview_sample/screens/settings_page.dart';

import '../preview_support.dart';

@Preview(group: screensGroup, name: 'Settings page', size: kPhoneSize)
Widget settingsPagePreview() => const SettingsPage(name: 'Maya Patel', role: 'Product designer');

@Preview(group: screensGroup, name: 'Settings page - dark', size: kPhoneSize, brightness: Brightness.dark)
Widget settingsPageDarkPreview() => const SettingsPage(name: 'Maya Patel', role: 'Product designer');
