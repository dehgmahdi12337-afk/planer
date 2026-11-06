import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_local_notifications/flutter_local_notifications.dart';
import 'package:path_provider/path_provider.dart';
import 'package:timezone/data/latest_all.dart' as tzdata;
import 'package:timezone/timezone.dart' as tz;
import 'package:webview_flutter/webview_flutter.dart';

const _native = MethodChannel('planner/native');
const _timerNotificationId = 2147483000;
final FlutterLocalNotificationsPlugin _notifications =
    FlutterLocalNotificationsPlugin();

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  tzdata.initializeTimeZones();
  tz.setLocalLocation(tz.getLocation('Etc/UTC'));
  await _notifications.initialize(
    const InitializationSettings(
      android: AndroidInitializationSettings('@mipmap/ic_launcher'),
    ),
  );
  runApp(const PlannerApp());
}

class PlannerApp extends StatelessWidget {
  const PlannerApp({super.key});

  @override
  Widget build(BuildContext context) => const MaterialApp(
        debugShowCheckedModeBanner: false,
        title: 'پلنر',
        home: PlannerWebView(),
      );
}

class PlannerWebView extends StatefulWidget {
  const PlannerWebView({super.key});

  @override
  State<PlannerWebView> createState() => _PlannerWebViewState();
}

class _PlannerWebViewState extends State<PlannerWebView>
    with WidgetsBindingObserver {
  late final WebViewController _web;
  String? _backupDirectory;
  DateTime? _lastBackup;
  bool _pageReady = false;
  bool _notificationPermission = false;
  Timer? _backupDebounce;
  String? _latestPayload;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _web = WebViewController()
      ..setJavaScriptMode(JavaScriptMode.unrestricted)
      ..setBackgroundColor(const Color(0x00000000))
      ..addJavaScriptChannel('Planner', onMessageReceived: _onHtmlMessage)
      ..setNavigationDelegate(NavigationDelegate(
        onPageFinished: (_) async {
          _pageReady = true;
          await _refreshBridgeState();
          await _consumeWidgetActions();
        },
      ))
      ..loadFlutterAsset('assets/planner.html');
    _initializeBackupLocation();
    _initializeNotifications();
  }

  Future<void> _initializeNotifications() async {
    final android = _notifications.resolvePlatformSpecificImplementation<
        AndroidFlutterLocalNotificationsPlugin>();
    _notificationPermission =
        await android?.areNotificationsEnabled() ?? false;
    if (mounted && _pageReady) await _refreshBridgeState();
  }

  Future<Directory> _defaultBackupDirectory() async {
    final base = await getApplicationDocumentsDirectory();
    final dir = Directory('${base.path}/PlannerBackups');
    if (!await dir.exists()) await dir.create(recursive: true);
    return dir;
  }

  Future<void> _initializeBackupLocation() async {
    final docs = await getApplicationDocumentsDirectory();
    final savedPath = File('${docs.path}/backup-location.txt');
    if (await savedPath.exists()) {
      final value = (await savedPath.readAsString()).trim();
      if (value.isNotEmpty && await Directory(value).exists()) {
        _backupDirectory = value;
      }
    }
    _backupDirectory ??= (await _defaultBackupDirectory()).path;
    await _loadLastBackupTime();
    if (_pageReady) await _refreshBridgeState();
  }

  Future<void> _loadLastBackupTime() async {
    final dirPath = _backupDirectory;
    if (dirPath == null) return;
    final dir = Directory(dirPath);
    if (!await dir.exists()) return;
    DateTime? newest;
    await for (final entity in dir.list()) {
      if (entity is File && entity.path.endsWith('.json')) {
        final modified = await entity.lastModified();
        if (newest == null || modified.isAfter(newest)) newest = modified;
      }
    }
    _lastBackup = newest;
  }

  Future<void> _refreshBridgeState() async {
    if (!_pageReady) return;
    final state = jsonEncode({
      'dir': _backupDirectory == null ? '' : 'پوشه پشتیبان پلنر',
      'last': _lastBackup?.millisecondsSinceEpoch,
    });
    await _web.runJavaScript('''
      try {
        if (window.onPerm) window.onPerm(${_notificationPermission ? 'true' : 'false'});
        if (window.onBk) window.onBk($state);
      } catch (_) {}
    ''');
  }

  Future<void> _onHtmlMessage(JavaScriptMessage message) async {
    try {
      final data = jsonDecode(message.message) as Map<String, dynamic>;
      switch (data['k']) {
        case 'bk':
          final payload = data['data'] as String?;
          if (payload != null) {
            _latestPayload = payload;
            _backupDebounce?.cancel();
            _backupDebounce = Timer(const Duration(seconds: 2), () {
              _writeBackup(payload, automatic: true);
            });
            await _syncWidget(payload);
            await _scheduleTaskReminders(payload);
          }
          break;
        case 'bknow':
          final payload = data['data'] as String?;
          if (payload != null) await _writeBackup(payload, automatic: false);
          break;
        case 'pickdir':
          await _chooseBackupDirectory();
          break;
        case 'restore':
          await _restoreBackup();
          break;
        case 'perm':
          await _requestNotificationPermission();
          break;
        case 'test':
          await _showNotification('پلنر', 'این یک اعلان آزمایشی است.');
          break;
        case 'timer':
          final at = (data['at'] as num?)?.toInt();
          if (at != null) {
            await _scheduleNotification(
              _timerNotificationId,
              'پلنر',
              data['title']?.toString() ?? 'زمان تمرکز تمام شد',
              DateTime.fromMillisecondsSinceEpoch(at),
            );
          }
          break;
        case 'tcancel':
          await _notifications.cancel(_timerNotificationId);
          break;
      }
    } catch (e) {
      debugPrint('Planner bridge error: $e');
    }
  }

  Future<void> _requestNotificationPermission() async {
    final android = _notifications.resolvePlatformSpecificImplementation<
        AndroidFlutterLocalNotificationsPlugin>();
    _notificationPermission =
        await android?.requestNotificationsPermission() ?? false;
    await _refreshBridgeState();
  }

  Future<void> _showNotification(String title, String body) async {
    await _notifications.show(
      900001,
      title,
      body,
      const NotificationDetails(
        android: AndroidNotificationDetails(
          'planner_reminders',
          'یادآورهای پلنر',
          channelDescription: 'یادآوری کارها و تمرکز',
          importance: Importance.max,
          priority: Priority.high,
        ),
      ),
    );
  }

  int _stableNotificationId(Object? id) {
    final value = id?.toString() ?? '';
    var hash = 0;
    for (final unit in value.codeUnits) {
      hash = (hash * 31 + unit) & 0x7fffffff;
    }
    return hash == 0 || hash == _timerNotificationId ? hash + 1 : hash;
  }

  Future<void> _scheduleNotification(
    int id,
    String title,
    String body,
    DateTime when,
  ) async {
    if (!when.isAfter(DateTime.now())) {
      await _notifications.cancel(id);
      return;
    }
    await _notifications.zonedSchedule(
      id,
      title,
      body,
      tz.TZDateTime.from(when, tz.local),
      const NotificationDetails(
        android: AndroidNotificationDetails(
          'planner_reminders',
          'یادآورهای پلنر',
          channelDescription: 'یادآوری کارها و تمرکز',
          importance: Importance.max,
          priority: Priority.high,
        ),
      ),
      androidScheduleMode: AndroidScheduleMode.inexactAllowWhileIdle,
      uiLocalNotificationDateInterpretation:
          UILocalNotificationDateInterpretation.absoluteTime,
    );
  }

  Future<void> _scheduleTaskReminders(String payload) async {
    final decoded = jsonDecode(payload) as Map<String, dynamic>;
    final tasks = (decoded['tasks'] as List? ?? const []);
    final activeIds = <int>{};
    for (final raw in tasks) {
      if (raw is! Map) continue;
      final id = _stableNotificationId(raw['id']);
      activeIds.add(id);
      final at = (raw['at'] as num?)?.toInt() ?? 0;
      final done = raw['done'] == true;
      final fired = raw['fired'] == true;
      if (!done && !fired && at > 0) {
        await _scheduleNotification(
          id,
          'یادآور پلنر',
          raw['title']?.toString() ?? 'زمان انجام کار رسیده است',
          DateTime.fromMillisecondsSinceEpoch(at),
        );
      } else {
        await _notifications.cancel(id);
      }
    }
  }

  Future<void> _writeBackup(String payload, {required bool automatic}) async {
    try {
      final dirPath = _backupDirectory ?? (await _defaultBackupDirectory()).path;
      final dir = Directory(dirPath);
      if (!await dir.exists()) await dir.create(recursive: true);
      final stamp = DateTime.now().toIso8601String().replaceAll(':', '-');
      final file = File('${dir.path}/planner-$stamp.json');
      await file.writeAsString(payload, flush: true);
      _lastBackup = DateTime.now();
      // Keep the most recent 20 backups to avoid unbounded growth.
      final files = <File>[];
      await for (final entity in dir.list()) {
        if (entity is File && entity.path.endsWith('.json')) files.add(entity);
      }
      files.sort((a, b) => b.path.compareTo(a.path));
      for (final old in files.skip(20)) {
        await old.delete();
      }
      await _refreshBridgeState();
    } catch (e) {
      debugPrint('Planner backup error: $e');
    }
  }

  Future<void> _chooseBackupDirectory() async {
    try {
      final selected = await FilePicker.getDirectoryPath();
      if (selected != null && selected.isNotEmpty) {
        _backupDirectory = selected;
        final docs = await getApplicationDocumentsDirectory();
        await File('${docs.path}/backup-location.txt').writeAsString(selected, flush: true);
        await _loadLastBackupTime();
        if (_latestPayload != null) {
          await _writeBackup(_latestPayload!, automatic: false);
        }
      }
    } catch (e) {
      debugPrint('Backup folder picker error: $e');
    }
    await _refreshBridgeState();
  }

  Future<void> _restoreBackup() async {
    try {
      final result = await FilePicker.pickFiles(
        type: FileType.custom,
        allowedExtensions: ['json'],
        withData: true,
      );
      if (result == null || result.files.isEmpty) return;
      final picked = result.files.single;
      String content;
      if (picked.bytes != null) {
        content = utf8.decode(picked.bytes!);
      } else if (picked.path != null) {
        content = await File(picked.path!).readAsString();
      } else {
        return;
      }
      final parsed = jsonDecode(content);
      if (parsed is! Map || parsed['tasks'] is! List) return;
      await _web.runJavaScript(
        'if (window.onRestore) window.onRestore(${jsonEncode(content)});',
      );
    } catch (e) {
      debugPrint('Planner restore error: $e');
    }
  }

  Future<void> _syncWidget(String payload) async {
    try {
      final decoded = jsonDecode(payload) as Map<String, dynamic>;
      final now = DateTime.now();
      final today = '${now.year.toString().padLeft(4, "0")}-${now.month.toString().padLeft(2, "0")}-${now.day.toString().padLeft(2, "0")}';
      final tasks = (decoded['tasks'] as List? ?? const [])
          .whereType<Map>()
          .where((task) => task['done'] != true && task['date'] == today)
          .map((task) => {
                'id': task['id']?.toString() ?? '',
                'title': task['title']?.toString() ?? '',
                'time': task['time']?.toString() ?? '',
                'date': task['date']?.toString() ?? '',
              })
          .toList();
      await _native.invokeMethod<void>('setTasks', jsonEncode(tasks));
    } catch (e) {
      debugPrint('Planner widget sync error: $e');
    }
  }

  Future<void> _consumeWidgetActions() async {
    try {
      final raw = await _native.invokeMethod<String>('takePending');
      final ids = (jsonDecode(raw ?? '[]') as List).map((e) => e.toString());
      for (final id in ids) {
        final safe = jsonEncode(id);
        await _web.runJavaScript('''
          (function(){
            const id = $safe;
            const input = [...document.querySelectorAll('input[data-id]')]
              .find((el) => el.dataset.id === id);
            if (input && !input.checked) input.click();
          })();
        ''');
      }
      final add = await _native.invokeMethod<bool>('takeAdd') ?? false;
      if (add) {
        await _web.runJavaScript('''
          (function(){
            const button = document.querySelector('#plus');
            if (button) button.click();
          })();
        ''');
      }
    } catch (e) {
      debugPrint('Planner widget action error: $e');
    }
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed && _pageReady) {
      _consumeWidgetActions();
      _refreshBridgeState();
    }
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _backupDebounce?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Scaffold(
        body: SafeArea(
          child: WebViewWidget(controller: _web),
        ),
      );
}
