import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:localsend_app/config/init.dart';
import 'package:localsend_app/config/init_error.dart';
import 'package:localsend_app/config/theme.dart';
import 'package:localsend_app/gen/strings.g.dart';
import 'package:localsend_app/model/persistence/color_mode.dart';
import 'package:localsend_app/pages/home_page.dart';
import 'package:localsend_app/provider/local_ip_provider.dart';
import 'package:localsend_app/provider/network/server/server_provider.dart';
import 'package:localsend_app/provider/settings_provider.dart';
import 'package:localsend_app/util/background_receive_service.dart';
import 'package:localsend_app/util/native/channel/android_channel.dart';
import 'package:localsend_app/util/native/platform_check.dart';
import 'package:localsend_app/util/ui/dynamic_colors.dart';
import 'package:localsend_app/widget/watcher/life_cycle_watcher.dart';
import 'package:localsend_app/widget/watcher/shortcut_watcher.dart';
import 'package:localsend_app/widget/watcher/tray_watcher.dart';
import 'package:localsend_app/widget/watcher/window_watcher.dart';
import 'package:localsend_isolates/isolate.dart';
import 'package:localsend_isolates/util/foreground_service.dart';
import 'package:refena_flutter/addons.dart';
import 'package:refena_flutter/refena_flutter.dart';
import 'package:routerino/routerino.dart';

Future<void> main(List<String> args) async {
  final RefenaContainer container;
  try {
    container = await preInit(args);
  } catch (e, stackTrace) {
    showInitErrorApp(
      error: e,
      stackTrace: stackTrace,
    );
    return;
  }

  if (checkPlatform([TargetPlatform.android])) {
    setAndroidEventHandlers(
      onScreenInteractive: () {
        container.redux(parentIsolateProvider).dispatch(IsolateDiscoveryRestartAction());
      },
      onReceiveNotificationAction: (sessionId, action) => container.notifier(serverProvider).handleReceiveNotificationAction(sessionId, action),
    );
    await configureReceiveNotifications();
  }

  runApp(
    RefenaScope.withContainer(
      container: container,
      child: TranslationProvider(
        child: const LocalSendApp(),
      ),
    ),
  );
}

class LocalSendApp extends StatelessWidget {
  const LocalSendApp();

  @override
  Widget build(BuildContext context) {
    final ref = context.ref;
    final (themeMode, colorMode, customColor) = ref.watch(
      settingsProvider.select((settings) => (settings.theme, settings.colorMode, settings.customColor)),
    );
    final dynamicColors = ref.watch(dynamicColorsProvider);
    return TrayWatcher(
      child: WindowWatcher(
        child: LifeCycleWatcher(
          onChangedState: (AppLifecycleState state) {
            switch (state) {
              case AppLifecycleState.resumed:
                ForegroundService.setAppInBackground(false);
                ref.redux(localIpProvider).dispatch(InitLocalIpAction());
                if (checkPlatform([TargetPlatform.iOS, TargetPlatform.android])) {
                  // The OS may have invalidated the sockets of the suspended app without any error ever reaching the accept loop.
                  unawaited(
                    ref.notifier(serverProvider).ensureRunning().then((_) {
                      if (checkPlatform([TargetPlatform.android]) &&
                          ref.read(settingsProvider).backgroundReceive &&
                          ref.read(serverProvider) != null) {
                        // Also repairs a service that the OS stopped while the app was away.
                        startBackgroundReceiveService();
                      }
                    }),
                  );
                }
                if (checkPlatform([TargetPlatform.iOS, TargetPlatform.android])) {
                  // The multicast sockets may die silently while the app is suspended, so always rebind them.
                  ref.redux(parentIsolateProvider).dispatch(IsolateDiscoveryRestartAction());
                }
                break;
              case AppLifecycleState.hidden:
              case AppLifecycleState.paused:
                ForegroundService.setAppInBackground(true);
                break;
              case AppLifecycleState.detached:
                ForegroundService.setAppInBackground(true);
                // Android can detach the Activity while the foreground service keeps the app
                // process alive. Disposing the Rust isolate here would leave a visible service
                // notification with no HTTP server behind it.
                final keepAndroidReceiver = checkPlatform([TargetPlatform.android]) && ref.read(settingsProvider).backgroundReceive;
                if (!keepAndroidReceiver) {
                  // The main isolate is only exited when all child isolates are exited.
                  // https://github.com/localsend/localsend/issues/1568
                  ref.redux(parentIsolateProvider).dispatch(IsolateDisposeAction());
                }
                break;
              default:
                break;
            }
          },
          child: ShortcutWatcher(
            child: MaterialApp(
              title: t.appName,
              locale: TranslationProvider.of(context).flutterLocale,
              supportedLocales: AppLocaleUtils.supportedLocales,
              localizationsDelegates: GlobalMaterialLocalizations.delegates,
              debugShowCheckedModeBanner: false,
              theme: getTheme(colorMode, customColor, Brightness.light, dynamicColors),
              darkTheme: getTheme(colorMode, customColor, Brightness.dark, dynamicColors),
              themeMode: colorMode == ColorMode.oled ? ThemeMode.dark : themeMode,
              navigatorKey: context.read(navigationProvider).key,
              home: RouterinoHome(
                builder: () => const HomePage(
                  initialTab: HomeTab.receive,
                  appStart: true,
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}
