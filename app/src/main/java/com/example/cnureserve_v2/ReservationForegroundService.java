package com.example.cnureserve_v2;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import androidx.core.app.NotificationCompat;

public class ReservationForegroundService extends Service {
    private static final String TAG        = "ReserveService_v2";
    private static final String CHANNEL_ID = "cnu_reserve_v2_channel";

    private static final String ACTION_LOGIN   = "LOGIN";
    private static final String ACTION_RESERVE = "RESERVE";

    private PowerManager.WakeLock wakeLock;
    private StateManager stateManager;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private ReservationManager manager1;
    private ReservationManager manager2;

    private int activeManagers    = 0;
    private int completedManagers = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        stateManager = new StateManager(this);
        try {
            createNotificationChannel();
            startForeground(1001, buildNotification("CNU 预约准备中..."));
            stateManager.appendLog(1, "前台服务已启动");
        } catch (RuntimeException e) {
            stateManager.appendLog(1, "前台服务进入前台失败：" + e.getClass().getSimpleName());
            ReserveReceiver.releaseReceiverWakeLock();
            throw e;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stateManager.appendLog(1, "服务收到空任务，未执行");
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        try {
            acquireWakeLock();
        } catch (RuntimeException e) {
            stateManager.appendLog(1, "服务唤醒锁获取失败：" + e.getClass().getSimpleName());
            Log.e(TAG, "Wake lock failed", e);
            stopSelf(startId);
            return START_NOT_STICKY;
        } finally {
            ReserveReceiver.releaseReceiverWakeLock();
        }

        String action = intent.getStringExtra("action");
        if (action == null) action = ACTION_LOGIN;
        if (!ACTION_LOGIN.equals(action) && !ACTION_RESERVE.equals(action)) {
            stateManager.appendLog(1, "服务收到未知动作，未执行");
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        long scheduledAt = intent.getLongExtra("scheduledAtMs", 0L);
        stateManager.appendLog(1, action + " 服务开始处理，距预定时间="
                + (scheduledAt > 0 ? System.currentTimeMillis() - scheduledAt : 0L) + "ms");

        String user1    = safeStr(intent.getStringExtra("user"));
        String pwd1     = safeStr(intent.getStringExtra("pwd"));
        String user2    = safeStr(intent.getStringExtra("user2"));
        String pwd2     = safeStr(intent.getStringExtra("pwd2"));
        String roomId   = safeStr(intent.getStringExtra("roomId"));
        String date     = safeStr(intent.getStringExtra("date"));
        String startT   = safeStr(intent.getStringExtra("startTime"));
        String endT     = safeStr(intent.getStringExtra("endTime"));
        String reason   = safeStr(intent.getStringExtra("reason"));
        String members1 = safeStr(intent.getStringExtra("members"));
        String members2 = safeStr(intent.getStringExtra("members2"));
        if (user1.isEmpty() || pwd1.isEmpty() || roomId.isEmpty()) {
            stateManager.appendLog(1, action + " 参数缺失，未执行");
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        long staggerMs = intent.getLongExtra("staggerMs", 0L);

        // 提取重试参数
        int retriesA = intent.getIntExtra("retries_a", 2);
        long intervalB = intent.getLongExtra("interval_b", 1000L);

        boolean hasTwoAccounts = !user2.isEmpty();

        completedManagers = 0;
        activeManagers    = hasTwoAccounts ? 2 : 1;

        manager1 = buildManager(1, hasTwoAccounts ? 0 : -1, hasTwoAccounts ? 2 : 1);
        manager1.setRetryParams(retriesA, intervalB);
        if (hasTwoAccounts) {
            manager2 = buildManager(2, 1, 2);
            manager2.setRetryParams(retriesA, intervalB);
        }

        Log.d(TAG, "Service 启动 action=" + action
                + " hasTwoAccounts=" + hasTwoAccounts
                + " staggerMs=" + staggerMs);

        try {
            if (ACTION_RESERVE.equals(action)) {
                String staggerInfo = (hasTwoAccounts && staggerMs > 0)
                        ? "（账号2 错峰 " + staggerMs + "ms）"
                        : "";
                updateNotification("执行预约..." + staggerInfo);
                handleReserveAction(user1, pwd1, user2, pwd2,
                        roomId, date, startT, endT, reason, members1, members2,
                        hasTwoAccounts, staggerMs);
            } else {
                updateNotification("预登录中（预约前" + 30 + "秒）...");
                handleLoginAction(user1, pwd1, user2, pwd2,
                        roomId, date, startT, endT, reason, members1, members2, hasTwoAccounts);
            }
        } catch (RuntimeException e) {
            stateManager.appendLog(1, action + " 服务执行失败：" + e.getClass().getSimpleName());
            Log.e(TAG, "Service action failed", e);
            stopSelf(startId);
        }

        return START_NOT_STICKY;
    }

    private void handleLoginAction(String user1, String pwd1, String user2, String pwd2,
                                   String roomId, String date, String startT, String endT,
                                   String reason, String members1, String members2,
                                   boolean hasTwoAccounts) {
        manager1.performLoginOnly(user1, pwd1, roomId, date, startT, endT, reason, members1);
        if (hasTwoAccounts) {
            manager2.performLoginOnly(user2, pwd2, roomId, date, startT, endT, reason, members2);
        }
    }

    private void handleReserveAction(String user1, String pwd1, String user2, String pwd2,
                                     String roomId, String date, String startT, String endT,
                                     String reason, String members1, String members2,
                                     boolean hasTwoAccounts, long staggerMs) {
        manager1.setReservationParams(roomId, date, startT, endT, reason);
        if (hasTwoAccounts) manager2.setReservationParams(roomId, date, startT, endT, reason);

        String token1 = stateManager.getCachedToken(1);
        String token2 = hasTwoAccounts ? stateManager.getCachedToken(2) : "";

        if (!token1.isEmpty()) {
            manager1.performReservationFromPersisted();

            if (hasTwoAccounts) {
                final String finalToken2  = token2;
                final String finalUser2   = user2;
                final String finalPwd2    = pwd2;
                final String finalMembers2 = members2;
                mainHandler.postDelayed(() -> {
                    if (!finalToken2.isEmpty()) {
                        manager2.performReservationFromPersisted();
                    } else {
                        Log.w(TAG, "账号2 token 不存在，回退全流程（错峰延迟后）");
                        manager2.start(finalUser2, finalPwd2,
                                roomId, date, startT, endT, reason, finalMembers2);
                    }
                }, staggerMs);
            }
        } else {
            Log.w(TAG, "账号1 token 不存在，两账号均回退全流程");
            manager1.start(user1, pwd1, roomId, date, startT, endT, reason, members1);

            if (hasTwoAccounts) {
                final String finalUser2    = user2;
                final String finalPwd2     = pwd2;
                final String finalMembers2 = members2;
                mainHandler.postDelayed(() ->
                                manager2.start(finalUser2, finalPwd2,
                                        roomId, date, startT, endT, reason, finalMembers2),
                        staggerMs);
            }
        }
    }

    private void onManagerComplete() {
        completedManagers++;
        Log.d(TAG, "Manager 完成 " + completedManagers + "/" + activeManagers);
        if (completedManagers >= activeManagers) {
            Log.d(TAG, "全部完成，Service 停止");
            mainHandler.post(this::stopSelf);
        }
    }

    private ReservationManager buildManager(int accountId, int filterStart, int filterStride) {
        ReservationManager m = new ReservationManager(
                msg -> Log.d(TAG, "[账号" + accountId + "] " + msg),
                this::onManagerComplete,
                stateManager);
        m.setAccountId(accountId);
        if (filterStart >= 0) m.setSegmentFilter(filterStart, filterStride);
        return m;
    }

    private void acquireWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (wakeLock == null)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CNU:ServiceLock");
        if (!wakeLock.isHeld())
            wakeLock.acquire(5 * 60 * 1000L);
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(1001, buildNotification(text));
    }

    private Notification buildNotification(String content) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("CNU 预约 v2")
                .setContentText(content)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setOngoing(true)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel c = new NotificationChannel(
                    CHANNEL_ID, "预约执行V2", NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                    .createNotificationChannel(c);
        }
    }

    private static String safeStr(String s) { return s != null ? s : ""; }

    @Override
    public void onDestroy() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
