package com.example.cnureserve_v2;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.PowerManager;

/**
 * 闹钟广播接收器。
 *
 * 关键设计：在 onReceive 里立即持锁，然后启动 Service，
 * Service 启动后调用 releaseReceiverWakeLock() 放锁。
 * 这样消除了"闹钟到达 → Service.onStartCommand 持锁"之间的空窗期，
 * 防止手机在这几百毫秒里再次进入深眠。
 */
public class ReserveReceiver extends BroadcastReceiver {

    private static volatile PowerManager.WakeLock sWakeLock;

    /** 在 Receiver.onReceive 中调用：抢锁，防止 CPU 在跳转到 Service 前休眠 */
    static synchronized void acquireReceiverWakeLock(Context ctx) {
        if (sWakeLock == null) {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            sWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CNU:ReceiverLock");
            sWakeLock.setReferenceCounted(false);
        }
        if (!sWakeLock.isHeld()) {
            sWakeLock.acquire(2 * 60 * 1000L); // 最多持 2 分钟（Service 启动后主动放锁）
        }
    }

    /** 在 Service.onStartCommand 中调用：Service 已持自己的锁，可以释放 Receiver 的锁 */
    public static synchronized void releaseReceiverWakeLock() {
        if (sWakeLock != null && sWakeLock.isHeld()) {
            sWakeLock.release();
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        // ① 立即持锁，防止 CPU 在 startForegroundService 返回前睡去
        acquireReceiverWakeLock(context);

        // ② 将所有 extras（含 action / 账号信息 / 时间参数）原样转发给 Service
        Intent serviceIntent = new Intent(context, ReservationForegroundService.class);
        if (intent.getExtras() != null) serviceIntent.putExtras(intent.getExtras());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent);
        } else {
            context.startService(serviceIntent);
        }
    }
}