package com.shi.remoteprinter;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 打印机自动发现：首选 Android NSD (mDNS)，兜底子网扫描。
 *
 * 网页版做不了这个：浏览器没有 mDNS 发现权限，也没有原始 socket 权限，
 * 只能靠用户手动填 IP。这是 APK 原生才能做的能力。
 *
 * 发现顺序：NSD (_uscan/_ipp/_http/_printer/_uscans) -> 子网扫描 (80/631 端口 + eSCL/网页探测)
 * -> 失败才显示手动填 IP。每次进扫描页 / 发起扫描前都重新发现一次。
 */
public class PrinterDiscovery {
    private static final String TAG = "PrinterDiscovery";

    public static final String[] SERVICE_TYPES = {
            "_uscan._tcp", "_ipp._tcp", "_http._tcp", "_printer._tcp", "_uscans._tcp"
    };
    /** NSD 发现超时：6 秒没找到 Epson 就转子网扫描 */
    public static final int NSD_TIMEOUT_MS = 6000;

    public static class DiscoveredPrinter {
        public final String ip;
        public final int port;
        public final String name;
        /** 来源：NSD / 子网扫描 / 手动 */
        public final String source;

        public DiscoveredPrinter(String ip, int port, String name, String source) {
            this.ip = ip;
            this.port = port;
            this.name = name;
            this.source = source;
        }

        @Override
        public String toString() {
            return name + " " + ip + ":" + port + " (" + source + ")";
        }
    }

    public interface Callback {
        void onFound(DiscoveredPrinter printer);

        void onNotFound(String reason);
    }

    /**
     * 开始一次完整发现。必须在主线程调用（NSD 要求主线程）。
     * 先 NSD，超时无果则自动转子网扫描，最后回调。
     */
    public static void discover(final Context context, final Callback callback) {
        final Handler mainHandler = new Handler(Looper.getMainLooper());
        final AtomicBoolean finished = new AtomicBoolean(false);
        final NsdManager nsdManager =
                (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        if (nsdManager == null) {
            // 没有 NSD 服务，直接走子网扫描
            runSubnetScanAsync(context, callback);
            return;
        }

        final List<NsdManager.DiscoveryListener> listeners =
                Collections.synchronizedList(new ArrayList<NsdManager.DiscoveryListener>());

        final Runnable stopAll = new Runnable() {
            @Override
            public void run() {
                for (NsdManager.DiscoveryListener l : listeners) {
                    try {
                        nsdManager.stopServiceDiscovery(l);
                    } catch (Exception e) {
                        // 未在发现中或已停止，忽略
                    }
                }
                listeners.clear();
            }
        };

        // 超时：NSD 没找到 -> 停掉发现，转子网扫描
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (finished.get()) return;
                stopAll.run();
                Log.i(TAG, "NSD 超时未找到 Epson，转子网扫描");
                runSubnetScanAsync(context, callback);
            }
        }, NSD_TIMEOUT_MS);

        for (final String type : SERVICE_TYPES) {
            final NsdManager.DiscoveryListener listener =
                    new NsdManager.DiscoveryListener() {
                        @Override
                        public void onDiscoveryStarted(String regType) {
                        }

                        @Override
                        public void onServiceFound(NsdServiceInfo serviceInfo) {
                            if (finished.get()) return;
                            nsdManager.resolveService(serviceInfo,
                                    new NsdManager.ResolveListener() {
                                        @Override
                                        public void onResolveFailed(
                                                NsdServiceInfo info, int errorCode) {
                                        }

                                        @Override
                                        public void onServiceResolved(NsdServiceInfo info) {
                                            if (finished.get()) return;
                                            String name = info.getServiceName();
                                            // 只认 Epson：名字含 EPSON/L315，或本身就是扫描服务 _uscan
                                            // （_http._tcp 会发现路由器等一堆设备，不能照单全收）
                                            boolean isEpson = looksLikeEpson(name)
                                                    || "_uscan._tcp".equals(type)
                                                    || "_uscans._tcp".equals(type);
                                            if (!isEpson) return;
                                            InetAddress host = info.getHost();
                                            String ip = host == null
                                                    ? null : host.getHostAddress();
                                            if (ip == null) return;
                                            if (!finished.compareAndSet(false, true)) return;
                                            mainHandler.removeCallbacksAndMessages(null);
                                            stopAll.run();
                                            Log.i(TAG, "NSD 发现: " + name + " " + ip
                                                    + ":" + info.getPort() + " (" + type + ")");
                                            DiscoveredPrinter p = new DiscoveredPrinter(
                                                    ip, info.getPort(),
                                                    name != null ? name : "Epson 打印机",
                                                    "NSD");
                                            callback.onFound(p);
                                        }
                                    });
                        }

                        @Override
                        public void onServiceLost(NsdServiceInfo serviceInfo) {
                        }

                        @Override
                        public void onDiscoveryStopped(String serviceType) {
                        }

                        @Override
                        public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                            Log.w(TAG, "NSD 启动失败 " + serviceType + " err=" + errorCode);
                        }

                        @Override
                        public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                        }
                    };
            listeners.add(listener);
            try {
                nsdManager.discoverServices(type,
                        NsdManager.PROTOCOL_DNS_SD, listener);
            } catch (Exception e) {
                Log.w(TAG, "discoverServices 异常 " + type, e);
            }
        }
    }

    /** 子网扫描放在后台线程跑，结果回主线程回调 */
    private static void runSubnetScanAsync(final Context context, final Callback callback) {
        final Handler mainHandler = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override
            public void run() {
                final DiscoveredPrinter found = scanSubnet(context);
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (found != null) {
                            callback.onFound(found);
                        } else {
                            callback.onNotFound("未发现打印机：请确认手机已连家里 Wi-Fi，"
                                    + "打印机已开机，或手动输入打印机 IP");
                        }
                    }
                });
            }
        }).start();
    }

    /**
     * 子网扫描：按手机当前 Wi-Fi 网段扫 1-254，80 端口探测 eSCL 接口
     * (/eSCL/ScannerStatus) 或打印机网页（含 EPSON 字样），631 端口也探测
     * （IPP 服务存在时作为辅助信号）。返回第一个确认是 Epson 打印机的地址。
     */
    public static DiscoveredPrinter scanSubnet(Context context) {
        String prefix = getSubnetPrefix(context);
        if (prefix == null) {
            Log.w(TAG, "拿不到 Wi-Fi 网段（未连 Wi-Fi？），跳过子网扫描");
            return null;
        }
        Log.i(TAG, "子网扫描网段: " + prefix + "1-254");
        ExecutorService pool = Executors.newFixedThreadPool(32);
        CompletionService<DiscoveredPrinter> cs =
                new ExecutorCompletionService<DiscoveredPrinter>(pool);
        int tasks = 0;
        for (int i = 1; i <= 254; i++) {
            final String host = prefix + i;
            tasks++;
            cs.submit(new java.util.concurrent.Callable<DiscoveredPrinter>() {
                @Override
                public DiscoveredPrinter call() {
                    return probeHost(host);
                }
            });
        }
        DiscoveredPrinter result = null;
        try {
            for (int i = 0; i < tasks; i++) {
                Future<DiscoveredPrinter> f = cs.poll(15, TimeUnit.SECONDS);
                if (f == null) break;
                DiscoveredPrinter p = f.get();
                if (p != null) {
                    result = p;
                    break;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "子网扫描异常", e);
        } finally {
            pool.shutdownNow();
        }
        return result;
    }

    /** 探测单个主机：80 端口 eSCL -> 80 端口打印机网页 -> 631 端口 IPP 存在性 */
    private static DiscoveredPrinter probeHost(String host) {
        // 1) eSCL 接口：最能确认是带扫描的 Epson 打印机
        if (EsclClient.probeEscl(host, 1000)) {
            Log.i(TAG, "子网扫描命中 eSCL: " + host);
            return new DiscoveredPrinter(host, 80, "Epson 打印机", "子网扫描");
        }
        // 2) 打印机网页：Epson Web Config 含 EPSON 字样
        if (EsclClient.probeWebPage(host, 1000)) {
            Log.i(TAG, "子网扫描命中打印机网页: " + host);
            return new DiscoveredPrinter(host, 80, "Epson 打印机", "子网扫描");
        }
        // 3) 631 端口：IPP 服务开着，辅助信号；仍需 80 端口确认才算数，
        //    这里只记录，不单独采纳（避免把别的 IPP 设备当成这台打印机）
        if (EsclClient.probePort(host, 631, 250)) {
            Log.d(TAG, "主机 " + host + " 631 端口开着，但 80 端口未确认是 Epson，跳过");
        }
        return null;
    }

    /** 取手机当前 Wi-Fi 的 /24 网段前缀，如 "192.168.5."；未连 Wi-Fi 返回 null */
    public static String getSubnetPrefix(Context context) {
        try {
            WifiManager wm = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                WifiInfo info = wm.getConnectionInfo();
                int ip = info == null ? 0 : info.getIpAddress();
                if (ip != 0) {
                    int b0 = ip & 0xff;
                    int b1 = (ip >> 8) & 0xff;
                    int b2 = (ip >> 16) & 0xff;
                    return b0 + "." + b1 + "." + b2 + ".";
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "读 Wi-Fi IP 失败", e);
        }
        // 兜底：枚举网卡，找局域网 IPv4
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a.isLoopbackAddress()) continue;
                    String s = a.getHostAddress();
                    if (s == null || s.contains(":")) continue;
                    if (a.isSiteLocalAddress()) {
                        int lastDot = s.lastIndexOf('.');
                        if (lastDot > 0) return s.substring(0, lastDot + 1);
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "枚举网卡失败", e);
        }
        return null;
    }

    /** 名字里是否像 Epson（NSD 服务名或 TXT 属性里常带 EPSON / L315x） */
    public static boolean looksLikeEpson(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(java.util.Locale.US);
        return n.contains("epson") || n.contains("l315");
    }
}
