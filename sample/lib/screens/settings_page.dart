import 'package:flutter/material.dart';

import '../widgets/primary_button.dart';
import '../widgets/profile_card.dart';
import '../widgets/settings_tile.dart';

/// Account settings: profile, notification switches and a save button.
class SettingsPage extends StatefulWidget {
  const SettingsPage({super.key, required this.name, required this.role});

  final String name;
  final String role;

  @override
  State<SettingsPage> createState() => _SettingsPageState();
}

class _SettingsPageState extends State<SettingsPage> {
  bool _push = true;
  bool _email = false;
  bool _weekly = true;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Settings')),
      body: ListView(
        padding: const EdgeInsets.symmetric(vertical: 8),
        children: <Widget>[
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 12),
            child: ProfileCard(name: widget.name, role: widget.role, online: true),
          ),
          const Padding(
            padding: EdgeInsets.fromLTRB(16, 24, 16, 8),
            child: Text('Notifications', style: TextStyle(fontWeight: FontWeight.w600)),
          ),
          SettingsTile(
            icon: Icons.notifications_outlined,
            title: 'Push notifications',
            subtitle: 'Mentions and direct messages',
            value: _push,
            onChanged: (bool v) => setState(() => _push = v),
          ),
          SettingsTile(
            icon: Icons.mail_outline,
            title: 'Email',
            value: _email,
            onChanged: (bool v) => setState(() => _email = v),
          ),
          SettingsTile(
            icon: Icons.insights_outlined,
            title: 'Weekly summary',
            subtitle: 'Every Monday morning',
            value: _weekly,
            onChanged: (bool v) => setState(() => _weekly = v),
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 32, 16, 16),
            child: PrimaryButton(label: 'Save changes', icon: Icons.check, onPressed: () {}),
          ),
        ],
      ),
    );
  }
}
