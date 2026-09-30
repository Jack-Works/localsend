import 'package:flutter/foundation.dart';
import 'package:flutter_foreground_task/flutter_foreground_task.dart';
import 'package:localsend_isolates/util/future_queue.dart';
import 'package:logging/logging.dart';

final _logger = Logger('ForegroundService');

// Use new IDs so installs that already created the old LOW channel receive the new settings.
const _backgroundChannelId = 'localsend_background_service_v3';
const _transferChannelId = 'localsend_transfer_service_v2';
const _serviceId = 1;

/// Notifications cannot sensibly be redrawn as often as transfer progress arrives.
const _updateInterval = Duration(milliseconds: 500);

enum _NotificationMode { background, transfer }

/// An Android foreground service that keeps the app process alive.
class ForegroundService {
  ForegroundService._();

  static const _fallbackChannelName = 'LocalSend';

  /// Start, update and stop must not overlap, otherwise they would read a [_running] flag that
  /// a still pending action is about to change.
  static final _queue = FutureQueue(
    onError: (e, st) => _logger.warning('Foreground service operation failed', e, st),
  );

  static _NotificationMode? _initializedMode;
  static bool _running = false;
  static _NotificationMode? _runningMode;
  static List<int>? _runningServiceTypes;
  static bool _persistent = false;
  static bool _transferRunning = false;
  static bool _appInBackground = false;
  static DateTime? _lastUpdate;
  static String? _lastTitle;
  static String? _lastText;
  static String? _baseTitle;
  static String? _baseText;
  static String? _persistentTitle;
  static String? _persistentText;
  static String? _persistentChannelName;
  static String? _transferChannelName;

  /// The service exists on Android only. On iOS the app keeps running in the background anyway,
  /// and on desktop there is nothing to keep alive.
  static bool get _isSupported => defaultTargetPlatform == TargetPlatform.android;

  /// Whether the service is currently keeping the process alive.
  static bool get isRunning => _running;

  /// Whether the Android activity is currently in the background.
  ///
  /// The app owns this bit because the receive controller must make the same decision as the
  /// UI without depending on a widget or an activity being present.
  static bool get isAppInBackground => _appInBackground;

  static void setAppInBackground(bool value) {
    _appInBackground = value;
  }

  /// Whether [updateNotification] would actually forward an update right now.
  /// Lets callers skip building a text that gets throttled away anyway.
  static bool get shouldUpdateNotification {
    if (!_isSupported || !_running) {
      return false;
    }
    final lastUpdate = _lastUpdate;
    return lastUpdate == null || DateTime.now().difference(lastUpdate) >= _updateInterval;
  }

  /// Starts the service and shows the notification. Does nothing if it is already running.
  ///
  /// [channelName] is shown in the Android notification settings and is only read the first time
  /// the service starts, because the notification channel is created once per app installation.
  static void start({
    required String channelName,
    required String title,
    required String text,
  }) {
    if (!_isSupported) {
      return;
    }

    _transferRunning = true;
    _transferChannelName = channelName;
    _baseTitle = title;
    _baseText = text;
    _ensureRunning(channelName: channelName);
  }

  /// Keeps the LocalSend listener alive after the activity is sent to the background.
  ///
  /// This method is intentionally separate from [start]. The latter is used for a user-initiated
  /// transfer and is allowed to end when that transfer ends; this method represents the explicit
  /// opt-in setting that keeps receiving available in the background.
  static void startPersistent({
    required String channelName,
    required String title,
    required String text,
  }) {
    if (!_isSupported) {
      return;
    }

    _persistent = true;
    _persistentChannelName = channelName;
    _persistentTitle = title;
    _persistentText = text;
    if (!_transferRunning) {
      _baseTitle = title;
      _baseText = text;
    }
    _ensureRunning(channelName: channelName);
  }

  /// Disables background receiving and removes the persistent service when no transfer is active.
  static void stopPersistent() {
    if (!_isSupported) {
      return;
    }

    _persistent = false;
    _persistentChannelName = null;
    _persistentTitle = null;
    _persistentText = null;
    _queue.add(() async {
      if (_transferRunning) {
        _ensureRunning(channelName: _transferChannelName ?? _fallbackChannelName);
      } else {
        await _stopIfIdle();
      }
    });
  }

  /// Updates the service notification.
  /// Throttled to [_updateInterval]; calls before the service is running are dropped.
  ///
  /// The [title] can change while the service runs, because what the service is keeping alive
  /// may change without it ever stopping.
  static void updateNotification({required String title, required String text}) {
    if (!_isSupported) {
      return;
    }

    final now = DateTime.now();
    final lastUpdate = _lastUpdate;
    if (lastUpdate != null && now.difference(lastUpdate) < _updateInterval) {
      return;
    }
    _lastUpdate = now;

    _baseTitle = title;
    _baseText = text;
    _enqueueRender();
  }

  /// Tells the service that a transfer has ended. The service remains alive when background
  /// receiving is enabled.
  static void stop() {
    if (!_isSupported) {
      return;
    }

    _transferRunning = false;
    _transferChannelName = null;
    _lastUpdate = null;
    _lastTitle = null;
    _lastText = null;
    if (_persistent) {
      _baseTitle = _persistentTitle;
      _baseText = _persistentText;
    }
    _queue.add(() async {
      if (_persistent) {
        _ensureRunning(channelName: _persistentChannelName ?? _fallbackChannelName);
      } else {
        await _stopIfIdle();
      }
    });
  }

  static void _enqueueRender() {
    final title = _persistent ? _persistentTitle : _baseTitle;
    final text = _persistent ? _persistentText : _baseText;
    if (title == null || text == null || (title == _lastTitle && text == _lastText)) {
      return;
    }
    _lastTitle = title;
    _lastText = text;

    _queue.add(() async {
      await _renderNotification();
    });
  }

  static void _ensureRunning({required String channelName}) {
    _lastUpdate = null;
    _lastTitle = null;
    _lastText = null;
    _queue.add(() async {
      if (!_persistent && !_transferRunning) {
        return;
      }

      final mode = _persistent ? _NotificationMode.background : _NotificationMode.transfer;
      final serviceTypes = _serviceTypes;
      final serviceTypeValues = serviceTypes.map((type) => type.rawValue).toList(growable: false);
      _init(channelName: _persistent ? (_persistentChannelName ?? channelName) : channelName, mode: mode);

      // The channel importance and foreground-service type are fixed when Android starts the
      // service. Restart the same service when switching between idle background receiving and
      // an active transfer so the notification and Android's FGS contract match the work.
      if (await FlutterForegroundTask.isRunningService && (_runningMode != mode || !listEquals(_runningServiceTypes, serviceTypeValues))) {
        _running = false;
        final stopResult = await FlutterForegroundTask.stopService();
        if (stopResult is ServiceRequestFailure) {
          _logger.warning('Could not restart the foreground service', stopResult.error);
          return;
        }
        _runningMode = null;
        _runningServiceTypes = null;
      }

      if (await FlutterForegroundTask.isRunningService) {
        _running = true;
        _runningMode = mode;
        _runningServiceTypes = serviceTypeValues;
        await _renderNotification();
        return;
      }

      await _requestNotificationPermission();
      final title = _persistent ? _persistentTitle : _baseTitle;
      final text = _persistent ? _persistentText : _baseText;
      if (title == null || text == null) {
        return;
      }

      final result = await FlutterForegroundTask.startService(
        serviceId: _serviceId,
        serviceTypes: serviceTypes,
        notificationTitle: title,
        notificationText: text,
        notificationInitialRoute: '/',
        callback: foregroundServiceStartCallback,
      );

      if (result is ServiceRequestFailure) {
        // Android 12+ rejects a start from the background. The setting is only enabled while the
        // activity is visible, so a failure here is unexpected but must not crash the app.
        _logger.warning('Could not start the foreground service', result.error);
        return;
      }

      _running = true;
      _runningMode = mode;
      _runningServiceTypes = serviceTypeValues;
    });
  }

  static List<ForegroundServiceTypes> get _serviceTypes {
    // A persistent LAN listener is an interaction with an external network device, while an
    // isolated transfer uses dataSync. connectedDevice has no Android 15 six-hour dataSync cap.
    return [
      if (_persistent) ForegroundServiceTypes.connectedDevice,
      if (!_persistent && _transferRunning) ForegroundServiceTypes.dataSync,
    ];
  }

  static Future<void> _renderNotification() async {
    if (!_running) {
      return;
    }

    final title = _persistent ? _persistentTitle : _baseTitle;
    final text = _persistent ? _persistentText : _baseText;
    if (title == null || text == null) {
      return;
    }

    _lastTitle = title;
    _lastText = text;
    final result = await FlutterForegroundTask.updateService(
      notificationTitle: title,
      notificationText: text,
    );
    if (result is ServiceRequestFailure) {
      _logger.warning('Could not update the foreground service', result.error);
    }
  }

  static Future<void> _stopIfIdle() async {
    if (_persistent || _transferRunning) {
      return;
    }

    if (!_running && !await FlutterForegroundTask.isRunningService) {
      return;
    }

    _running = false;
    _runningMode = null;
    _runningServiceTypes = null;
    final result = await FlutterForegroundTask.stopService();
    if (result is ServiceRequestFailure) {
      _logger.warning('Could not stop the foreground service', result.error);
    }
  }

  static void _init({required String channelName, required _NotificationMode mode}) {
    if (_initializedMode == mode) {
      return;
    }
    _initializedMode = mode;

    final isTransfer = mode == _NotificationMode.transfer;

    FlutterForegroundTask.init(
      androidNotificationOptions: AndroidNotificationOptions(
        channelId: isTransfer ? _transferChannelId : _backgroundChannelId,
        channelName: isTransfer ? '$channelName (transfer)' : channelName,
        // MIN keeps the persistent listener out of the normal status bar on Android versions
        // that honor channel importance. Android still requires an FGS notification and some
        // OEMs may show a service indicator regardless.
        channelImportance: isTransfer ? NotificationChannelImportance.HIGH : NotificationChannelImportance.MIN,
        priority: isTransfer ? NotificationPriority.HIGH : NotificationPriority.MIN,
        onlyAlertOnce: true,
      ),
      iosNotificationOptions: const IOSNotificationOptions(
        showNotification: false,
        playSound: false,
      ),
      foregroundTaskOptions: ForegroundTaskOptions(
        // The work happens in the other isolates, so the service has nothing to do on its own.
        eventAction: ForegroundTaskEventAction.nothing(),
        autoRunOnBoot: false,
        autoRunOnMyPackageReplaced: false,
        allowWakeLock: true,
        allowWifiLock: true,
      ),
    );
  }

  /// Android 13+ needs this permission to show the notification.
  /// The service itself runs either way, so a missing permission is not treated as an error.
  ///
  /// Must not overlap with another permission request: Android cancels the pending dialog and
  /// reports an empty result, which the plugin surfaces as a `PermissionRequestCancelledException`.
  static Future<void> _requestNotificationPermission() async {
    try {
      // Only ask while the user has not decided yet. Once permanently denied, the permission can
      // only be changed in the system settings and asking again silently resolves to denied.
      if (await FlutterForegroundTask.checkNotificationPermission() == NotificationPermission.denied) {
        await FlutterForegroundTask.requestNotificationPermission();
      }
    } catch (e) {
      _logger.warning('Could not request the notification permission', e);
    }
  }
}

/// Entry point required by flutter_foreground_task. The service itself has no task work; its
/// notification is deliberately kept separate from received-message notifications.
@pragma('vm:entry-point')
void foregroundServiceStartCallback() {
  FlutterForegroundTask.setTaskHandler(_ForegroundServiceTaskHandler());
}

class _ForegroundServiceTaskHandler extends TaskHandler {
  @override
  Future<void> onStart(DateTime timestamp, TaskStarter starter) async {}

  @override
  void onRepeatEvent(DateTime timestamp) {}

  @override
  Future<void> onDestroy(DateTime timestamp, bool isTimeout) async {}
}
