import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:localsend_app/model/persistence/quick_save_mode.dart';
import 'package:localsend_app/model/state/server/server_state.dart';
import 'package:localsend_app/provider/network/server/controller/receive_controller.dart';
import 'package:localsend_app/provider/network/server/server_utils.dart';
import 'package:localsend_app/provider/persistence_provider.dart';
import 'package:localsend_isolates/isolate.dart';
import 'package:localsend_isolates/model/session_status.dart';
import 'package:localsend_isolates/rust/api/model.dart';
import 'package:localsend_isolates/rust/api/server.dart';
import 'package:localsend_isolates/util/foreground_service.dart';
import 'package:mockito/mockito.dart';
import 'package:refena_flutter/refena_flutter.dart';

import '../../mocks.mocks.dart';

class _Persistence extends MockPersistenceService {
  @override
  bool isBackgroundReceive() => true;
}

class _RecordingController extends ReceiveController {
  _RecordingController(super.server);
  final accepted = <Map<String, String>>[];
  int declined = 0;

  @override
  Future<void> acceptFileRequest(Map<String, String> files) async {
    accepted.add(files);
  }

  @override
  void declineFileRequest() {
    declined++;
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const native = MethodChannel('org.localsend.localsend_app/localsend');
  const paths = MethodChannel('plugins.flutter.io/path_provider');
  late RefenaContainer container;
  late _RecordingController controller;
  late ServerState? state;
  late Directory directory;
  late List<MethodCall> calls;
  bool notificationsEnabled = true;

  setUp(() async {
    debugDefaultTargetPlatformOverride = TargetPlatform.android;
    ForegroundService.setAppInBackground(true);
    directory = await Directory.systemTemp.createTemp('localsend-receive-test');
    final persistence = _Persistence();
    when(persistence.getDestination()).thenReturn(directory.path);
    // Background approval takes precedence even when Quick Save is enabled.
    when(persistence.getQuickSave()).thenReturn(QuickSaveMode.on);
    container = RefenaContainer(overrides: [persistenceProvider.overrideWithValue(persistence)]);
    state = const ServerState(alias: 'receiver', port: 53317, https: true, session: null, web: null);
    controller = _RecordingController(
      ServerUtils(
        refFunc: () => container,
        getState: () => state!,
        getStateOrNull: () => state,
        setState: (update) => state = update(state),
      ),
    );
    calls = [];
    notificationsEnabled = true;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(paths, (_) async => directory.path);
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(native, (call) async {
      calls.add(call);
      return call.method == 'showReceiveRequest' ? notificationsEnabled : null;
    });
  });

  tearDown(() async {
    container.disposeContainer();
    ForegroundService.setAppInBackground(false);
    debugDefaultTargetPlatformOverride = null;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(paths, null);
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(native, null);
    await directory.delete(recursive: true);
  });

  HttpServerPrepareUploadEvent request({String? message}) => HttpServerPrepareUploadEvent(
    sessionId: 'current',
    ip: '192.0.2.1',
    certFingerprint: 'sender',
    info: const RegisterDtoV2(alias: 'Sender', version: '2.1', fingerprint: 'sender', port: 53317, protocol: ProtocolType.https, download: false),
    files: {'file': FileDto(id: 'file', fileName: 'hello.txt', size: BigInt.from(5), fileType: 'text/plain', preview: message)},
  );

  test('background Quick Save waits for the notification decision and rejects stale actions', () async {
    await controller.onPrepareUpload(request());
    expect(state!.session!.status, SessionStatus.waiting);
    expect(controller.accepted, isEmpty);
    expect(calls.where((call) => call.method == 'showReceiveRequest'), hasLength(1));
    await controller.handleNotificationAction('old', 'accept');
    await controller.handleNotificationAction('old', 'ignore');
    expect(controller.accepted, isEmpty);
    expect(controller.declined, 0);
    await controller.handleNotificationAction('current', 'accept');
    expect(controller.accepted, [
      {'file': 'hello.txt'},
    ]);
  });

  test('ignore rejects a current request without accepting it', () async {
    await controller.onPrepareUpload(request());
    await controller.handleNotificationAction('current', 'ignore');
    expect(controller.declined, 1);
    expect(controller.accepted, isEmpty);
  });

  test('disabled notifications reject instead of silently auto-accepting', () async {
    notificationsEnabled = false;
    await controller.onPrepareUpload(request());
    expect(controller.declined, 1);
    expect(controller.accepted, isEmpty);
  });

  test('text previews wait for acceptance and complete without uploading a file', () async {
    await controller.onPrepareUpload(request(message: 'hello'));
    expect(controller.accepted, isEmpty);
    expect(calls.any((call) => call.method == 'showReceiveComplete'), isFalse);
    await controller.handleNotificationAction('current', 'accept');
    expect(controller.accepted, [<String, String>{}]);
  });
}
