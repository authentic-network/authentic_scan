import 'dart:async';

import 'package:flutter/services.dart';

class AuthenticScan {
  static const MethodChannel _channel =
      const MethodChannel('authentic_scan');

  static Future<String> get platformVersion async {
    final String version = await _channel.invokeMethod('getPlatformVersion');
    return version;
  }
}
