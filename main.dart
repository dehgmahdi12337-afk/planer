import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter_local_notifications/flutter_local_notifications.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:timezone/data/latest_all.dart' as tzd;
import 'package:timezone/timezone.dart' as tz;

final np = FlutterLocalNotificationsPlugin();

String two(int n) => n.toString().padLeft(2, '0');
String ymd(DateTime d) => '${d.year}-${two(d.month)}-${two(d.day)}';
String fa(Object n) => n
    .toString()
    .replaceAllMapped(RegExp(r'\d'), (m) => '۰۱۲۳۴۵۶۷۸۹'[int.parse(m[0]!)]);

String fmtDur(int m) {
  if (m < 60) return fa('$m') + ' دقیقه';
  final h = m ~/ 60, r = m % 60;
  return r == 0
      ? fa('$h') + ' ساعت'
      : fa('$h') + ' ساعت و ' + fa('$r') + ' دقیقه';
}

class Task {
  int id, dur; // dur = مدت‌زمان کار به دقیقه (۰ یعنی تعیین نشده)
  String title, date;
  String? time;
  bool done, missed;
  Task(this.id, this.title, this.date, this.time,
      {this.done = false, this.missed = false, this.dur = 0});
  Map toJson() => {
        'id': id,
        't': title,
        'd': date,
        'h': time,
        'done': done,
        'm': missed,
        'u': dur
      };
  factory Task.fromJson(Map j) => Task(j['id'], j['t'], j['d'], j['h'],
      done: j['done'], missed: j['m'], dur: j['u'] ?? 0);
  DateTime? get at {
    if (time == null) return null;
    final p = time!.split(':'), d = DateTime.parse(date);
    return DateTime(d.year, d.month, d.day, int.parse(p[0]), int.parse(p[1]));
  }
}

Future<void> sched(int id, String title, String body, DateTime when) =>
    np.zonedSchedule(
      id,
      title,
      body,
      tz.TZDateTime.from(when, tz.UTC), // یک لحظهٔ مطلق؛ منطقهٔ زمانی مهم نیست
      const NotificationDetails(
          android: AndroidNotificationDetails('planner', 'یادآور',
              importance: Importance.max, priority: Priority.high)),
      androidScheduleMode: AndroidScheduleMode.inexactAllowWhileIdle,
      uiLocalNotificationDateInterpretation:
          UILocalNotificationDateInterpretation.absoluteTime,
    );

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  tzd.initializeTimeZones();
  await np.initialize(const InitializationSettings(
      android: AndroidInitializationSettings('@mipmap/ic_launcher')));
  await np
      .resolvePlatformSpecificImplementation<
          AndroidFlutterLocalNotificationsPlugin>()
      ?.requestNotificationsPermission();
  runApp(MaterialApp(
    debugShowCheckedModeBanner: false,
    title: 'پلنر',
    theme: ThemeData(useMaterial3: true, colorSchemeSeed: const Color(0xFF4F6DF5)),
    builder: (c, w) => Directionality(textDirection: TextDirection.rtl, child: w!),
    home: const Home(),
  ));
}

class Home extends StatefulWidget {
  const Home({super.key});
  @override
  State<Home> createState() => _HomeState();
}

class _HomeState extends State<Home> with WidgetsBindingObserver {
  List<Task> tasks = [];

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    load();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState s) {
    if (s == AppLifecycleState.resumed) settle();
  }

  Future<void> load() async {
    final p = await SharedPreferences.getInstance();
    final raw = p.getString('tasks');
    if (raw != null) {
      tasks = (jsonDecode(raw) as List).map((e) => Task.fromJson(e as Map)).toList();
    }
    await settle();
  }

  // هر کار انجام‌نشدهٔ روزهای قبل = «از دست رفته» (۱− نمره) و دیگر به امروز نمی‌آید
  Future<void> settle() async {
    final today = ymd(DateTime.now());
    for (final t in tasks) {
      if (t.date.compareTo(today) < 0 && !t.done) t.missed = true;
    }
    await save();
  }

  Future<void> save() async {
    final p = await SharedPreferences.getInstance();
    await p.setString('tasks', jsonEncode(tasks));
    if (mounted) setState(() {});
    night();
  }

  // هشدار ساعت ۲۱ اگر امروز کار انجام‌نشده داری
  Future<void> night() async {
    await np.cancel(0);
    final now = DateTime.now(), today = ymd(now);
    final n = tasks.where((t) => t.date == today && !t.done).length;
    final t9 = DateTime(now.year, now.month, now.day, 21);
    if (n > 0 && t9.isAfter(now)) {
      await sched(0, 'ساعت ۲۱ شد', '${fa(n)} کار انجام‌نشده داری؛ هرکدام ۱− نمره', t9);
    }
  }

  void remind(Task t) {
    final a = t.at;
    if (!t.done && a != null && a.isAfter(DateTime.now())) {
      sched(t.id, 'یادآور', t.title, a);
    }
  }

  void toggle(Task t) {
    t.done = !t.done;
    t.done ? np.cancel(t.id) : remind(t);
    save();
  }

  void del(Task t) {
    np.cancel(t.id);
    tasks.remove(t);
    save();
  }

  void add() {
    final c = TextEditingController();
    bool tom = false;
    int dur = 0;
    TimeOfDay? tm;
    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      builder: (_) => StatefulBuilder(
        builder: (ctx, set) => Padding(
          padding: EdgeInsets.fromLTRB(16, 16, 16, 16 + MediaQuery.of(ctx).viewInsets.bottom),
          child: Column(mainAxisSize: MainAxisSize.min, children: [
            TextField(controller: c, autofocus: true, decoration: const InputDecoration(hintText: 'کار جدید…')),
            const SizedBox(height: 8),
            Row(children: [
              ChoiceChip(label: const Text('امروز'), selected: !tom, onSelected: (_) => set(() => tom = false)),
              const SizedBox(width: 8),
              ChoiceChip(label: const Text('فردا'), selected: tom, onSelected: (_) => set(() => tom = true)),
              const Spacer(),
              TextButton(
                onPressed: () async {
                  final r = await showTimePicker(context: ctx, initialTime: TimeOfDay.now());
                  if (r != null) set(() => tm = r);
                },
                child: Text(tm == null ? '⏰ ساعت' : fa('${two(tm!.hour)}:${two(tm!.minute)}')),
              ),
            ]),
            const SizedBox(height: 4),
            Align(
              alignment: AlignmentDirectional.centerStart,
              child: Wrap(spacing: 6, children: [
                for (final m in [0, 15, 30, 45, 60, 90, 120])
                  ChoiceChip(
                      label: Text(m == 0 ? 'بدون زمان' : fmtDur(m)),
                      selected: dur == m,
                      onSelected: (_) => set(() => dur = m)),
              ]),
            ),
            const SizedBox(height: 8),
            FilledButton(
              onPressed: () {
                final title = c.text.trim();
                if (title.isEmpty) return;
                final d = DateTime.now().add(Duration(days: tom ? 1 : 0));
                final t = Task(DateTime.now().millisecondsSinceEpoch % 2000000000 + 1, title, ymd(d),
                    tm == null ? null : '${two(tm!.hour)}:${two(tm!.minute)}',
                    dur: dur);
                Navigator.pop(ctx);
                tasks.add(t);
                remind(t);
                save();
              },
              child: const Text('افزودن'),
            ),
          ]),
        ),
      ),
    );
  }

  String info(Task t) => [
        if (t.time != null) fa(t.time!),
        if (t.dur > 0) '⏳ ${fmtDur(t.dur)}',
      ].join('  ·  ');

  @override
  Widget build(BuildContext context) {
    final today = ymd(DateTime.now());
    final list = tasks.where((t) => t.date == today && !t.missed).toList()
      ..sort((a, b) => (a.done ? 1 : 0) - (b.done ? 1 : 0));
    final later = tasks.where((t) => t.date.compareTo(today) > 0).toList();
    final planned = list.fold<int>(0, (s, t) => s + t.dur);
    final doneMin = list.where((t) => t.done).fold<int>(0, (s, t) => s + t.dur);
    final score = tasks.where((t) => t.done).length - tasks.where((t) => t.missed).length;
    final hist = <Widget>[];
    for (var i = 1; i <= 7; i++) {
      final k = ymd(DateTime.now().subtract(Duration(days: i)));
      final ok = tasks.where((t) => t.date == k && t.done).length;
      final bad = tasks.where((t) => t.date == k && t.missed).length;
      if (ok + bad > 0) {
        hist.add(ListTile(
          dense: true,
          title: Text(fa(k)),
          subtitle: Text('✓ ${fa(ok)}   ✗ ${fa(bad)}'),
          trailing: Text(fa(ok - bad),
              textDirection: TextDirection.ltr,
              style: TextStyle(fontWeight: FontWeight.bold, color: ok - bad < 0 ? Colors.red : Colors.green)),
        ));
      }
    }
    return Scaffold(
      appBar: AppBar(title: const Text('📋 پلنر')),
      floatingActionButton: FloatingActionButton(onPressed: add, child: const Icon(Icons.add)),
      body: ListView(padding: const EdgeInsets.fromLTRB(12, 12, 12, 90), children: [
        Card(
          child: ListTile(
            title: const Text('امتیاز کل'),
            subtitle: const Text('انجام‌شده ۱+  ·  انجام‌نشده ۱−'),
            trailing: Text(fa(score),
                textDirection: TextDirection.ltr,
                style: TextStyle(fontSize: 30, fontWeight: FontWeight.bold, color: score < 0 ? Colors.red : Colors.green)),
          ),
        ),
        if (planned > 0)
          Card(
            child: ListTile(
              leading: const Icon(Icons.timelapse),
              title: const Text('زمان امروز'),
              subtitle: Text('انجام‌شده ${fmtDur(doneMin)} از ${fmtDur(planned)}'),
            ),
          ),
        const Padding(padding: EdgeInsets.all(8), child: Text('امروز')),
        Card(
          child: list.isEmpty
              ? const Padding(padding: EdgeInsets.all(20), child: Center(child: Text('کاری برای امروز نیست')))
              : Column(children: [
                  for (final t in list)
                    CheckboxListTile(
                      value: t.done,
                      onChanged: (_) => toggle(t),
                      title: Text(t.title, style: TextStyle(decoration: t.done ? TextDecoration.lineThrough : null)),
                      subtitle: info(t).isEmpty ? null : Text(info(t)),
                      secondary: IconButton(icon: const Icon(Icons.close), onPressed: () => del(t)),
                    ),
                ]),
        ),
        if (later.isNotEmpty) ...[
          const Padding(padding: EdgeInsets.all(8), child: Text('بعداً')),
          Card(
            child: Column(children: [
              for (final t in later)
                ListTile(
                  title: Text(t.title),
                  subtitle: Text(fa(t.date) + (info(t).isEmpty ? '' : '  ·  ' + info(t))),
                  trailing: IconButton(icon: const Icon(Icons.close), onPressed: () => del(t)),
                ),
            ]),
          ),
        ],
        if (hist.isNotEmpty) ...[
          const Padding(padding: EdgeInsets.all(8), child: Text('امتیاز روزهای اخیر')),
          Card(child: Column(children: hist)),
        ],
      ]),
    );
  }
}
