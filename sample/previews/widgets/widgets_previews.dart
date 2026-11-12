import 'package:flutter/material.dart';
import 'package:flutter/widget_previews.dart';
import 'package:flutter_preview_sample/widgets/confirm_dialog.dart';
import 'package:flutter_preview_sample/widgets/primary_button.dart';
import 'package:flutter_preview_sample/widgets/profile_card.dart';
import 'package:flutter_preview_sample/widgets/settings_tile.dart';
import 'package:flutter_preview_sample/widgets/stat_tile.dart';

import '../preview_support.dart';

@Preview(group: cardsGroup, name: 'Profile card', wrapper: padded)
Widget profileCardPreview() => const ProfileCard(name: 'Maya Patel', role: 'Product designer', online: true);

@Preview(group: cardsGroup, name: 'Profile card - dark', wrapper: padded, brightness: Brightness.dark)
Widget profileCardDarkPreview() => const ProfileCard(name: 'Maya Patel', role: 'Product designer', online: true);

@Preview(group: cardsGroup, name: 'Profile card - large text', wrapper: padded, textScaleFactor: 1.5)
Widget profileCardLargeTextPreview() => const ProfileCard(name: 'Maya Patel', role: 'Product designer');

@Preview(group: cardsGroup, name: 'Stat tile - up', size: Size(260, 170), wrapper: padded)
Widget statTileUpPreview() => const StatTile(label: 'Active users', value: '12,480', changePercent: 8.4);

@Preview(group: cardsGroup, name: 'Stat tile - down', size: Size(260, 170), wrapper: padded)
Widget statTileDownPreview() => const StatTile(label: 'Churn', value: '2.1%', changePercent: -0.6);

/// Too little height on purpose: shows how layout problems appear under the image.
@Preview(group: cardsGroup, name: 'Stat tile - too short (overflow)', size: Size(260, 90), wrapper: padded)
Widget statTileOverflowPreview() => const StatTile(label: 'Active users', value: '12,480', changePercent: 8.4);

@Preview(group: buttonsGroup, name: 'Button', wrapper: padded)
Widget buttonPreview() => PrimaryButton(label: 'Continue', icon: Icons.arrow_forward, onPressed: () {});

@Preview(group: buttonsGroup, name: 'Button - loading', wrapper: padded)
Widget buttonLoadingPreview() => PrimaryButton(label: 'Continue', loading: true, onPressed: () {});

@Preview(group: buttonsGroup, name: 'Button - disabled', wrapper: padded)
Widget buttonDisabledPreview() => const PrimaryButton(label: 'Continue', onPressed: null);

@Preview(group: cardsGroup, name: 'Settings tile', wrapper: padded)
Widget settingsTilePreview() => SettingsTile(
      icon: Icons.notifications_outlined,
      title: 'Push notifications',
      subtitle: 'Mentions and direct messages',
      value: true,
      onChanged: (_) {},
    );

@Preview(group: cardsGroup, name: 'Confirm dialog', size: Size(400, 330))
Widget confirmDialogPreview() => ConfirmDialog(
      title: 'Delete project?',
      message: 'This removes the project and its 24 files for everyone. This can\'t be undone.',
      confirmLabel: 'Delete',
      onConfirm: () {},
      onCancel: () {},
    );
