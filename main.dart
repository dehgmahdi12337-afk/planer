import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_local_notifications/flutter_local_notifications.dart';
import 'package:timezone/data/latest_all.dart' as tzd;
import 'package:timezone/timezone.dart' as tz;
import 'package:webview_flutter/webview_flutter.dart';

final np = FlutterLocalNotificationsPlugin();

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  tzd.initializeTimeZones();
  await np.initialize(const InitializationSettings(
      android: AndroidInitializationSettings('@mipmap/ic_launcher')));
  await np
      .resolvePlatformSpecificImplementation<
          AndroidFlutterLocalNotificationsPlugin>()
      ?.requestNotificationsPermission();
  runApp(const MaterialApp(debugShowCheckedModeBanner: false, home: Shell()));
}

class Shell extends StatefulWidget {
  const Shell({super.key});
  @override
  State<Shell> createState() => _ShellState();
}

class _ShellState extends State<Shell> {
  late final WebViewController c;

  @override
  void initState() {
    super.initState();
    c = WebViewController()
      ..setJavaScriptMode(JavaScriptMode.unrestricted)
      ..setBackgroundColor(const Color(0x00000000))
      // صفحه لیست کارهای آینده را به‌صورت JSON می‌فرستد و ما اعلان‌ها را زمان‌بندی می‌کنیم
      ..addJavaScriptChannel('Planner', onMessageReceived: (m) => sync(m.message));
    rootBundle
        .loadString('assets/planner.html')
        .then((h) => c.loadHtmlString(h, baseUrl: 'https://planner.app/'));
  }

  Future<void> sync(String s) async {
    try {
      await np.cancelAll();
      for (final t in jsonDecode(s) as List) {
        final at = DateTime.fromMillisecondsSinceEpoch((t['at'] as num).toInt());
        await np.zonedSchedule(
          (t['id'] as String).hashCode & 0x3fffffff,
          'یادآور',
          t['title'] as String,
          tz.TZDateTime.from(at, tz.UTC), // یک لحظهٔ مطلق؛ منطقهٔ زمانی مهم نیست
          const NotificationDetails(
              android: AndroidNotificationDetails('planner', 'یادآور',
                  importance: Importance.max, priority: Priority.high)),
          androidScheduleMode: AndroidScheduleMode.inexactAllowWhileIdle,
          uiLocalNotificationDateInterpretation:
              UILocalNotificationDateInterpretation.absoluteTime,
        );
      }
    } catch (_) {}
  }

  @override
  Widget build(BuildContext context) =>
      Scaffold(body: SafeArea(child: WebViewWidget(controller: c)));
}
