import 'package:flutter/material.dart';

/// A metric with its change since the last period.
class StatTile extends StatelessWidget {
  const StatTile({
    super.key,
    required this.label,
    required this.value,
    required this.changePercent,
  });

  final String label;
  final String value;
  final double changePercent;

  @override
  Widget build(BuildContext context) {
    final ThemeData theme = Theme.of(context);
    final bool up = changePercent >= 0;
    final Color trend = up ? Colors.green.shade600 : theme.colorScheme.error;
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisSize: MainAxisSize.min,
          children: <Widget>[
            Text(label, style: theme.textTheme.labelLarge?.copyWith(color: theme.colorScheme.onSurfaceVariant)),
            const SizedBox(height: 8),
            Text(value, style: theme.textTheme.headlineMedium?.copyWith(fontWeight: FontWeight.w600)),
            const SizedBox(height: 4),
            Row(
              children: <Widget>[
                Icon(up ? Icons.trending_up : Icons.trending_down, color: trend, size: 18),
                const SizedBox(width: 4),
                Text('${up ? '+' : ''}${changePercent.toStringAsFixed(1)}%', style: TextStyle(color: trend)),
              ],
            ),
          ],
        ),
      ),
    );
  }
}
