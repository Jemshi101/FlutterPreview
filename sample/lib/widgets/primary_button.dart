import 'package:flutter/material.dart';

/// Full-width primary action with an optional icon and a loading state.
class PrimaryButton extends StatelessWidget {
  const PrimaryButton({
    super.key,
    required this.label,
    required this.onPressed,
    this.icon,
    this.loading = false,
  });

  final String label;
  final VoidCallback? onPressed;
  final IconData? icon;
  final bool loading;

  @override
  Widget build(BuildContext context) {
    final Widget child = loading
        ? const SizedBox.square(dimension: 20, child: CircularProgressIndicator(strokeWidth: 2.5))
        : Row(
            mainAxisSize: MainAxisSize.min,
            children: <Widget>[
              if (icon != null) ...<Widget>[Icon(icon, size: 20), const SizedBox(width: 8)],
              Text(label),
            ],
          );
    return SizedBox(
      width: double.infinity,
      height: 52,
      child: FilledButton(onPressed: loading ? null : onPressed, child: child),
    );
  }
}
