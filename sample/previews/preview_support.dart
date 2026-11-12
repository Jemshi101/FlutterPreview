import 'package:flutter/material.dart';

/// Shared by the sample's previews: groups, sizes and a wrapper, the way a real project would organise them.
const String cardsGroup = 'Cards';
const String buttonsGroup = 'Buttons';
const String screensGroup = 'Screens';

/// A phone-sized frame for page previews.
const Size kPhoneSize = Size(390, 844);

/// Gives small widgets some breathing room.
Widget padded(Widget child) => Padding(padding: const EdgeInsets.all(16), child: child);
