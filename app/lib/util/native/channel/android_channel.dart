import 'dart:async';

import 'package:dart_mappable/dart_mappable.dart';
import 'package:flutter/services.dart';
import 'package:logging/logging.dart';

part 'android_channel.mapper.dart';

const _methodChannel = MethodChannel('org.localsend.localsend_app/localsend');
final _logger = Logger('AndroidSaf');

void setAndroidEventHandlers({
  required FutureOr<void> Function() onScreenInteractive,
  required Future<void> Function(String sessionId, String action) onReceiveNotificationAction,
}) {
  _methodChannel.setMethodCallHandler((call) async {
    switch (call.method) {
      case 'screenInteractive':
        await onScreenInteractive();
      case 'receiveNotificationAction':
        final arguments = (call.arguments as Map).cast<String, dynamic>();
        await onReceiveNotificationAction(arguments['sessionId'] as String, arguments['action'] as String);
      default:
        throw MissingPluginException('Unknown Android callback: ${call.method}');
    }
  });
}

Future<T?> receiveNotificationAndroid<T>(String method, Map<String, Object?> arguments) async {
  try {
    return await _methodChannel.invokeMethod<T>(method, arguments);
  } catch (e, st) {
    _logger.warning('Android notification operation failed: $method', e, st);
    return null;
  }
}

/// From Android 10 and above, we need to use the Storage Access Framework (SAF) to access files due to the scoped storage.
/// SAF itself is available from Android 4.4 (API level 19).
/// We implemented our own algorithm to build encode and decode content URIs.
/// Older versions might also work but the encoded content URI is not guaranteed to work with our algorithm.
const contentUriMinSdk = 27;

Future<PickDirectoryResult?> pickDirectoryAndroid() async {
  final result = await _methodChannel.invokeMethod<Map>('pickDirectory');
  if (result == null) {
    return null;
  }

  return PickDirectoryResultMapper.fromJson({
    'directoryUri': result['directoryUri'],
    'files': (result['files'] as List).map((e) => FileInfoMapper.fromJson((e as Map).cast<String, dynamic>())).toList(),
  });
}

Future<String?> pickDirectoryPathAndroid() async {
  final result = await _methodChannel.invokeMethod<String>('pickDirectoryPath');
  return result;
}

Future<List<FileInfo>?> pickFilesAndroid() async {
  final result = await _methodChannel.invokeMethod<List>('pickFiles');
  if (result == null) {
    return null;
  }

  return result.map((e) => FileInfoMapper.fromJson((e as Map).cast<String, dynamic>())).toList();
}

/// Returns the global "Download" directory, e.g. /storage/emulated/0/Download.
Future<String?> getDownloadsDirectoryAndroid() async {
  try {
    return await _methodChannel.invokeMethod<String>('getDownloadsDirectory');
  } catch (e) {
    _logger.warning('Could not get downloads directory', e);
    return null;
  }
}

Future<bool> getSystemAnimationsStatusAndroid() async {
  return await _methodChannel.invokeMethod('isAnimationsEnabled') ?? true;
}

/// Requests the local-network permission. Android 17+ uses ACCESS_LOCAL_NETWORK;
/// Android 13-16 use the nearby-devices permission. Returns true when granted or
/// when running on an older Android version.
Future<bool> requestLocalNetworkPermissionAndroid() async {
  try {
    return await _methodChannel.invokeMethod<bool>('requestLocalNetworkPermission') ?? false;
  } catch (e) {
    _logger.warning('Could not request local network permission', e);
    return false;
  }
}

/// Keeps Wi-Fi multicast discovery working while the screen is interactive. This lock is
/// independent from the HTTP server and foreground service.
void setLocalNetworkMulticastLockAndroid(bool enabled) {
  unawaited(
    _methodChannel.invokeMethod<void>('setLocalNetworkMulticastLock', {'enabled': enabled}).catchError((e) {
      _logger.warning('Could not update local network multicast lock', e);
    }),
  );
}

Future<void> openContentUri({
  required String uri,
}) async {
  _logger.info('Opening content URI: $uri');
  await _methodChannel.invokeMethod('openContentUri', {
    'uri': uri,
  });
}

/// Tells MainActivity that the Dart side is now subscribed to the share_handler media stream,
/// so share intents that were held back during app start can be replayed.
Future<void> flushPendingShareIntentsAndroid() async {
  try {
    await _methodChannel.invokeMethod('shareIntentReady');
  } catch (e) {
    _logger.warning('Could not flush pending share intents', e);
  }
}

Future<void> openGallery() async {
  _logger.info('Opening gallery');
  await _methodChannel.invokeMethod('openGallery');
}

@MappableClass()
class PickDirectoryResult with PickDirectoryResultMappable {
  final String directoryUri;
  final List<FileInfo> files;

  PickDirectoryResult({
    required this.directoryUri,
    required this.files,
  });
}

@MappableClass()
class FileInfo with FileInfoMappable {
  final String name;
  final int size;
  final String uri;

  /// RFC 3339 in UTC. Null when the document provider does not know it.
  final String? lastModified;

  FileInfo({
    required this.name,
    required this.size,
    required this.uri,
    required this.lastModified,
  });
}
