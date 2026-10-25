package com.example.cnureserve_v2;

import android.app.AlarmManager;
import android.app.DatePickerDialog;
import android.app.PendingIntent;
import android.app.TimePickerDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

        private EditText etUsername, etPassword, etMembers;
        private EditText etUsername2, etPassword2, etMembers2;
        private EditText etRoomId, etReason, etOffsetMs;
        private EditText etStaggerMs;

        // 新增参数输入框
        private EditText etFirstSegmentRetries, etRetryIntervalMs;

        private TextView tvLog1, tvLog2, tvLog2Header;
        private TextView tvScheduledTime, tvCountdown;
        private Button   btnSelectDate, btnSelectStartTime, btnSelectEndTime,
                btnSelectTriggerTime, btnStartCountdown;
        private Button btnSelectRoom;

        private static final String[] ROOM_NAMES = {
                "405（小）", "406（小）", "407（小）", "408（小）", "505（小）", "506（小）",
                "507（小）", "508（小）", "605（小）", "606（小）", "401（大）", "402（大）",
                "601（大）", "602（大）"
        };
        private static final String[] ROOM_IDS = {
                "100533547", "100533559", "100533563", "100533567", "100533571", "100533575",
                "100533579", "100533583", "100533595", "100533599", "100533535", "100533543",
                "100533587", "100533591"
        };

        private StateManager stateManager;
        private Intent pendingSchedule;
        private final SharedPreferences.OnSharedPreferenceChangeListener logListener =
                (prefs, key) -> {
                        if ("logs1".equals(key) || "logs2".equals(key))
                                runOnUiThread(this::loadHistoryLogs);
                };

        private Calendar scheduleDate        = null;
        private Calendar scheduleTriggerTime = null;
        private Calendar startTimeRange      = null;
        private Calendar endTimeRange        = null;

        private Handler countdownHandler = new Handler(Looper.getMainLooper());
        private boolean isCountingDown   = false;
        private long    countdownEndTime = 0;

        @Override
        protected void onCreate(Bundle savedInstanceState) {
                super.onCreate(savedInstanceState);
                setContentView(R.layout.activity_main);

                etUsername  = findViewById(R.id.etUsername);
                etPassword  = findViewById(R.id.etPassword);
                etMembers   = findViewById(R.id.etMembers);
                etUsername2 = findViewById(R.id.etUsername2);
                etPassword2 = findViewById(R.id.etPassword2);
                etMembers2  = findViewById(R.id.etMembers2);
                etRoomId    = findViewById(R.id.etRoomId);
                etReason    = findViewById(R.id.etReason);
                etOffsetMs  = findViewById(R.id.etOffsetMs);
                etStaggerMs = findViewById(R.id.etStaggerMs);

                etFirstSegmentRetries = findViewById(R.id.etFirstSegmentRetries);
                etRetryIntervalMs     = findViewById(R.id.etRetryIntervalMs);

                tvLog1       = findViewById(R.id.tvLog1);
                tvLog2       = findViewById(R.id.tvLog2);
                tvLog2Header = findViewById(R.id.tvLog2Header);
                tvScheduledTime      = findViewById(R.id.tvScheduledTime);
                tvCountdown          = findViewById(R.id.tvCountdown);
                btnSelectDate        = findViewById(R.id.btnSelectDate);
                btnSelectTriggerTime = findViewById(R.id.btnSelectTriggerTime);
                btnSelectStartTime   = findViewById(R.id.btnSelectStartTime);
                btnSelectEndTime     = findViewById(R.id.btnSelectEndTime);
                btnStartCountdown    = findViewById(R.id.btnStartCountdown);
                btnSelectRoom        = findViewById(R.id.btnSelectRoom);

                stateManager = new StateManager(this);
                if (savedInstanceState != null)
                        pendingSchedule = savedInstanceState.getParcelable("pendingSchedule");

                restoreState();
                loadHistoryLogs();
                initDefaultTimes();
                updateScheduleDisplay();

                btnSelectDate.setOnClickListener(v -> showDatePicker());
                btnSelectTriggerTime.setOnClickListener(v -> showTriggerTimePicker());
                btnSelectStartTime.setOnClickListener(v -> showStartTimePicker());
                btnSelectEndTime.setOnClickListener(v -> showEndTimePicker());
                findViewById(R.id.btnSubmit).setOnClickListener(v -> executeNow());
                btnStartCountdown.setOnClickListener(v -> startCountdownSchedule());
                btnSelectRoom.setOnClickListener(v -> showRoomPicker());
                findViewById(R.id.btnBatterySettings).setOnClickListener(v -> openBatterySettings());
                findViewById(R.id.btnNotificationSettings).setOnClickListener(v -> openAppSettings());
        }

        @Override protected void onStart() {
                super.onStart();
                stateManager.registerLogListener(logListener);
                loadHistoryLogs();
        }

        @Override protected void onResume() {
                super.onResume();
                updatePowerStatus();
                if (pendingSchedule != null) {
                        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
                        Intent schedule = pendingSchedule;
                        pendingSchedule = null;
                        if (canSchedule(am)) scheduleAlarms(schedule);
                        else {
                                stateManager.appendLog(1, "精确闹钟授权未获得，任务未安排");
                                Toast.makeText(this, "未获得闹钟权限，任务未安排", Toast.LENGTH_LONG).show();
                        }
                }
        }

        @Override protected void onStop() {
                stateManager.unregisterLogListener(logListener);
                super.onStop();
        }

        @Override protected void onSaveInstanceState(Bundle outState) {
                outState.putParcelable("pendingSchedule", pendingSchedule);
                super.onSaveInstanceState(outState);
        }

        private int parseIntSafe(String s, int def) {
                try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
        }
        private long parseLongSafe(String s, long def) {
                try { return Long.parseLong(s.trim()); } catch (Exception e) { return def; }
        }

        private void startCountdownSchedule() {
                Calendar targetCal = Calendar.getInstance();
                targetCal.set(Calendar.YEAR,         scheduleDate.get(Calendar.YEAR));
                targetCal.set(Calendar.MONTH,        scheduleDate.get(Calendar.MONTH));
                targetCal.set(Calendar.DAY_OF_MONTH, scheduleDate.get(Calendar.DAY_OF_MONTH));
                targetCal.set(Calendar.HOUR_OF_DAY,  scheduleTriggerTime.get(Calendar.HOUR_OF_DAY));
                targetCal.set(Calendar.MINUTE,       scheduleTriggerTime.get(Calendar.MINUTE));
                targetCal.set(Calendar.SECOND,       0);
                targetCal.set(Calendar.MILLISECOND,  0);
                long targetTimeMs = targetCal.getTimeInMillis();

                int offsetMs = parseIntSafe(etOffsetMs.getText().toString(), 500);
                long staggerMs = parseLongSafe(etStaggerMs.getText().toString(), 0L);
                if (targetTimeMs <= System.currentTimeMillis()
                        || targetTimeMs + offsetMs <= System.currentTimeMillis()) {
                        Toast.makeText(this, "触发时间已过期，请重新设置", Toast.LENGTH_SHORT).show();
                        return;
                }

                if (etUsername.getText().toString().trim().isEmpty()
                        || etPassword.getText().toString().trim().isEmpty()
                        || etRoomId.getText().toString().trim().isEmpty()) {
                        Toast.makeText(this, "请填写账号1学号、密码和研讨间（可多选）", Toast.LENGTH_SHORT).show();
                        return;
                }
                if (!validateRoomSelection(etRoomId.getText().toString())) return;

                Intent schedule = buildAlarmIntent(targetTimeMs, "LOGIN", staggerMs);
                schedule.putExtra("offsetMs", offsetMs);
                AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
                if (am == null) {
                        stateManager.appendLog(1, "系统闹钟服务不可用，任务未安排");
                        Toast.makeText(this, "系统闹钟不可用", Toast.LENGTH_LONG).show();
                        return;
                }
                if (!canSchedule(am)) {
                        pendingSchedule = schedule;
                        stateManager.appendLog(1, "缺少精确闹钟权限，等待授权；任务尚未安排");
                        try {
                                startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                        Uri.parse("package:" + getPackageName())));
                        } catch (RuntimeException e) {
                                pendingSchedule = null;
                                stateManager.appendLog(1, "无法打开精确闹钟授权页面：" + e.getClass().getSimpleName());
                                openAppSettings();
                        }
                        return;
                }
                scheduleAlarms(schedule);
        }

        private boolean canSchedule(AlarmManager am) {
                return am != null && (Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                        || am.canScheduleExactAlarms());
        }

        private void scheduleAlarms(Intent schedule) {
                long targetTimeMs = schedule.getLongExtra("targetTimeMs", 0L);
                int offsetMs = schedule.getIntExtra("offsetMs", 500);
                long staggerMs = schedule.getLongExtra("staggerMs", 0L);
                long loginTimeMs = targetTimeMs - 5 * 60_000L;
                long reserveTimeMs = targetTimeMs + offsetMs;
                if (reserveTimeMs <= System.currentTimeMillis()) {
                        stateManager.appendLog(1, "授权返回时预约时间已过，任务未安排");
                        Toast.makeText(this, "预约时间已过，请重新设置", Toast.LENGTH_LONG).show();
                        return;
                }
                AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
                if (!canSchedule(am)) {
                        stateManager.appendLog(1, "精确闹钟不可用，任务未安排");
                        Toast.makeText(this, "精确闹钟不可用", Toast.LENGTH_LONG).show();
                        return;
                }
                int piFlags = PendingIntent.FLAG_UPDATE_CURRENT
                        | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
                int oldSequence = stateManager.getAlarmSequence();
                int nextSequence = oldSequence >= 1_000_000 ? 1 : oldSequence + 1;
                int loginCode = 1000 + nextSequence * 2;
                int reserveCode = loginCode + 1;
                Intent loginIntent = new Intent(schedule);
                loginIntent.putExtra("action", "LOGIN");
                long wakeTimeMs = Math.max(loginTimeMs, System.currentTimeMillis() + 1_000L);
                loginIntent.putExtra("scheduledAtMs", wakeTimeMs);
                PendingIntent loginPi = PendingIntent.getBroadcast(this, loginCode, loginIntent, piFlags);
                Intent reserveIntent = new Intent(schedule);
                reserveIntent.putExtra("action", "RESERVE");
                reserveIntent.putExtra("scheduledAtMs", reserveTimeMs);
                PendingIntent reservePi = PendingIntent.getBroadcast(this, reserveCode, reserveIntent, piFlags);
                boolean loginScheduled = true;
                try {
                        setExactAlarm(am, wakeTimeMs, loginPi);
                        setExactAlarm(am, reserveTimeMs, reservePi);
                } catch (RuntimeException e) {
                        try {
                                am.cancel(loginPi);
                                am.cancel(reservePi);
                        } catch (RuntimeException cancelError) {
                                stateManager.appendLog(1, "闹钟回滚失败：" + cancelError.getClass().getSimpleName());
                        }
                        stateManager.appendLog(1, "闹钟设置失败：" + e.getClass().getSimpleName());
                        Toast.makeText(this, "新闹钟设置失败，原任务保留", Toast.LENGTH_LONG).show();
                        return;
                }

                cancelPreviousAlarms(am, oldSequence);
                stateManager.saveAlarmSequence(nextSequence);
                stateManager.clearCachedLogins();
                isCountingDown = true;
                countdownEndTime = targetTimeMs;
                saveState();
                boolean dual = !schedule.getStringExtra("user2").isEmpty();
                setDualAccountMode(dual);

                SimpleDateFormat stf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
                String modeStr = dual ? "双账号并行" : "单账号";

                String staggerInfo = (dual && staggerMs > 0)
                        ? "\n账号2 错峰延迟: " + staggerMs + "ms"
                        : "";

                stateManager.appendLog(1, "闹钟已安排：服务唤醒="
                        + (loginScheduled ? stf.format(loginTimeMs) : "已跳过")
                        + "，服务内预约到点=" + stf.format(reserveTimeMs) + "，偏移=" + offsetMs + "ms"
                        + "，模式=" + modeStr + staggerInfo);
                updateCountdown();
                updatePowerStatus();
        }

        private void cancelPreviousAlarms(AlarmManager am, int sequence) {
                int flags = PendingIntent.FLAG_NO_CREATE
                        | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
                int[] codes = sequence == 0
                        ? new int[]{201, 202}
                        : new int[]{1000 + sequence * 2, 1001 + sequence * 2};
                for (int code : codes) {
                        PendingIntent previous = PendingIntent.getBroadcast(
                                this, code, new Intent(this, ReserveReceiver.class), flags);
                        if (previous != null) {
                                try {
                                        am.cancel(previous);
                                } catch (RuntimeException e) {
                                        stateManager.appendLog(1, "旧闹钟撤销失败：" + e.getClass().getSimpleName());
                                }
                        }
                }
        }

        private void setExactAlarm(AlarmManager am, long timeMs, PendingIntent pi) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        am.setAlarmClock(new AlarmManager.AlarmClockInfo(timeMs, pi), pi);
                } else {
                        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, timeMs, pi);
                }
        }

        private void updatePowerStatus() {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                boolean optimized = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        && pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName());
                boolean notificationsOff = Build.VERSION.SDK_INT >= 33
                        && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                        != PackageManager.PERMISSION_GRANTED;
                ((TextView) findViewById(R.id.tvBatteryStatus)).setText(
                        (Build.VERSION.SDK_INT < Build.VERSION_CODES.M ? "请确认允许后台运行和自启动。"
                                : optimized ? "电池优化未豁免；请允许后台运行和自启动。"
                                : "系统电池优化已豁免；仍需确认厂商后台运行和自启动设置。")
                        + (notificationsOff ? " 通知权限关闭，前台服务通知可能不可见。" : ""));
                findViewById(R.id.btnNotificationSettings).setVisibility(
                        notificationsOff ? View.VISIBLE : View.GONE);
        }

        private void openBatterySettings() {
                try {
                        startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                } catch (ActivityNotFoundException e) {
                        openAppSettings();
                }
        }

        private void openAppSettings() {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())));
        }

        private Intent buildAlarmIntent(long targetTimeMs, String action, long staggerMs) {
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
                SimpleDateFormat stf = new SimpleDateFormat("HH:mm",      Locale.getDefault());
                Intent intent = new Intent(this, ReserveReceiver.class);
                intent.putExtra("action",      action);
                intent.putExtra("targetTimeMs", targetTimeMs);
                intent.putExtra("user",    etUsername.getText().toString().trim());
                intent.putExtra("pwd",     etPassword.getText().toString().trim());
                intent.putExtra("members", etMembers.getText().toString().trim());
                intent.putExtra("user2",    etUsername2.getText().toString().trim());
                intent.putExtra("pwd2",     etPassword2.getText().toString().trim());
                intent.putExtra("members2", etMembers2.getText().toString().trim());
                intent.putExtra("roomId",    etRoomId.getText().toString().trim());
                intent.putExtra("date",      sdf.format(scheduleDate.getTime()));
                intent.putExtra("startTime", stf.format(startTimeRange.getTime()));
                intent.putExtra("endTime",   stf.format(endTimeRange.getTime()));
                intent.putExtra("reason",    etReason.getText().toString().trim());
                intent.putExtra("staggerMs", staggerMs);

                // 传递重试参数
                intent.putExtra("retries_a",  parseIntSafe(etFirstSegmentRetries.getText().toString(), 2));
                intent.putExtra("interval_b", parseLongSafe(etRetryIntervalMs.getText().toString(), 1000L));

                return intent;
        }

        private void executeNow() {
                stateManager.clearLogs();
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
                SimpleDateFormat stf = new SimpleDateFormat("HH:mm",      Locale.getDefault());
                String date   = sdf.format(Calendar.getInstance().getTime());
                String startT = stf.format(startTimeRange.getTime());
                String endT   = stf.format(endTimeRange.getTime());

                String u1  = etUsername.getText().toString().trim();
                String p1  = etPassword.getText().toString().trim();
                String m1  = etMembers.getText().toString().trim();
                String u2  = etUsername2.getText().toString().trim();
                String p2  = etPassword2.getText().toString().trim();
                String m2  = etMembers2.getText().toString().trim();
                String rid = etRoomId.getText().toString().trim();
                String rsn = etReason.getText().toString().trim();

                int a = parseIntSafe(etFirstSegmentRetries.getText().toString(), 2);
                long b = parseLongSafe(etRetryIntervalMs.getText().toString(), 1000L);

                boolean dual = !u2.isEmpty();
                setDualAccountMode(dual);

                stateManager.appendLog(1, "手动执行开始");

                if (dual) {
                        long staggerMs = parseLongSafe(etStaggerMs.getText().toString(), 0L);

                        ReservationManager mgr1 = new ReservationManager(
                                msg -> {}, () -> {}, stateManager);
                        mgr1.setAccountId(1);
                        mgr1.setSegmentFilter(0, 2);
                        mgr1.setRetryParams(a, b);
                        mgr1.start(u1, p1, rid, date, startT, endT, rsn, m1);

                        ReservationManager mgr2 = new ReservationManager(
                                msg -> {}, () -> {}, stateManager);
                        mgr2.setAccountId(2);
                        mgr2.setSegmentFilter(1, 2);
                        mgr2.setRetryParams(a, b);

                        new Handler(Looper.getMainLooper()).postDelayed(
                                () -> mgr2.start(u2, p2, rid, date, startT, endT, rsn, m2),
                                staggerMs);
                } else {
                        ReservationManager mgr = new ReservationManager(
                                msg -> {}, () -> {}, stateManager);
                        mgr.setAccountId(1);
                        mgr.setRetryParams(a, b);
                        mgr.start(u1, p1, rid, date, startT, endT, rsn, m1);
                }
        }

        private void updateCountdown() {
                if (!isCountingDown) return;
                long remaining = countdownEndTime - System.currentTimeMillis();
                if (remaining <= 0) {
                        isCountingDown = false;
                        tvCountdown.setText("预约任务已交由闹钟后台处理");
                        return;
                }
                long h = remaining / 3_600_000;
                long m = (remaining % 3_600_000) / 60_000;
                long s = (remaining % 60_000) / 1_000;
                tvCountdown.setText(String.format("倒计时: %02d:%02d:%02d", h, m, s));
                countdownHandler.postDelayed(this::updateCountdown, 1000);
        }

        private void setDualAccountMode(boolean dual) {
                int vis = dual ? View.VISIBLE : View.GONE;
                tvLog2Header.setVisibility(vis);
                tvLog2.setVisibility(vis);
        }

        private void showRoomPicker() {
                String current = etRoomId.getText().toString().trim();
                List<String> selected = new ArrayList<>();
                if (!current.isEmpty()) selected.addAll(Arrays.asList(current.split(",")));
                boolean[] checked = new boolean[ROOM_IDS.length];
                List<Integer> selectionOrder = new ArrayList<>();
                for (int i = 0; i < ROOM_IDS.length; i++) checked[i] = selected.contains(ROOM_IDS[i]);
                for (String id : selected) {
                        int index = Arrays.asList(ROOM_IDS).indexOf(id);
                        if (index >= 0) selectionOrder.add(index);
                }
                AlertDialog dialog = new AlertDialog.Builder(this)
                        .setTitle("按顺序选择研讨间（只能选择同一类型）")
                        .setMultiChoiceItems(ROOM_NAMES, checked, (d, which, isChecked) -> {
                                int firstType = -1;
                                for (int i = 0; i < checked.length; i++) if (checked[i]) { firstType = i >= 10 ? 1 : 0; break; }
                                if (isChecked && firstType >= 0 && (which >= 10 ? 1 : 0) != firstType) {
                                        ((AlertDialog) d).getListView().setItemChecked(which, false);
                                        Toast.makeText(this, "小研讨间和大研讨间不能混选", Toast.LENGTH_SHORT).show();
                                } else {
                                        checked[which] = isChecked;
                                        if (isChecked && !selectionOrder.contains(which)) selectionOrder.add(which);
                                        if (!isChecked) selectionOrder.remove((Integer) which);
                                }
                        })
                        .setPositiveButton("确定", (d, w) -> {
                                StringBuilder ids = new StringBuilder();
                                for (int i : selectionOrder) if (checked[i]) {
                                        if (ids.length() > 0) ids.append(",");
                                        ids.append(ROOM_IDS[i]);
                                }
                                etRoomId.setText(ids.toString());
                                saveState();
                        })
                        .setNegativeButton("取消", null).create();
                dialog.show();
        }

        private boolean validateRoomSelection(String value) {
                String[] ids = value.split(",");
                int type = -1;
                for (String raw : ids) {
                        String id = raw.trim();
                        if (id.isEmpty()) continue;
                        int index = Arrays.asList(ROOM_IDS).indexOf(id);
                        if (index < 0) continue;
                        int current = index >= 10 ? 1 : 0;
                        if (type < 0) type = current;
                        else if (type != current) {
                                Toast.makeText(this, "小研讨间和大研讨间不能混选", Toast.LENGTH_LONG).show();
                                return false;
                        }
                }
                return true;
        }

        private void saveState() {
                stateManager.saveReservationParams(
                        etUsername.getText().toString(), etPassword.getText().toString(),
                        etRoomId.getText().toString(),   etReason.getText().toString(),
                        etMembers.getText().toString());
                stateManager.saveAccount2Params(
                        etUsername2.getText().toString(),
                        etPassword2.getText().toString(),
                        etMembers2.getText().toString());
                stateManager.saveDateInfo(
                        scheduleDate.getTimeInMillis(),
                        scheduleTriggerTime.get(Calendar.HOUR_OF_DAY),
                        scheduleTriggerTime.get(Calendar.MINUTE),
                        startTimeRange.get(Calendar.HOUR_OF_DAY),
                        startTimeRange.get(Calendar.MINUTE),
                        endTimeRange.get(Calendar.HOUR_OF_DAY),
                        endTimeRange.get(Calendar.MINUTE));
                stateManager.saveCountdownState(countdownEndTime, isCountingDown);
                stateManager.saveOffsetMs(etOffsetMs.getText().toString());
                stateManager.saveStaggerMs(etStaggerMs.getText().toString());

                stateManager.saveRetryParams(
                        etFirstSegmentRetries.getText().toString(),
                        etRetryIntervalMs.getText().toString());
        }

        private void restoreState() {
                etUsername.setText(stateManager.getUsername());
                etPassword.setText(stateManager.getPassword());
                etRoomId.setText(stateManager.getRoomId());
                etReason.setText(stateManager.getReason());
                etMembers.setText(stateManager.getMembers());
                etUsername2.setText(stateManager.getUsername2());
                etPassword2.setText(stateManager.getPassword2());
                etMembers2.setText(stateManager.getMembers2());
                etOffsetMs.setText(stateManager.getOffsetMs());
                etStaggerMs.setText(stateManager.getStaggerMs());

                etFirstSegmentRetries.setText(stateManager.getFirstSegmentRetries());
                etRetryIntervalMs.setText(stateManager.getRetryIntervalMs());

                long dateMs = stateManager.getScheduleDateMs();
                if (dateMs > 0) {
                        scheduleDate = Calendar.getInstance(); scheduleDate.setTimeInMillis(dateMs);
                        scheduleTriggerTime = Calendar.getInstance();
                        scheduleTriggerTime.set(Calendar.HOUR_OF_DAY, stateManager.getTriggerHour());
                        scheduleTriggerTime.set(Calendar.MINUTE,      stateManager.getTriggerMinute());
                        startTimeRange = Calendar.getInstance();
                        startTimeRange.set(Calendar.HOUR_OF_DAY, stateManager.getStartHour());
                        startTimeRange.set(Calendar.MINUTE,      stateManager.getStartMinute());
                        endTimeRange = Calendar.getInstance();
                        endTimeRange.set(Calendar.HOUR_OF_DAY, stateManager.getEndHour());
                        endTimeRange.set(Calendar.MINUTE,      stateManager.getEndMinute());
                }

                if (stateManager.isCountdownActive()) {
                        countdownEndTime = stateManager.getCountdownTargetTime();
                        if (countdownEndTime > System.currentTimeMillis()) {
                                isCountingDown = true;
                                updateCountdown();
                        }
                }
        }

        private void initDefaultTimes() {
                if (scheduleDate == null)        scheduleDate = Calendar.getInstance();
                if (scheduleTriggerTime == null) {
                        scheduleTriggerTime = Calendar.getInstance();
                        scheduleTriggerTime.set(Calendar.HOUR_OF_DAY, 8);
                        scheduleTriggerTime.set(Calendar.MINUTE, 0);
                }
                if (startTimeRange == null) {
                        startTimeRange = Calendar.getInstance();
                        startTimeRange.set(Calendar.HOUR_OF_DAY, 8);
                        startTimeRange.set(Calendar.MINUTE, 0);
                }
                if (endTimeRange == null) {
                        endTimeRange = Calendar.getInstance();
                        endTimeRange.set(Calendar.HOUR_OF_DAY, 22);
                        endTimeRange.set(Calendar.MINUTE, 0);
                }
        }

        private void loadHistoryLogs() {
                String log1 = stateManager.getLog(1);
                String log2 = stateManager.getLog(2);
                tvLog1.setText(log1 == null || log1.isEmpty() ? "等待..." : log1.trim());
                if (log2 != null && !log2.isEmpty()) {
                        setDualAccountMode(true);
                        tvLog2.setText(log2.trim());
                } else {
                        tvLog2.setText("等待...");
                }
        }

        private void updateScheduleDisplay() {
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
                SimpleDateFormat stf = new SimpleDateFormat("HH:mm",      Locale.getDefault());
                tvScheduledTime.setText(
                        "预约日期: "   + sdf.format(scheduleDate.getTime()) +
                                "\n触发时间: " + stf.format(scheduleTriggerTime.getTime()) +
                                "\n预约时段: " + stf.format(startTimeRange.getTime())
                                + " ~ " + stf.format(endTimeRange.getTime()));
        }

        private void showDatePicker() {
                new DatePickerDialog(this, (v, y, m, d) -> {
                        scheduleDate = Calendar.getInstance(); scheduleDate.set(y, m, d);
                        saveState(); updateScheduleDisplay();
                }, scheduleDate.get(Calendar.YEAR), scheduleDate.get(Calendar.MONTH),
                        scheduleDate.get(Calendar.DAY_OF_MONTH)).show();
        }
        private void showTriggerTimePicker() {
                new TimePickerDialog(this, (v, h, m) -> {
                        scheduleTriggerTime = Calendar.getInstance();
                        scheduleTriggerTime.set(Calendar.HOUR_OF_DAY, h);
                        scheduleTriggerTime.set(Calendar.MINUTE, m);
                        saveState(); updateScheduleDisplay();
                }, scheduleTriggerTime.get(Calendar.HOUR_OF_DAY),
                        scheduleTriggerTime.get(Calendar.MINUTE), true).show();
        }
        private void showStartTimePicker() {
                new TimePickerDialog(this, (v, h, m) -> {
                        startTimeRange.set(Calendar.HOUR_OF_DAY, h); startTimeRange.set(Calendar.MINUTE, m);
                        saveState(); updateScheduleDisplay();
                }, startTimeRange.get(Calendar.HOUR_OF_DAY), startTimeRange.get(Calendar.MINUTE), true).show();
        }
        private void showEndTimePicker() {
                new TimePickerDialog(this, (v, h, m) -> {
                        endTimeRange.set(Calendar.HOUR_OF_DAY, h); endTimeRange.set(Calendar.MINUTE, m);
                        saveState(); updateScheduleDisplay();
                }, endTimeRange.get(Calendar.HOUR_OF_DAY), endTimeRange.get(Calendar.MINUTE), true).show();
        }
}
