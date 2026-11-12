import 'package:flutter/material.dart';

/// Asks the user to confirm a destructive action.
class ConfirmDialog extends StatelessWidget {
  const ConfirmDialog({
    super.key,
    required this.title,
    required this.message,
    required this.confirmLabel,
    required this.onConfirm,
    required this.onCancel,
  });

  final String title;
  final String message;
  final String confirmLabel;
  final VoidCallback onConfirm;
  final VoidCallback onCancel;

  @override
  Widget build(BuildContext context) {
    final ColorScheme colors = Theme.of(context).colorScheme;
    return AlertDialog(
      icon: Icon(Icons.warning_amber_rounded, color: colors.error, size: 32),
      title: Text(title),
      content: Text(message),
      actions: <Widget>[
        TextButton(onPressed: onCancel, child: const Text('Cancel')),
        FilledButton(
          style: FilledButton.styleFrom(backgroundColor: colors.error, foregroundColor: colors.onError),
          onPressed: onConfirm,
          child: Text(confirmLabel),
        ),
      ],
    );
  }
}
