package com.example.cnureserve_v2;

import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.crypto.Cipher;
import okhttp3.*;

public class ReservationManager {
    private static final String BASE_URL = "https://selfservice.cnu.edu.cn/ic-web";

    private final OkHttpClient       client;
    private final Handler            mainHandler = new Handler(Looper.getMainLooper());
    private final LogListener        listener;
    private final CompletionListener completionListener;
    private final StateManager       stateManager;

    private int accountId = 1;

    private String     cachedToken    = null;
    private long       cachedAppAccNo = 0;
    private List<Long> cachedMembers  = null;
    private String     cachedRoomId, cachedDate, cachedStart, cachedEnd, cachedReason;

    private boolean loginCompleted     = false;
    private boolean reservationPending = false;

    private int segmentFilterStart  = 0;
    private int segmentFilterStride = 1;

    // 重试参数控制，默认 a=2, b=1000
    private int  maxFirstSegmentRetries = 2;
    private long retryIntervalMs        = 1000L;

    private int pendingRequests = 0;

    public interface LogListener        { void onLog(String msg); }
    public interface CompletionListener { void onComplete(); }

    public ReservationManager(LogListener listener, CompletionListener completionListener,
                              StateManager stateManager) {
        this.listener           = listener;
        this.completionListener = completionListener;
        this.stateManager       = stateManager;
        this.client = new OkHttpClient.Builder()
                .cookieJar(new CookieJar() {
                    @Override
                    public void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
                        if (stateManager == null) return;
                        List<Cookie> existing = stateManager.getCookies(accountId, url);
                        List<Cookie> merged = new ArrayList<>(existing);
                        for (Cookie newCookie : cookies) {
                            for (int i = 0; i < merged.size(); i++) {
                                if (merged.get(i).name().equals(newCookie.name())) {
                                    merged.remove(i);
                                    break;
                                }
                            }
                            merged.add(newCookie);
                        }
                        stateManager.saveCookies(accountId, merged);
                    }

                    @Override
                    public List<Cookie> loadForRequest(HttpUrl url) {
                        if (stateManager == null) return new ArrayList<>();
                        return stateManager.getCookies(accountId, url);
                    }
                })
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build();
    }

    public void setAccountId(int id) { this.accountId = id; }

    public void setSegmentFilter(int start, int stride) {
        segmentFilterStart  = start;
        segmentFilterStride = stride;
    }

    public void setRetryParams(int retriesA, long intervalB) {
        this.maxFirstSegmentRetries = retriesA;
        this.retryIntervalMs = intervalB;
    }

    public void setReservationParams(String roomId, String date,
                                     String start, String end, String reason) {
        cachedRoomId = roomId; cachedDate = date;
        cachedStart  = start;  cachedEnd  = end;  cachedReason = reason;
    }

    private void log(String msg) {
        if (stateManager != null) stateManager.appendLog(accountId, msg);
        mainHandler.post(() -> listener.onLog(msg));
    }

    public void start(String user, String pwd, String roomId, String date,
                      String start, String end, String reason, String extraMembers) {
        List<TimeSegment> segs = getFilteredSegments(start, end);
        log("🚀 开始任务 | 账号: " + user
                + " | 日期: " + date
                + " | 分配时段（共" + segs.size() + "段）: " + segsToStr(segs));
        incrementPendingRequests();
        fetchPublicKeyThenLogin(user, pwd, roomId, date, start, end, reason, extraMembers);
    }

    private void fetchPublicKeyThenLogin(String user, String pwd, String roomId, String date,
                                         String start, String end, String reason, String extraMembers) {
        client.newCall(new Request.Builder().url(BASE_URL + "/login/publicKey").get().build())
                .enqueue(new Callback() {
                    @Override public void onFailure(Call call, IOException e) {
                        log("❌ 公钥获取失败: " + e.getMessage());
                        decrementPendingRequests();
                    }
                    @Override public void onResponse(Call call, Response response) throws IOException {
                        try {
                            JsonObject json = JsonParser.parseString(response.body().string()).getAsJsonObject();
                            String pk = json.getAsJsonObject("data").get("publicKey").getAsString();
                            String nc = json.getAsJsonObject("data").get("nonceStr").getAsString();
                            doLogin(user, pwd, pk, nc, roomId, date, start, end, reason, extraMembers, false);
                        } catch (Exception e) {
                            log("❌ 公钥解析失败: " + e.getMessage());
                            decrementPendingRequests();
                        }
                    }
                });
    }

    private void doLogin(String user, String pwd, String pk, String nc,
                         String roomId, String date, String start, String end,
                         String reason, String extraMembers, boolean loginOnlyMode) {
        try {
            String encPwd = rsaEncrypt(pwd + ";" + nc, pk);
            JsonObject body = new JsonObject();
            body.addProperty("logonName",   user);
            body.addProperty("password",    encPwd);
            body.addProperty("privacy",     true);
            body.addProperty("consoleType", 16);
            client.newCall(new Request.Builder().url(BASE_URL + "/login/user")
                    .post(RequestBody.create(body.toString(), MediaType.parse("application/json")))
                    .build()).enqueue(new Callback() {
                @Override public void onFailure(Call call, IOException e) {
                    log("❌ 登录请求失败: " + e.getMessage());
                    decrementPendingRequests();
                }
                @Override public void onResponse(Call call, Response response) throws IOException {
                    try {
                        JsonObject res = JsonParser.parseString(response.body().string()).getAsJsonObject();
                        if (res.get("code").getAsInt() == 0) {
                            String token = res.getAsJsonObject("data").get("token").getAsString();
                            long   accNo  = res.getAsJsonObject("data").get("accNo").getAsLong();
                            List<Long> members = buildMemberList(accNo, extraMembers);
                            if (loginOnlyMode) {
                                String membersStr = membersToStr(members);
                                mainHandler.post(() -> {
                                    cachedToken    = token;
                                    cachedAppAccNo = accNo;
                                    cachedMembers  = members;
                                    loginCompleted = true;
                                    if (stateManager != null)
                                        stateManager.saveCachedLogin(accountId, token, accNo, membersStr);
                                    log("✅ 登录成功 | 账号ID: " + accNo + " | token 与 Cookies 已持久化...");
                                    if (reservationPending) {
                                        log("⚡ 预约触发早于登录完成，现立即补发");
                                        reservationPending = false;
                                        doReserve();
                                    }
                                });
                            } else {
                                log("✅ 登录成功 | 账号ID: " + accNo);
                                List<TimeSegment> segs = getFilteredSegments(start, end);
                                mainHandler.post(() -> executeStep(
                                        token, accNo, members, segs, 0, roomId, date, reason, 0));
                            }
                        } else {
                            log("❌ 登录失败 code=" + res.get("code").getAsInt()
                                    + (res.has("message") ? " → " + res.get("message").getAsString() : ""));
                            decrementPendingRequests();
                        }
                    } catch (Exception e) {
                        log("❌ 登录响应解析失败: " + e.getMessage());
                        decrementPendingRequests();
                    }
                }
            });
        } catch (Exception e) {
            log("❌ RSA 加密失败: " + e.getMessage());
            decrementPendingRequests();
        }
    }

    public void performLoginOnly(String user, String pwd, String roomId, String date,
                                 String start, String end, String reason, String extraMembers) {
        cachedRoomId = roomId; cachedDate = date;
        cachedStart  = start;  cachedEnd  = end;  cachedReason = reason;
        List<TimeSegment> preview = getFilteredSegments(start, end);
        log("🔐 开始登录 | 账号: " + user
                + " | 日期: " + date
                + " | 分配时段（共" + preview.size() + "段）: " + segsToStr(preview));
        incrementPendingRequests();
        client.newCall(new Request.Builder().url(BASE_URL + "/login/publicKey").get().build())
                .enqueue(new Callback() {
                    @Override public void onFailure(Call call, IOException e) {
                        log("❌ 公钥获取失败: " + e.getMessage());
                        decrementPendingRequests();
                    }
                    @Override public void onResponse(Call call, Response response) throws IOException {
                        try {
                            JsonObject json = JsonParser.parseString(response.body().string()).getAsJsonObject();
                            String pk = json.getAsJsonObject("data").get("publicKey").getAsString();
                            String nc = json.getAsJsonObject("data").get("nonceStr").getAsString();
                            doLogin(user, pwd, pk, nc, roomId, date, start, end,
                                    reason, extraMembers, true);
                        } catch (Exception e) {
                            log("❌ 公钥解析失败: " + e.getMessage());
                            decrementPendingRequests();
                        }
                    }
                });
    }

    public void performReservationFromPersisted() {
        String token = stateManager.getCachedToken(accountId);
        long   accNo  = stateManager.getCachedAccNo(accountId);
        String memStr = stateManager.getCachedMembersStr(accountId);

        if (token == null || token.isEmpty()) {
            log("⚠️ 无持久化 token，尝试全流程执行作为备用...");
            log("❌ 请检查 LOGIN 闹钟是否正常执行");
            return;
        }

        List<Long> members = membersFromStr(memStr);
        log("🚀 从持久化 token 开始预约 | 账号ID: " + accNo
                + " | 时段: " + segsToStr(getFilteredSegments(cachedStart, cachedEnd)));

        cachedToken    = token;
        cachedAppAccNo = accNo;
        cachedMembers  = members;
        incrementPendingRequests();
        doReserve();
    }

    private void doReserve() {
        List<TimeSegment> segs = getFilteredSegments(cachedStart, cachedEnd);
        if (segs.isEmpty()) { log("⚠️ 无可用时段"); return; }
        executeStep(cachedToken, cachedAppAccNo, cachedMembers,
                segs, 0, cachedRoomId, cachedDate, cachedReason, 0);
    }

    private void executeStep(String token, long appAccNo, List<Long> members,
                             List<TimeSegment> segs, int idx, String rid,
                             String d, String r, int retryCount) {
        if (idx >= segs.size()) {
            log("🎊 全部时段提交完毕");
            decrementPendingRequests();
            return;
        }

        TimeSegment seg      = segs.get(idx);
        String      slotDesc = d + " " + seg.start + "~" + seg.end;
        String      retryTag = retryCount > 0
                ? "（第" + (retryCount + 1) + "次）"
                : "";
        log("📤 提交预约 | " + slotDesc + retryTag);

        JsonObject payload = new JsonObject();
        payload.addProperty("sysKind",       1);
        payload.addProperty("appAccNo",      appAccNo);
        payload.addProperty("resvBeginTime", d + " " + seg.start + ":00");
        payload.addProperty("resvEndTime",   d + " " + seg.end   + ":00");
        payload.addProperty("testName",      r);
        payload.addProperty("resvKind",      2);
        JsonArray mArr = new JsonArray(); for (Long m : members) mArr.add(m); payload.add("resvMember", mArr);
        JsonArray dArr = new JsonArray(); dArr.add(Long.parseLong(rid));      payload.add("resvDev", dArr);

        incrementPendingRequests();

        client.newCall(new Request.Builder()
                .url(BASE_URL + "/reserve")
                .header("Token", token)
                .post(RequestBody.create(payload.toString(), MediaType.parse("application/json")))
                .build()).enqueue(new Callback() {

            @Override public void onFailure(Call call, IOException e) {
                decrementPendingRequests();
                // 收到失败响应后，等待设定好的时间（重试 b ms 或 下一段 1000ms）再继续
                if (idx == 0 && retryCount < maxFirstSegmentRetries) {
                    log("🔄 网络失败，等待重试 | " + slotDesc + " (" + (retryCount + 1) + "/" + maxFirstSegmentRetries + ")");
                    mainHandler.postDelayed(() -> executeStep(token, appAccNo, members, segs, idx, rid, d, r, retryCount + 1), retryIntervalMs);
                } else {
                    log("⚠️ 网络失败，跳过 | " + slotDesc);
                    mainHandler.postDelayed(() -> executeStep(token, appAccNo, members, segs, idx + 1, rid, d, r, 0), 1000L);
                }
            }

            @Override public void onResponse(Call call, Response response) throws IOException {
                String raw = response.body().string();
                boolean isConflict = false;
                boolean isSuccess = false;
                String errMsg = "";

                try {
                    JsonObject resp = JsonParser.parseString(raw).getAsJsonObject();
                    int code = resp.get("code").getAsInt();
                    if (code == 0) {
                        isSuccess = true;
                    } else {
                        errMsg = "code=" + code;
                        if (resp.has("message") && !resp.get("message").isJsonNull())
                            errMsg = resp.get("message").getAsString();
                        else if (resp.has("msg") && !resp.get("msg").isJsonNull())
                            errMsg = resp.get("msg").getAsString();

                        if (errMsg.contains("当前设备正在被预约，请稍后重试")) {
                            isConflict = true;
                        }
                    }
                } catch (Exception ex) {
                    errMsg = "解析异常: " + raw;
                }
                decrementPendingRequests();

                // 优先处理被占冲突重试 (收到响应后等待 b ms)
                if (isConflict) {
                    log("🔄 被占冲突，等待重试当前时段 | " + slotDesc);
                    mainHandler.postDelayed(() -> executeStep(token, appAccNo, members, segs, idx, rid, d, r, retryCount + 1), retryIntervalMs);
                    return;
                }

                // 处理普通失败 (收到响应后等待 b ms)
                if (!isSuccess) {
                    if (idx == 0 && retryCount < maxFirstSegmentRetries) {
                        log("🔄 第一段失败，等待重试 | " + slotDesc + " (" + (retryCount + 1) + "/" + maxFirstSegmentRetries + ") 错误: " + errMsg);
                        mainHandler.postDelayed(() -> executeStep(token, appAccNo, members, segs, idx, rid, d, r, retryCount + 1), retryIntervalMs);
                        return;
                    } else {
                        log("❌ 预约失败 | " + slotDesc + " → " + errMsg);
                    }
                } else {
                    log("✅ 预约成功 | " + slotDesc);
                }

                // 收到响应后固定等待 1s (1000ms)，再开启下一段的预约请求
                mainHandler.postDelayed(() -> executeStep(token, appAccNo, members, segs, idx + 1, rid, d, r, 0), 1000L);
            }
        });
    }

    private List<TimeSegment> getFilteredSegments(String start, String end) {
        List<TimeSegment> all = prioritizeSegments(splitTime(start, end));
        if (segmentFilterStride <= 1) return all;
        List<TimeSegment> filtered = new ArrayList<>();
        for (int i = segmentFilterStart; i < all.size(); i += segmentFilterStride)
            filtered.add(all.get(i));
        return filtered;
    }

    private List<TimeSegment> prioritizeSegments(List<TimeSegment> orig) {
        List<TimeSegment> high = new ArrayList<>(), low = new ArrayList<>();
        for (TimeSegment seg : orig) {
            int sMin = Integer.parseInt(seg.start.split(":")[0]) * 60;
            if (sMin >= 12 * 60 && sMin < 20 * 60) high.add(seg);
            else low.add(seg);
        }
        high.addAll(low);
        return high;
    }

    private List<TimeSegment> splitTime(String s, String e) {
        List<TimeSegment> list = new ArrayList<>();
        try {
            int start = Integer.parseInt(s.split(":")[0]) * 60 + Integer.parseInt(s.split(":")[1]);
            int end   = Integer.parseInt(e.split(":")[0]) * 60 + Integer.parseInt(e.split(":")[1]);
            while (start < end) {
                int n = Math.min(start + 240, end);
                list.add(new TimeSegment(
                        String.format("%02d:%02d", start / 60, start % 60),
                        String.format("%02d:%02d", n    / 60, n    % 60)));
                start = n;
            }
        } catch (Exception ignored) {}
        return list;
    }

    private List<Long> buildMemberList(long selfAccNo, String extraMembers) {
        List<Long> list = new ArrayList<>();
        list.add(selfAccNo);
        if (extraMembers != null && !extraMembers.isEmpty()) {
            for (String id : extraMembers.split(",")) {
                try { list.add(Long.parseLong(id.trim())); } catch (Exception ignored) {}
            }
        }
        return list;
    }

    private String membersToStr(List<Long> members) {
        StringBuilder sb = new StringBuilder();
        for (Long m : members) { if (sb.length() > 0) sb.append(","); sb.append(m); }
        return sb.toString();
    }

    private List<Long> membersFromStr(String s) {
        List<Long> list = new ArrayList<>();
        if (s == null || s.isEmpty()) return list;
        for (String id : s.split(",")) {
            try { list.add(Long.parseLong(id.trim())); } catch (Exception ignored) {}
        }
        return list;
    }

    private String segsToStr(List<TimeSegment> segs) {
        StringBuilder sb = new StringBuilder();
        for (TimeSegment s : segs) { if (sb.length() > 0) sb.append(", "); sb.append(s.start).append("~").append(s.end); }
        return sb.length() == 0 ? "（无）" : sb.toString();
    }

    private synchronized void incrementPendingRequests() { pendingRequests++; }
    private synchronized void decrementPendingRequests() {
        pendingRequests--;
        if (pendingRequests <= 0 && completionListener != null)
            mainHandler.post(completionListener::onComplete);
    }

    private String rsaEncrypt(String msg, String key) throws Exception {
        byte[] bK = Base64.decode(
                key.replace("-----BEGIN PUBLIC KEY-----", "")
                        .replace("-----END PUBLIC KEY-----", "")
                        .replaceAll("\\s", ""), Base64.DEFAULT);
        PublicKey pK = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(bK));
        Cipher c = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        c.init(Cipher.ENCRYPT_MODE, pK);
        return Base64.encodeToString(c.doFinal(msg.getBytes()), Base64.NO_WRAP);
    }

    static class TimeSegment {
        String start, end;
        TimeSegment(String s, String e) { this.start = s; this.end = e; }
    }
}