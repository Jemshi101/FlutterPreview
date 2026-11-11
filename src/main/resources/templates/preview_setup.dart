// Flutter Preview plugin: local setup that runs once before previews render.
//
// This file is only used by the Flutter Preview plugin. It isn't part of your app or your tests, and it is
// gitignored (`/.flutter_preview/`), so it stays on your machine.
//
// Use it for things your widgets expect the app to have done at startup, for example:
//   * initialise singletons (localization, service locators, config)
//   * mock a platform channel that must return a real value
//
// Native plugin calls that nothing answers are already stubbed with an empty success reply, so you only need
// mocks for calls whose result matters.

// import 'package:flutter/services.dart';
// import 'package:flutter_test/flutter_test.dart';

Future<void> flutterPreviewSetup() async {
  // Example: initialise a singleton.
  // MyLocalization().init(const <String, String>{});

  // Example: mock a method channel.
  // TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
  //   const MethodChannel('my_app/settings'),
  //   (MethodCall call) async => call.method == 'getUnits' ? 'metric' : null,
  // );
}
