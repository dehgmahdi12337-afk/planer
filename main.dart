import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_local_notifications/flutter_local_notifications.dart';
import 'package:timezone/data/latest_all.dart' as tzd;
import 'package:timezone/timezone.dart' as tz;
import 'package:webview_flutter/webview_flutter.dart';

final np = FlutterLocalNotificationsPlugin();
const nat = MethodChannel('planner/native');

AndroidFlutterLocalNotificationsPlugin? get an => np
    .resolvePlatformSpecificImplementation<AndroidFlutterLocalNotificationsPlugin>();

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  tzd.initializeTimeZones();
  await np.initialize(const InitializationSettings(
      android: AndroidInitializationSettings('@mipmap/ic_launcher')));
  await an?.requestNotificationsPermission();
  runApp(const MaterialApp(debugShowCheckedModeBanner: false, home: Shell()));
}

class Shell extends StatefulWidget {
  const Shell({super.key});
  @override
  State<Shell> createState() => _ShellState();
}

class _ShellState extends State<Shell> with WidgetsBindingObserver {
  late final WebViewController c;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    c = WebViewController()
      ..setJavaScriptMode(JavaScriptMode.unrestricted)
      ..setBackgroundColor(const Color(0x00000000))
      ..enableZoom(false)
      ..setNavigationDelegate(NavigationDelegate(onPageFinished: (_) {
        status();
        pull();
      }))
      ..addJavaScriptChannel('Planner', onMessageReceived: (m) => onMsg(m.message));
    rootBundle
        .loadString('assets/planner.html')
        .then((h) => c.loadHtmlString(h, baseUrl: 'https://planner.app/'));
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState s) {
    if (s == AppLifecycleState.resumed) {
      status();
      pull();
    }
  }

  // وضعیت مجوز اعلان را به صفحه خبر می‌دهد
  Future<void> status() async {
    final ok = await an?.areNotificationsEnabled() ?? false;
    await c.runJavaScript('window.onPerm&&window.onPerm($ok)');
  }

  // کارهایی که از ویجت تیک خورده‌اند و درخواست «کار جدید» از ویجت
  Future<void> pull() async {
    try {
      final s = await nat.invokeMethod<String>('takePending') ?? '[]';
      if (s != '[]') await c.runJavaScript('window.applyNative&&window.applyNative($s)');
      if (await nat.invokeMethod<bool>('takeAdd') == true) {
        await c.runJavaScript('window.openAdd&&window.openAdd()');
      }
    } catch (_) {}
  }

  Future<void> onMsg(String raw) async {
    try {
      final m = jsonDecode(raw) as Map;
      if (m['k'] == 'perm') {
        await an?.requestNotificationsPermission();
        await status();
        return;
      }
      await nat.invokeMethod('setTasks', jsonEncode(m['w'] ?? []));
      await np.cancelAll();
      for (final t in (m['r'] ?? []) as List) {
        await remind(t);
      }
    } catch (_) {}
  }

  Future<void> remind(dynamic t) async {
    final at = DateTime.fromMillisecondsSinceEpoch((t['at'] as num).toInt());
    final id = (t['id'] as String).hashCode & 0x3fffffff;
    const d = NotificationDetails(
        android: AndroidNotificationDetails('planner', 'یادآور کارها',
            importance: Importance.max, priority: Priority.high));
    Future<void> go(AndroidScheduleMode mode) => np.zonedSchedule(
          id,
          'پلنر',
          'باید این کار رو انجام بدی: ${t['title']}',
          tz.TZDateTime.from(at, tz.UTC), // یک لحظهٔ مطلق؛ منطقهٔ زمانی مهم نیست
          d,
          androidScheduleMode: mode,
          uiLocalNotificationDateInterpretation:
              UILocalNotificationDateInterpretation.absoluteTime,
        );
    try {
      await go(AndroidScheduleMode.exactAllowWhileIdle);
    } catch (_) {
      await go(AndroidScheduleMode.inexactAllowWhileIdle);
    }
  }

  @override
  Widget build(BuildContext context) =>
      Scaffold(body: SafeArea(child: WebViewWidget(controller: c)));
}
