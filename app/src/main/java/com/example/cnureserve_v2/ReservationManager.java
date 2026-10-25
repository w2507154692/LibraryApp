package com.example.cnureserve_v2;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
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
    private String     cachedRoomIds = "", cachedDate, cachedStart, cachedEnd, cachedReason;

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
        cachedRoomIds = normalizeRoomIds(roomId); cachedDate = date;
        cachedStart  = start;  cachedEnd  = end;  cachedReason = reason;
    }

    private void log(String msg) {
        if (stateManager != null) stateManager.appendLog(accountId, msg);
        mainHandler.post(() -> listener.onLog(msg));
    }

    public void start(String user, String pwd, String roomId, String date,
                      String start, String end, String reason, String extraMembers) {
        // 手动执行和定时回退都会直接进入 start()。同步保存预约参数，
        // 否则 cachedRoomIds 仍为空，登录成功后 executeRoom() 会误判为没有研讨间。
        setReservationParams(roomId, date, start, end, reason);
        if (parseRoomIds(cachedRoomIds).isEmpty()) {
            log("❌ 未选择有效研讨间，任务未启动");
            if (completionListener != null) mainHandler.post(completionListener::onComplete);
            return;
        }
        List<TimeSegment> segs = getFilteredSegments(start, end);
        log("🚀 开始任务 | 账号: " + user
                + " | 日期: " + date
                + " | 研讨间顺序: " + cachedRoomIds
                + " | 分配时段（共" + segs.size() + "段）: " + segsToStr(segs));
        incrementPendingRequests();
        fetchPublicKeyThenLogin(user, pwd, roomId, date, start, end, reason, extraMembers);
    }

    private void fetchPublicKeyThenLogin(String user, String pwd, String roomId, String date,
                                         String start, String end, String reason, String extraMembers) {
        long queuedAt = SystemClock.elapsedRealtime();
        log("登录公钥 HTTP 请求入队");
        client.newCall(new Request.Builder().url(BASE_URL + "/login/publicKey").get().build())
                .enqueue(new Callback() {
                    @Override public void onFailure(Call call, IOException e) {
                        log("登录公钥 HTTP 失败，耗时=" + (SystemClock.elapsedRealtime() - queuedAt) + "ms");
                        log("❌ 公钥获取失败: " + e.getMessage());
                        decrementPendingRequests();
                    }
                    @Override public void onResponse(Call call, Response response) throws IOException {
                        log("登录公钥 HTTP 响应，耗时=" + (SystemClock.elapsedRealtime() - queuedAt) + "ms，状态=" + response.code());
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
            long queuedAt = SystemClock.elapsedRealtime();
            log("登录 HTTP 请求入队");
            client.newCall(new Request.Builder().url(BASE_URL + "/login/user")
                    .post(RequestBody.create(body.toString(), MediaType.parse("application/json")))
                    .build()).enqueue(new Callback() {
                @Override public void onFailure(Call call, IOException e) {
                    log("登录 HTTP 失败，耗时=" + (SystemClock.elapsedRealtime() - queuedAt) + "ms");
                    log("❌ 登录请求失败: " + e.getMessage());
                    decrementPendingRequests();
                }
                @Override public void onResponse(Call call, Response response) throws IOException {
                    log("登录 HTTP 响应，耗时=" + (SystemClock.elapsedRealtime() - queuedAt) + "ms，状态=" + response.code());
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
                                    decrementPendingRequests();
                                    if (reservationPending) {
                                        log("⚡ 预约触发早于登录完成，现立即补发");
                                        reservationPending = false;
                                        doReserve();
                                    }
                                });
                            } else {
                                log("✅ 登录成功 | 账号ID: " + accNo);
                                List<TimeSegment> segs = getFilteredSegments(start, end);
                                mainHandler.post(() -> executeRoom(
                                        token, accNo, members, segs, 0, 0,
                                        new ArrayList<>(), parseRoomIds(cachedRoomIds), date, reason, 0));
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
        cachedRoomIds = normalizeRoomIds(roomId); cachedDate = date;
        cachedStart  = start;  cachedEnd  = end;  cachedReason = reason;
        List<TimeSegment> preview = getFilteredSegments(start, end);
        log("🔐 开始登录 | 账号: " + user
                + " | 日期: " + date
                + " | 研讨间顺序: " + cachedRoomIds
                + " | 分配时段（共" + preview.size() + "段）: " + segsToStr(preview));
        incrementPendingRequests();
        long queuedAt = SystemClock.elapsedRealtime();
        log("预登录公钥 HTTP 请求入队");
        client.newCall(new Request.Builder().url(BASE_URL + "/login/publicKey").get().build())
                .enqueue(new Callback() {
                    @Override public void onFailure(Call call, IOException e) {
                        log("预登录公钥 HTTP 失败，耗时=" + (SystemClock.elapsedRealtime() - queuedAt) + "ms");
                        log("❌ 公钥获取失败: " + e.getMessage());
                        decrementPendingRequests();
                    }
                    @Override public void onResponse(Call call, Response response) throws IOException {
                        log("预登录公钥 HTTP 响应，耗时=" + (SystemClock.elapsedRealtime() - queuedAt) + "ms，状态=" + response.code());
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
        if (segs.isEmpty()) { log("⚠️ 无可用时段"); decrementPendingRequests(); return; }
        if (parseRoomIds(cachedRoomIds).isEmpty()) {
            log("❌ 未选择有效研讨间");
            decrementPendingRequests();
            return;
        }
        executeRoom(cachedToken, cachedAppAccNo, cachedMembers, segs,
                0, 0, new ArrayList<>(), parseRoomIds(cachedRoomIds), cachedDate, cachedReason, 0);
    }

    private void executeRoom(String token, long appAccNo, List<Long> members,
                             List<TimeSegment> segs, int roomIndex, int segmentIndex,
                             List<ReservationRecord> successful, List<String> rooms,
                             String d, String r, int retryCount) {
        if (roomIndex >= rooms.size()) {
            log("❌ 所有研讨间均未完成全部时段");
            decrementPendingRequests();
            return;
        }
        if (segmentIndex >= segs.size()) {
            log("🎊 研讨间 " + rooms.get(roomIndex) + " 全部时段预约成功");
            decrementPendingRequests();
            return;
        }

        String rid = rooms.get(roomIndex);
        TimeSegment seg = segs.get(segmentIndex);
        String slotDesc = d + " " + seg.start + "~" + seg.end;
        log("📤 提交预约 | 研讨间 " + rid + " | " + slotDesc
                + (retryCount > 0 ? "（第" + (retryCount + 1) + "次）" : ""));

        JsonObject payload = new JsonObject();
        payload.addProperty("sysKind",       1);
        payload.addProperty("appAccNo",      appAccNo);
        payload.addProperty("resvBeginTime", d + " " + seg.start + ":00");
        payload.addProperty("resvEndTime",   d + " " + seg.end   + ":00");
        payload.addProperty("testName",      r);
        payload.addProperty("resvKind",      2);
        JsonArray mArr = new JsonArray(); for (Long m : members) mArr.add(m); payload.add("resvMember", mArr);
        JsonArray dArr = new JsonArray();
        try { dArr.add(Long.parseLong(rid)); }
        catch (NumberFormatException e) {
            log("❌ 研讨间 ID 无效: " + rid);
            failRoom(token, appAccNo, members, segs, roomIndex, successful, rooms, d, r);
            return;
        }
        payload.add("resvDev", dArr);

        incrementPendingRequests();
        long queuedAt = SystemClock.elapsedRealtime();
        log("预约 HTTP 请求入队，研讨间=" + rid + "，时段=" + slotDesc);
        client.newCall(new Request.Builder()
                .url(BASE_URL + "/reserve")
                .header("Token", token)
                .post(RequestBody.create(payload.toString(), MediaType.parse("application/json")))
                .build()).enqueue(new Callback() {

            @Override public void onFailure(Call call, IOException e) {
                log("预约 HTTP 失败，耗时=" + (SystemClock.elapsedRealtime() - queuedAt) + "ms");
                decrementPendingRequests();
                if (segmentIndex == 0 && retryCount < maxFirstSegmentRetries) {
                    log("🔄 网络失败，等待重试 | " + slotDesc + " (" + (retryCount + 1) + "/" + maxFirstSegmentRetries + ")");
                    postRetry(token, appAccNo, members, segs, roomIndex, segmentIndex,
                            successful, rooms, d, r, retryCount);
                } else {
                    log("❌ 研讨间 " + rid + " 失败，开始撤销已成功时段");
                    failRoom(token, appAccNo, members, segs, roomIndex, successful, rooms, d, r);
                }
            }

            @Override public void onResponse(Call call, Response response) throws IOException {
                log("预约 HTTP 响应，耗时=" + (SystemClock.elapsedRealtime() - queuedAt) + "ms，状态=" + response.code());
                String raw = response.body().string();
                boolean isSuccess = false;
                String errMsg = "";
                String uuid = null;

                try {
                    JsonObject resp = JsonParser.parseString(raw).getAsJsonObject();
                    int code = resp.get("code").getAsInt();
                    if (code == 0) {
                        isSuccess = true;
                        uuid = findReservationId(resp);
                    } else {
                        errMsg = "code=" + code;
                        if (resp.has("message") && !resp.get("message").isJsonNull())
                            errMsg = resp.get("message").getAsString();
                        else if (resp.has("msg") && !resp.get("msg").isJsonNull())
                            errMsg = resp.get("msg").getAsString();

                    }
                } catch (Exception ex) {
                    errMsg = "解析异常: " + raw;
                }
                decrementPendingRequests();

                if (!isSuccess) {
                    if (segmentIndex == 0 && retryCount < maxFirstSegmentRetries) {
                        log("🔄 第一段失败，等待重试 | " + slotDesc + " (" + (retryCount + 1) + "/" + maxFirstSegmentRetries + ") 错误: " + errMsg);
                        postRetry(token, appAccNo, members, segs, roomIndex, segmentIndex,
                                successful, rooms, d, r, retryCount);
                        return;
                    } else {
                        log("❌ 预约失败 | 研讨间 " + rid + " | " + slotDesc + " → " + errMsg);
                        failRoom(token, appAccNo, members, segs, roomIndex, successful, rooms, d, r);
                        return;
                    }
                } else {
                    successful.add(new ReservationRecord(uuid, slotDesc));
                    log("✅ 预约成功 | 研讨间 " + rid + " | " + slotDesc
                            + (uuid == null ? "（响应未返回uuid，回滚时无法定位）" : ""));
                }

                mainHandler.postDelayed(() -> executeRoom(token, appAccNo, members, segs,
                        roomIndex, segmentIndex + 1, successful, rooms, d, r, 0), 1000L);
            }
        });
    }

    private void postRetry(String token, long appAccNo, List<Long> members, List<TimeSegment> segs,
                            int roomIndex, int segmentIndex, List<ReservationRecord> successful,
                            List<String> rooms, String d, String r, int retryCount) {
        mainHandler.postDelayed(() -> executeRoom(token, appAccNo, members, segs,
                roomIndex, segmentIndex, successful, rooms, d, r, retryCount + 1), retryIntervalMs);
    }

    private void failRoom(String token, long appAccNo, List<Long> members, List<TimeSegment> segs,
                          int roomIndex, List<ReservationRecord> successful, List<String> rooms,
                          String d, String r) {
        String rid = rooms.get(roomIndex);
        if (successful.isEmpty()) {
            log("↪️ 研讨间 " + rid + " 没有成功时段，切换下一个");
            mainHandler.post(() -> executeRoom(token, appAccNo, members, segs, roomIndex + 1,
                    0, new ArrayList<>(), rooms, d, r, 0));
            return;
        }
        log("↩️ 撤销研讨间 " + rid + " 已成功的 " + successful.size() + " 个时段");
        cancelSuccessful(token, successful, 0, () -> executeRoom(token, appAccNo, members, segs,
                roomIndex + 1, 0, new ArrayList<>(), rooms, d, r, 0));
    }

    private void cancelSuccessful(String token, List<ReservationRecord> records, int index, Runnable next) {
        if (index >= records.size()) { next.run(); return; }
        ReservationRecord record = records.get(index);
        if (record.uuid == null || record.uuid.isEmpty()) {
            log("⚠️ 无 uuid，无法撤销时段 " + record.slotDesc);
            cancelSuccessful(token, records, index + 1, next);
            return;
        }
        JsonObject body = new JsonObject(); body.addProperty("uuid", record.uuid);
        incrementPendingRequests();
        long queuedAt = SystemClock.elapsedRealtime();
        log("撤销预约请求入队 | " + record.slotDesc);
        client.newCall(new Request.Builder().url(BASE_URL + "/reserve/delete")
                .header("Token", token)
                .post(RequestBody.create(body.toString(), MediaType.parse("application/json")))
                .build()).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                log("撤销预约 HTTP 失败，耗时=" + (SystemClock.elapsedRealtime() - queuedAt) + "ms");
                decrementPendingRequests();
                cancelSuccessful(token, records, index + 1, next);
            }
            @Override public void onResponse(Call call, Response response) throws IOException {
                String raw = response.body() == null ? "" : response.body().string();
                log("撤销预约 HTTP 响应，耗时=" + (SystemClock.elapsedRealtime() - queuedAt)
                        + "ms，状态=" + response.code());
                try {
                    JsonObject result = JsonParser.parseString(raw).getAsJsonObject();
                    if (!result.has("code") || result.get("code").getAsInt() != 0)
                        log("⚠️ 撤销失败 | " + record.slotDesc);
                } catch (Exception e) { log("⚠️ 撤销响应解析失败 | " + record.slotDesc); }
                response.close();
                decrementPendingRequests();
                cancelSuccessful(token, records, index + 1, next);
            }
        });
    }

    private String findReservationId(JsonObject resp) {
        for (String key : new String[]{"uuid", "resvUuid", "resvId", "id"})
            if (resp.has(key) && !resp.get(key).isJsonNull()) return resp.get(key).getAsString();
        if (resp.has("data") && resp.get("data").isJsonObject()) {
            JsonObject data = resp.getAsJsonObject("data");
            for (String key : new String[]{"uuid", "resvUuid", "resvId", "id"})
                if (data.has(key) && !data.get(key).isJsonNull()) return data.get(key).getAsString();
        }
        if (resp.has("data") && resp.get("data").isJsonArray()) {
            for (JsonElement item : resp.getAsJsonArray("data")) {
                if (item.isJsonObject()) {
                    String id = findReservationId(item.getAsJsonObject());
                    if (id != null) return id;
                }
            }
        }
        if (resp.has("data") && resp.get("data").isJsonPrimitive())
            return resp.get("data").getAsString();
        return null;
    }

    private static class ReservationRecord {
        final String uuid, slotDesc;
        ReservationRecord(String uuid, String slotDesc) { this.uuid = uuid; this.slotDesc = slotDesc; }
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

    private List<String> parseRoomIds(String value) {
        List<String> result = new ArrayList<>();
        if (value == null) return result;
        for (String item : value.split(",")) {
            String id = item.trim();
            if (!id.isEmpty() && !result.contains(id)) result.add(id);
        }
        return result;
    }

    private String normalizeRoomIds(String value) {
        StringBuilder out = new StringBuilder();
        for (String id : parseRoomIds(value)) {
            if (out.length() > 0) out.append(",");
            out.append(id);
        }
        return out.toString();
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
