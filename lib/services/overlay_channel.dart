import 'package:flutter/services.dart';

class OverlayStatus {
  const OverlayStatus({
    required this.overlayPermission,
    required this.overlayRunning,
  });

  final bool overlayPermission;
  final bool overlayRunning;

  static const OverlayStatus empty =
      OverlayStatus(overlayPermission: false, overlayRunning: false);

  factory OverlayStatus.fromMap(Map<dynamic, dynamic> map) {
    return OverlayStatus(
      overlayPermission: map['overlayPermission'] == true,
      overlayRunning: map['overlayRunning'] == true,
    );
  }
}

class OverlayChannel {
  OverlayChannel._();

  static const MethodChannel _channel = MethodChannel('mcheck/overlay');

  static Future<OverlayStatus> status() async {
    try {
      final map = await _channel.invokeMapMethod<String, dynamic>('status');
      if (map == null) return OverlayStatus.empty;
      return OverlayStatus.fromMap(map);
    } on MissingPluginException {
      return OverlayStatus.empty;
    }
  }

  static Future<void> requestOverlayPermission() async {
    await _channel.invokeMethod<void>('requestOverlayPermission');
  }

  static Future<bool> startOverlay() async {
    final result = await _channel.invokeMethod<bool>('startOverlay');
    return result == true;
  }

  static Future<void> stopOverlay() async {
    await _channel.invokeMethod<void>('stopOverlay');
  }
}
