package com.example.cnureserve_v2;

import android.content.Context;
import android.content.SharedPreferences;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import okhttp3.Cookie;
import okhttp3.HttpUrl;

public class StateManager {
    private static final String PREF_NAME = "cnu_reservation_v2_state";
    private final SharedPreferences prefs;

    public StateManager(Context context) {
        this.prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public void saveReservationParams(String u, String p, String r, String re, String m) {
        prefs.edit().putString("u", u).putString("p", p).putString("r", r)
                .putString("re", re).putString("m", m).apply();
    }

    public void saveAccount2Params(String u2, String p2, String m2) {
        prefs.edit().putString("u2", u2).putString("p2", p2).putString("m2", m2).apply();
    }

    public void saveDateInfo(long d, int th, int tm, int sh, int sm, int eh, int em) {
        prefs.edit().putLong("d", d).putInt("th", th).putInt("tm", tm)
                .putInt("sh", sh).putInt("sm", sm).putInt("eh", eh).putInt("em", em).apply();
    }

    public void saveCountdownState(long t, boolean a) {
        prefs.edit().putLong("ct", t).putBoolean("ca", a).apply();
    }

    public void saveOffsetMs(String ms) {
        prefs.edit().putString("offsetMs", ms).apply();
    }

    public void saveStaggerMs(String ms) {
        prefs.edit().putString("staggerMs", ms).apply();
    }

    // 新增: 保存重试参数 a 和 b
    public void saveRetryParams(String a, String b) {
        prefs.edit().putString("retry_a", a).putString("retry_b", b).apply();
    }

    public String getUsername()  { return prefs.getString("u",  ""); }
    public String getPassword()  { return prefs.getString("p",  ""); }
    public String getRoomId()    { return prefs.getString("r",  ""); }
    public String getReason()    { return prefs.getString("re", ""); }
    public String getMembers()   { return prefs.getString("m",  ""); }

    public String getUsername2() { return prefs.getString("u2", ""); }
    public String getPassword2() { return prefs.getString("p2", ""); }
    public String getMembers2()  { return prefs.getString("m2", ""); }

    public long getScheduleDateMs()  { return prefs.getLong("d",   0); }
    public int  getTriggerHour()     { return prefs.getInt("th",   8); }
    public int  getTriggerMinute()   { return prefs.getInt("tm",   0); }
    public int  getStartHour()       { return prefs.getInt("sh",   8); }
    public int  getStartMinute()     { return prefs.getInt("sm",   0); }
    public int  getEndHour()         { return prefs.getInt("eh",  22); }
    public int  getEndMinute()       { return prefs.getInt("em",   0); }
    public String getOffsetMs()      { return prefs.getString("offsetMs",  "500"); }
    public String getStaggerMs()     { return prefs.getString("staggerMs", "0"); }

    // 新增: 读取重试参数 a 和 b
    public String getFirstSegmentRetries() { return prefs.getString("retry_a", "2"); }
    public String getRetryIntervalMs()     { return prefs.getString("retry_b", "1000"); }

    public long    getCountdownTargetTime() { return prefs.getLong("ct",    0); }
    public boolean isCountdownActive()      { return prefs.getBoolean("ca", false); }

    public void saveCachedLogin(int account, String token, long accNo, String membersStr) {
        prefs.edit()
                .putString("tok"  + account, token)
                .putLong("ano"    + account, accNo)
                .putString("memC" + account, membersStr)
                .apply();
    }

    public String getCachedToken(int account)      { return prefs.getString("tok"  + account, ""); }
    public long   getCachedAccNo(int account)      { return prefs.getLong("ano"    + account, 0);  }
    public String getCachedMembersStr(int account) { return prefs.getString("memC" + account, ""); }

    public void saveCookies(int account, List<Cookie> cookies) {
        Set<String> set = new HashSet<>();
        for (Cookie c : cookies) set.add(c.toString());
        prefs.edit().putStringSet("cookies" + account, set).apply();
    }

    public List<Cookie> getCookies(int account, HttpUrl url) {
        Set<String> set = prefs.getStringSet("cookies" + account, new HashSet<>());
        List<Cookie> list = new ArrayList<>();
        for (String s : set) {
            Cookie c = Cookie.parse(url, s);
            if (c != null) list.add(c);
        }
        return list;
    }

    public void clearCachedLogins() {
        prefs.edit()
                .remove("tok1").remove("ano1").remove("memC1").remove("cookies1")
                .remove("tok2").remove("ano2").remove("memC2").remove("cookies2")
                .apply();
    }

    public void appendLog(int account, String msg) {
        String key      = "logs" + account;
        String existing = prefs.getString(key, "");
        String time     = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        String newLog   = existing + "\n[" + time + "] " + msg;
        if (newLog.length() > 12000) newLog = newLog.substring(newLog.length() - 12000);
        prefs.edit().putString(key, newLog).apply();
    }

    public String getLog(int account) { return prefs.getString("logs" + account, ""); }

    public void clearLogs() {
        prefs.edit().remove("logs1").remove("logs2").apply();
    }
}