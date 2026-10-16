package com.example.cnureserve_v2;

import android.app.AlarmManager;
import android.app.DatePickerDialog;
import android.app.PendingIntent;
import android.app.TimePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
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

        private StateManager stateManager;

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

                stateManager = new StateManager(this);

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
        }

        private int parseIntSafe(String s, int def) {
                try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
        }
        private long parseLongSafe(String s, long def) {
                try { return Long.parseLong(s.trim()); } catch (Exception e) { return def; }
        }

        private void startCountdownSchedule() {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
                        if (am != null && !am.canScheduleExactAlarms()) {
                                startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM));
                                return;
                        }
                }

                Calendar targetCal = Calendar.getInstance();
                targetCal.set(Calendar.YEAR,         scheduleDate.get(Calendar.YEAR));
                targetCal.set(Calendar.MONTH,        scheduleDate.get(Calendar.MONTH));
                targetCal.set(Calendar.DAY_OF_MONTH, scheduleDate.get(Calendar.DAY_OF_MONTH));
                targetCal.set(Calendar.HOUR_OF_DAY,  scheduleTriggerTime.get(Calendar.HOUR_OF_DAY));
                targetCal.set(Calendar.MINUTE,       scheduleTriggerTime.get(Calendar.MINUTE));
                targetCal.set(Calendar.SECOND,       0);
                targetCal.set(Calendar.MILLISECOND,  0);
                long targetTimeMs = targetCal.getTimeInMillis();

                if (targetTimeMs <= System.currentTimeMillis()) {
                        Toast.makeText(this, "触发时间已过期，请重新设置", Toast.LENGTH_SHORT).show();
                        return;
                }

                if (etUsername.getText().toString().trim().isEmpty()
                        || etPassword.getText().toString().trim().isEmpty()
                        || etRoomId.getText().toString().trim().isEmpty()) {
                        Toast.makeText(this, "请填写账号1学号、密码和研讨间ID", Toast.LENGTH_SHORT).show();
                        return;
                }

                int offsetMs = parseIntSafe(etOffsetMs.getText().toString(), 500);
                long staggerMs = parseLongSafe(etStaggerMs.getText().toString(), 0L);

                stateManager.clearLogs();
                stateManager.clearCachedLogins();
                isCountingDown   = true;
                countdownEndTime = targetTimeMs;
                saveState();

                AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
                int piFlags = PendingIntent.FLAG_UPDATE_CURRENT
                        | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);

                Intent loginIntent = buildAlarmIntent(targetTimeMs, "LOGIN", staggerMs);
                PendingIntent loginPi = PendingIntent.getBroadcast(this, 201, loginIntent, piFlags);
                setExactAlarm(am, targetTimeMs - 30_000L, loginPi);

                Intent reserveIntent = buildAlarmIntent(targetTimeMs, "RESERVE", staggerMs);
                PendingIntent reservePi = PendingIntent.getBroadcast(this, 202, reserveIntent, piFlags);
                setExactAlarm(am, targetTimeMs + offsetMs, reservePi);

                boolean dual = !etUsername2.getText().toString().trim().isEmpty();
                setDualAccountMode(dual);

                SimpleDateFormat stf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
                String modeStr = dual
                        ? "双账号并行 (" + etUsername.getText().toString().trim()
                          + " + " + etUsername2.getText().toString().trim() + ")"
                        : "单账号 (" + etUsername.getText().toString().trim() + ")";

                String staggerInfo = (dual && staggerMs > 0)
                        ? "\n账号2 错峰延迟: " + staggerMs + "ms"
                        : "";

                tvLog1.setText("闹钟1(登录)：" + stf.format(targetTimeMs - 30_000L)
                        + "\n闹钟2(预约)：" + stf.format(targetTimeMs + offsetMs) + " (+" + offsetMs + "ms)"
                        + "\n模式：" + modeStr
                        + staggerInfo
                        + "\n\n请关闭本应用的\"电池优化\"开关，并确保屏幕不被强制断网。");
                if (dual) tvLog2.setText("等待闹钟触发...");
                updateCountdown();
        }

        private void setExactAlarm(AlarmManager am, long timeMs, PendingIntent pi) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        am.setAlarmClock(new AlarmManager.AlarmClockInfo(timeMs, pi), pi);
                } else {
                        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, timeMs, pi);
                }
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

                tvLog1.setText("正在执行（账号1）...");
                if (dual) tvLog2.setText("正在执行（账号2）...");

                if (dual) {
                        long staggerMs = parseLongSafe(etStaggerMs.getText().toString(), 0L);

                        ReservationManager mgr1 = new ReservationManager(
                                msg -> tvLog1.append("\n" + msg), () -> {}, stateManager);
                        mgr1.setAccountId(1);
                        mgr1.setSegmentFilter(0, 2);
                        mgr1.setRetryParams(a, b);
                        mgr1.start(u1, p1, rid, date, startT, endT, rsn, m1);

                        ReservationManager mgr2 = new ReservationManager(
                                msg -> tvLog2.append("\n" + msg), () -> {}, stateManager);
                        mgr2.setAccountId(2);
                        mgr2.setSegmentFilter(1, 2);
                        mgr2.setRetryParams(a, b);

                        new Handler(Looper.getMainLooper()).postDelayed(
                                () -> mgr2.start(u2, p2, rid, date, startT, endT, rsn, m2),
                                staggerMs);
                } else {
                        ReservationManager mgr = new ReservationManager(
                                msg -> tvLog1.append("\n" + msg), () -> {}, stateManager);
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
                if (log1 != null && !log1.isEmpty()) tvLog1.setText("上次运行日志（账号1）:\n" + log1);
                if (log2 != null && !log2.isEmpty()) {
                        setDualAccountMode(true);
                        tvLog2.setText("上次运行日志（账号2）:\n" + log2);
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