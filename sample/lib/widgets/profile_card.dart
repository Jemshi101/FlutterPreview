import 'package:flutter/material.dart';

/// A person with their role and online status.
class ProfileCard extends StatelessWidget {
  const ProfileCard({
    super.key,
    required this.name,
    required this.role,
    this.online = false,
    this.onMessage,
  });

  final String name;
  final String role;
  final bool online;
  final VoidCallback? onMessage;

  @override
  Widget build(BuildContext context) {
    final ColorScheme colors = Theme.of(context).colorScheme;
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Row(
          children: <Widget>[
            Stack(
              children: <Widget>[
                CircleAvatar(
                  radius: 26,
                  backgroundColor: colors.primaryContainer,
                  child: Text(
                    name.isEmpty ? '?' : name[0],
                    style: TextStyle(fontSize: 22, color: colors.onPrimaryContainer, fontWeight: FontWeight.w600),
                  ),
                ),
                if (online)
                  Positioned(
                    right: 0,
                    bottom: 0,
                    child: Container(
                      width: 14,
                      height: 14,
                      decoration: BoxDecoration(
                        color: Colors.green,
                        shape: BoxShape.circle,
                        border: Border.all(color: colors.surface, width: 2),
                      ),
                    ),
                  ),
              ],
            ),
            const SizedBox(width: 16),
            Expanded(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: <Widget>[
                  Text(name, style: Theme.of(context).textTheme.titleMedium),
                  const SizedBox(height: 2),
                  Text(role, style: Theme.of(context).textTheme.bodyMedium?.copyWith(color: colors.onSurfaceVariant)),
                ],
              ),
            ),
            IconButton.filledTonal(onPressed: onMessage, icon: const Icon(Icons.chat_bubble_outline)),
          ],
        ),
      ),
    );
  }
}
