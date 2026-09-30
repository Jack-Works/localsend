import 'dart:async';

import 'package:localsend_app/gen/strings.g.dart';
import 'package:localsend_app/model/state/server/receive_session_state.dart';
import 'package:localsend_app/util/native/channel/android_channel.dart';
import 'package:localsend_isolates/model/file_type.dart';
import 'package:localsend_isolates/util/file_size_helper.dart';
import 'package:localsend_isolates/util/foreground_service.dart';

void startBackgroundReceiveService() {
  setLocalNetworkMulticastLockAndroid(true);
  ForegroundService.startPersistent(
    channelName: t.notifications.backgroundService,
    title: t.appName,
    text: t.settingsTab.receive.runInBackground,
  );
}

void stopBackgroundReceiveService() {
  setLocalNetworkMulticastLockAndroid(false);
  ForegroundService.stopPersistent();
}

Future<void> configureReceiveNotifications() async {
  await receiveNotificationAndroid<void>('configureReceiveNotifications', {
    'requests': t.notifications.requests,
    'progress': t.notifications.progress,
    'results': t.notifications.results,
  });
}

Future<bool> showBackgroundReceiveRequest(ReceiveSessionState session) async {
  return await receiveNotificationAndroid<bool>('showReceiveRequest', {
        'sessionId': session.sessionId,
        'title': '${t.receiveTab.title} · ${session.senderAlias}',
        'text':
            session.message ?? '${session.files.length} ${t.general.files}\n${session.files.values.take(3).map((f) => f.file.fileName).join('\n')}',
        'accept': t.general.accept,
        'ignore': t.notifications.ignore,
      }) ??
      false;
}

void showBackgroundReceiveProgress(ReceiveSessionState session, int current, int total, {bool first = false}) {
  final percent = total == 0 ? 0 : (current * 100 ~/ total).clamp(0, 100);
  unawaited(
    receiveNotificationAndroid<void>('showReceiveProgress', {
      'sessionId': session.sessionId,
      'title': '${t.progressPage.titleReceiving} · ${session.senderAlias}',
      'text': '$percent% (${current.asReadableFileSize} / ${total.asReadableFileSize})',
      'percent': percent,
      'first': first,
    }),
  );
}

void cancelBackgroundReceiveNotification(String sessionId) {
  unawaited(receiveNotificationAndroid<void>('cancelReceiveNotification', {'sessionId': sessionId}));
}

Future<void> showBackgroundReceiveComplete({
  required String key,
  required String sender,
  required String name,
  required FileType type,
  String? path,
  String? message,
  bool failed = false,
}) async {
  await receiveNotificationAndroid<void>('showReceiveComplete', {
    'key': key,
    'title': '${failed ? t.general.error : t.general.finished} · $sender',
    'text': name,
    'type': type.name,
    'path': path,
    'message': message,
    'open': t.general.open,
    'copy': t.general.copy,
  });
}
