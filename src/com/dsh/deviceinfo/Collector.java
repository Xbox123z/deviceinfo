package com.dsh.deviceinfo;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.os.SystemClock;
import android.telephony.TelephonyManager;
import android.util.DisplayMetrics;
import android.util.Size;
import android.view.Display;
import android.view.WindowManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Method;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * 设备信息采集器：纯原生 API + 少量 /proc /sys 读取。
 * 每个分区独立 try/catch，任何 API 缺失/无权限都只影响该分区，不会让 App 闪退。
 */
public final class Collector {

    public static final String NA = "—";

    private Collector() {}

    /** 分区标题 → (标签 → 值)。顺序即展示顺序。 */
    public static LinkedHashMap<String, LinkedHashMap<String, String>> collect(Context ctx) {
        LinkedHashMap<String, LinkedHashMap<String, String>> out = new LinkedHashMap<>();
        add(out, "设备", 1, ctx);
        add(out, "CPU", 2, ctx);
        add(out, "内存", 3, ctx);
        add(out, "存储", 4, ctx);
        add(out, "屏幕", 5, ctx);
        add(out, "电池", 6, ctx);
        add(out, "网络", 7, ctx);
        add(out, "传感器", 8, ctx);
        add(out, "相机", 9, ctx);
        add(out, "系统", 10, ctx);
        return out;
    }

    private static void add(LinkedHashMap<String, LinkedHashMap<String, String>> out,
                            String title, int kind, Context ctx) {
        try {
            LinkedHashMap<String, String> m;
            switch (kind) {
                case 1:  m = device();      break;
                case 2:  m = cpu();         break;
                case 3:  m = memory(ctx);   break;
                case 4:  m = storage();     break;
                case 5:  m = screen(ctx);   break;
                case 6:  m = battery(ctx);  break;
                case 7:  m = network(ctx);  break;
                case 8:  m = sensors(ctx);  break;
                case 9:  m = camera(ctx);   break;
                default: m = system();      break;
            }
            out.put(title, m);
        } catch (Throwable t) {
            LinkedHashMap<String, String> e = new LinkedHashMap<>();
            e.put("采集失败", t.getClass().getSimpleName() + "：" + String.valueOf(t.getMessage()));
            out.put(title, e);
        }
    }

    // ------------------------------------------------------------ 工具
    static String s(Object o) { return o == null ? NA : String.valueOf(o); }

    static String round2(double v) { return String.format(Locale.US, "%.2f", v); }

    static String fmtBytes(long b) {
        if (b <= 0) return NA;
        double gb = b / 1073741824.0;
        if (gb >= 1.0) return String.format(Locale.US, "%.2f GB", gb);
        return String.format(Locale.US, "%.0f MB", b / 1048576.0);
    }

    static String fmtDuration(long ms) {
        long sec = ms / 1000L;
        long d = sec / 86400L, h = (sec % 86400L) / 3600L, mnt = (sec % 3600L) / 60L;
        StringBuilder sb = new StringBuilder();
        if (d > 0) sb.append(d).append(" 天 ");
        if (d > 0 || h > 0) sb.append(h).append(" 小时 ");
        sb.append(mnt).append(" 分钟");
        return sb.toString();
    }

    static String readLine(String path) {
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(path));
            String l = r.readLine();
            return l == null ? null : l.trim();
        } catch (Throwable t) {
            return null;
        } finally {
            try { if (r != null) r.close(); } catch (Throwable ignored) {}
        }
    }

    static List<String> readLines(String path) {
        List<String> out = new ArrayList<>();
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(path));
            String l;
            while ((l = r.readLine()) != null) out.add(l);
        } catch (Throwable ignored) {
        } finally {
            try { if (r != null) r.close(); } catch (Throwable ignored) {}
        }
        return out;
    }

    /** 读 /proc/meminfo 的某个键，返回字节数；读不到返回 -1 */
    static long meminfoBytes(String key) {
        for (String line : readLines("/proc/meminfo")) {
            if (line.startsWith(key)) {
                String[] p = line.split("\\s+");
                if (p.length >= 2) {
                    try { return Long.parseLong(p[1]) * 1024L; } catch (Throwable ignored) {}
                }
            }
        }
        return -1;
    }

    static String sysProp(String key) {
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            Method g = c.getMethod("get", String.class);
            Object v = g.invoke(null, key);
            return v == null ? null : String.valueOf(v);
        } catch (Throwable t) {
            return null;
        }
    }

    static String trunc(String v, int n) {
        if (v == null) return NA;
        return v.length() <= n ? v : v.substring(0, n - 1) + "…";
    }

    static String joinList(String[] arr) {
        if (arr == null || arr.length == 0) return NA;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) sb.append(" / ");
            sb.append(arr[i]);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ 1 设备
    static LinkedHashMap<String, String> device() {
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        m.put("品牌 / 制造商", s(Build.BRAND) + " / " + s(Build.MANUFACTURER));
        m.put("型号", s(Build.MODEL));
        m.put("设备代号", s(Build.DEVICE));
        m.put("产品 / 主板", s(Build.PRODUCT) + " / " + s(Build.BOARD));
        m.put("构建指纹", s(Build.FINGERPRINT));
        m.put("Android 版本", s(Build.VERSION.RELEASE));
        m.put("API 级别", Build.VERSION.SDK_INT + "（" + s(Build.VERSION.CODENAME) + "）");
        if (Build.VERSION.SDK_INT >= 23) m.put("安全补丁", s(Build.VERSION.SECURITY_PATCH));
        m.put("Build ID", s(Build.ID));
        m.put("构建类型 / 标签", s(Build.TYPE) + " / " + s(Build.TAGS));
        return m;
    }

    // ------------------------------------------------------------ 2 CPU
    static LinkedHashMap<String, String> cpu() {
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        String abi;
        if (Build.VERSION.SDK_INT >= 21 && Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0) {
            abi = Build.SUPPORTED_ABIS[0];
        } else {
            abi = s(Build.CPU_ABI);
        }
        m.put("架构 ABI", abi);
        if (Build.VERSION.SDK_INT >= 21) m.put("支持的 ABI", joinList(Build.SUPPORTED_ABIS));
        m.put("硬件", s(Build.HARDWARE));

        int sysCores = 0;
        File dir = new File("/sys/devices/system/cpu/");
        String[] ls = dir.list();
        if (ls != null) {
            for (String n : ls) {
                if (n.matches("cpu\\d+")) sysCores++;
            }
        }
        int rtCores = Runtime.getRuntime().availableProcessors();
        m.put("核心数", (sysCores > 0 ? sysCores : rtCores) + " 核（Runtime: " + rtCores + "）");

        // /proc/cpuinfo 的 Hardware 行
        String hw = null;
        for (String line : readLines("/proc/cpuinfo")) {
            int idx = line.indexOf(':');
            if (idx > 0) {
                String k = line.substring(0, idx).trim();
                if ("Hardware".equals(k) || "model name".equals(k)) {
                    hw = line.substring(idx + 1).trim();
                    if ("Hardware".equals(k)) break;
                }
            }
        }
        if (hw != null) m.put("CPU 硬件名", trunc(hw, 60));

        int cores = sysCores > 0 ? sysCores : Math.min(rtCores, 16);
        for (int i = 0; i < cores; i++) {
            String base = "/sys/devices/system/cpu/cpu" + i + "/cpufreq/";
            String cur = readLine(base + "scaling_cur_freq");
            String max = readLine(base + "scaling_max_freq");
            String val;
            if (cur != null) {
                val = mhz(cur) + (max != null ? "  （上限 " + mhz(max) + "）" : "");
            } else {
                val = "不可读（内核未开放 / 权限限制）";
            }
            m.put("CPU" + i, val);
        }
        return m;
    }

    /** kHz 字符串 →  GHz/MHz */
    static String mhz(String khz) {
        try {
            long v = Long.parseLong(khz.trim());
            double m = v / 1000.0;
            if (m >= 1000) return String.format(Locale.US, "%.2f GHz", m / 1000.0);
            return String.format(Locale.US, "%.0f MHz", m);
        } catch (Throwable t) {
            return khz;
        }
    }

    // ------------------------------------------------------------ 3 内存
    static LinkedHashMap<String, String> memory(Context ctx) {
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        long total = meminfoBytes("MemTotal");
        long avail = meminfoBytes("MemAvailable");
        long free = meminfoBytes("MemFree");
        long usable = avail > 0 ? avail : free;
        m.put("物理内存总量", fmtBytes(total));
        m.put("当前可用", fmtBytes(usable));
        if (total > 0 && usable > 0) {
            m.put("已用", fmtBytes(total - usable) + "（" + Math.round((total - usable) * 100.0 / total) + "%）");
        }
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null) {
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            m.put("系统可用内存", fmtBytes(mi.availMem));
            m.put("低内存阈值", fmtBytes(mi.threshold));
            m.put("低内存状态", mi.lowMemory ? "是" : "否");
        }
        Runtime r = Runtime.getRuntime();
        m.put("Java 堆上限", fmtBytes(r.maxMemory()));
        m.put("Java 堆已分配", fmtBytes(r.totalMemory()));
        m.put("Java 堆已用", fmtBytes(r.totalMemory() - r.freeMemory()));
        return m;
    }

    // ------------------------------------------------------------ 4 存储
    static LinkedHashMap<String, String> storage() {
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        try {
            StatFs d = new StatFs(Environment.getDataDirectory().getPath());
            long t = d.getTotalBytes(), a = d.getAvailableBytes();
            m.put("内部存储总容量", fmtBytes(t));
            m.put("内部存储可用", fmtBytes(a) + (t > 0 ? "（" + Math.round(a * 100.0 / t) + "% 可用）" : ""));
            m.put("内部存储已用", fmtBytes(t - a));
        } catch (Throwable t) {
            m.put("内部存储", "不可读：" + t.getClass().getSimpleName());
        }
        try {
            File ext = Environment.getExternalStorageDirectory();
            StatFs e = new StatFs(ext.getPath());
            long t = e.getTotalBytes(), a = e.getAvailableBytes();
            m.put("外部存储总容量", fmtBytes(t));
            m.put("外部存储可用", fmtBytes(a));
            m.put("外部存储路径", ext.getAbsolutePath());
        } catch (Throwable t) {
            m.put("外部存储", "不可读：" + t.getClass().getSimpleName());
        }
        try {
            m.put("是否可移动", Environment.isExternalStorageRemovable() ? "是（可插拔）" : "否（内置）");
        } catch (Throwable ignored) {}
        m.put("数据目录", String.valueOf(Environment.getDataDirectory()));
        return m;
    }

    // ------------------------------------------------------------ 5 屏幕
    @SuppressWarnings("deprecation")
    static LinkedHashMap<String, String> screen(Context ctx) {
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) {
            m.put("屏幕", "WindowManager 不可用");
            return m;
        }
        Display d = wm.getDefaultDisplay();
        DisplayMetrics real = new DisplayMetrics();
        try {
            d.getRealMetrics(real);
            m.put("分辨率（真实）", real.widthPixels + " × " + real.heightPixels);
        } catch (Throwable ignored) {}
        DisplayMetrics dm = new DisplayMetrics();
        try {
            d.getMetrics(dm);
            m.put("分辨率（可用）", dm.widthPixels + " × " + dm.heightPixels);
        } catch (Throwable ignored) {}
        DisplayMetrics use = real.widthPixels > 0 ? real : dm;
        m.put("密度", use.densityDpi + " dpi（x" + round2(use.density) + "）");
        m.put("物理 DPI", String.format(Locale.US, "x %.1f / y %.1f", use.xdpi, use.ydpi));
        try {
            m.put("刷新率", round2(d.getRefreshRate()) + " Hz");
        } catch (Throwable ignored) {}
        if (Build.VERSION.SDK_INT >= 23) {
            try {
                Display.Mode[] modes = d.getSupportedModes();
                if (modes != null && modes.length > 0) {
                    StringBuilder sb = new StringBuilder();
                    int n = 0;
                    for (Display.Mode mo : modes) {
                        if (n++ >= 6) { sb.append(" …"); break; }
                        if (n > 1) sb.append("\n");
                        sb.append(mo.getPhysicalWidth()).append("×").append(mo.getPhysicalHeight())
                          .append(" @ ").append(round2(mo.getRefreshRate())).append("Hz");
                    }
                    m.put("支持的显示模式", sb.toString());
                }
            } catch (Throwable ignored) {}
        }
        if (Build.VERSION.SDK_INT >= 24) {
            try {
                Display.HdrCapabilities hc = d.getHdrCapabilities();
                int[] types = hc != null ? hc.getSupportedHdrTypes() : null;
                m.put("HDR 能力", types == null || types.length == 0 ? "不支持 HDR" : hdrNames(types));
                m.put("当前是否 HDR", d.isHdr() ? "是" : "否");
                if (hc != null) {
                    m.put("最大亮度", hc.getDesiredMaxLuminance() > 0
                            ? String.format(Locale.US, "%.0f nits", hc.getDesiredMaxLuminance()) : NA);
                    m.put("平均亮度", hc.getDesiredMaxAverageLuminance() > 0
                            ? String.format(Locale.US, "%.0f nits", hc.getDesiredMaxAverageLuminance()) : NA);
                }
            } catch (Throwable ignored) {}
        }
        return m;
    }

    static String hdrNames(int[] types) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < types.length; i++) {
            if (i > 0) sb.append(" / ");
            switch (types[i]) {
                case 1: sb.append("Dolby Vision"); break;
                case 2: sb.append("HDR10"); break;
                case 3: sb.append("HLG"); break;
                case 4: sb.append("HDR10+"); break;
                default: sb.append("类型").append(types[i]);
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ 6 电池
    static LinkedHashMap<String, String> battery(Context ctx) {
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        Intent it = null;
        try {
            it = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        } catch (Throwable ignored) {}
        int level = it != null ? it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) : -1;
        int scale = it != null ? it.getIntExtra(BatteryManager.EXTRA_SCALE, -1) : -1;
        if (level >= 0 && scale > 0) {
            m.put("电量", Math.round(level * 100f / scale) + " %");
        } else {
            m.put("电量", NA);
        }
        if (it != null) {
            int status = it.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            String st;
            switch (status) {
                case BatteryManager.BATTERY_STATUS_CHARGING:     st = "充电中"; break;
                case BatteryManager.BATTERY_STATUS_DISCHARGING:  st = "放电中"; break;
                case BatteryManager.BATTERY_STATUS_FULL:         st = "已充满"; break;
                case BatteryManager.BATTERY_STATUS_NOT_CHARGING: st = "未充电"; break;
                default: st = "未知";
            }
            m.put("状态", st);
            int plug = it.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
            String pt;
            switch (plug) {
                case BatteryManager.BATTERY_PLUGGED_AC:       pt = "交流电源（充电器）"; break;
                case BatteryManager.BATTERY_PLUGGED_USB:      pt = "USB 充电"; break;
                case BatteryManager.BATTERY_PLUGGED_WIRELESS:  pt = "无线充电"; break;
                case 0:                                       pt = "未接电源"; break;
                default:                                      pt = plug > 0 ? "充电类型代码 " + plug : "未知";
            }
            m.put("充电类型", pt);
            int temp = it.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
            if (temp != Integer.MIN_VALUE) m.put("温度", String.format(Locale.US, "%.1f ℃", temp / 10.0));
            int volt = it.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);
            if (volt > 0) m.put("电压", volt + " mV（" + String.format(Locale.US, "%.2f V", volt / 1000.0) + "）");
            int health = it.getIntExtra(BatteryManager.EXTRA_HEALTH, -1);
            String ht;
            switch (health) {
                case BatteryManager.BATTERY_HEALTH_GOOD:          ht = "良好"; break;
                case BatteryManager.BATTERY_HEALTH_OVERHEAT:      ht = "过热"; break;
                case BatteryManager.BATTERY_HEALTH_DEAD:          ht = "已损坏"; break;
                case BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE:  ht = "电压过高"; break;
                case BatteryManager.BATTERY_HEALTH_COLD:          ht = "过冷"; break;
                case BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE: ht = "故障"; break;
                default: ht = "未知";
            }
            m.put("健康度", ht);
            String tech = it.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY);
            if (tech != null) m.put("电池技术", tech);
        }
        try {
            BatteryManager bm = (BatteryManager) ctx.getSystemService(Context.BATTERY_SERVICE);
            if (bm != null) {
                int cap = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
                if (cap >= 0 && cap <= 100 && !m.containsKey("电量")) m.put("电量", cap + " %");
                int counter = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
                if (counter > 0) m.put("剩余电荷", (counter / 1000) + " mAh");
                int cur = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
                if (cur != 0) m.put("瞬时电流", String.format(Locale.US, "%.0f mA", cur / 1000.0));
            }
        } catch (Throwable ignored) {}
        return m;
    }

    // ------------------------------------------------------------ 7 网络
    @SuppressWarnings("deprecation")
    static LinkedHashMap<String, String> network(Context ctx) {
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo ni = null;
        NetworkCapabilities nc = null;
        try {
            ni = cm != null ? cm.getActiveNetworkInfo() : null;
        } catch (Throwable ignored) {}
        if (cm != null && Build.VERSION.SDK_INT >= 23) {
            try {
                Network n = cm.getActiveNetwork();
                if (n != null) nc = cm.getNetworkCapabilities(n);
            } catch (Throwable ignored) {}
        }
        m.put("连接状态", ni != null && ni.isConnected() ? "已连接" : "未连接（或无权限读取）");
        String type = "未知";
        if (nc != null) {
            if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) type = "Wi-Fi";
            else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) type = "移动网络";
            else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) type = "以太网";
            else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) type = "蓝牙共享";
            else type = "其他";
        } else if (ni != null) {
            type = ni.getTypeName();
        }
        m.put("网络类型", type);

        // 本机 IPv4
        StringBuilder ips = new StringBuilder();
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs != null && ifs.hasMoreElements()) {
                NetworkInterface iface = ifs.nextElement();
                if (!iface.isUp() || iface.isLoopback()) continue;
                Enumeration<InetAddress> addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof Inet4Address) {
                        if (ips.length() > 0) ips.append("\n");
                        ips.append(iface.getName()).append("：").append(a.getHostAddress());
                    }
                }
            }
        } catch (Throwable ignored) {}
        m.put("本机 IPv4", ips.length() > 0 ? ips.toString() : "不可读");

        // 运营商
        try {
            TelephonyManager tm = (TelephonyManager) ctx.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm != null) {
                String op = tm.getNetworkOperatorName();
                String sim = tm.getSimOperatorName();
                m.put("移动网络运营商", (op == null || op.length() == 0) ? NA : op);
                m.put("SIM 运营商", (sim == null || sim.length() == 0) ? NA : sim);
            }
        } catch (Throwable ignored) {}

        // MAC
        String mac = null;
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                WifiInfo wi = wm.getConnectionInfo();
                if (wi != null) mac = wi.getMacAddress();
                if (mac != null && ("02:00:00:00:00:00".equals(mac) || mac.length() == 0)) mac = null;
            }
        } catch (Throwable ignored) {}
        if (mac == null) mac = readLine("/sys/class/net/wlan0/address");
        m.put("Wi-Fi MAC", mac != null ? mac : "系统限制不可取（Android 6+ 已随机化）");

        // WiFi 名称
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                WifiInfo wi = wm.getConnectionInfo();
                String ssid = wi != null ? wi.getSSID() : null;
                if (ssid == null || ssid.contains("unknown") || ssid.length() == 0) {
                    ssid = "需定位权限，未申请（本 App 不弹权限框）";
                }
                m.put("Wi-Fi 名称", ssid);
            }
        } catch (Throwable ignored) {}

        // DNS
        try {
            LinkProperties lp = (cm != null && Build.VERSION.SDK_INT >= 21) ? cm.getLinkProperties(cm.getActiveNetwork()) : null;
            if (lp != null) {
                List<InetAddress> dns = lp.getDnsServers();
                StringBuilder sb = new StringBuilder();
                for (InetAddress a : dns) {
                    if (sb.length() > 0) sb.append(" / ");
                    sb.append(a.getHostAddress());
                }
                m.put("DNS 服务器", sb.length() > 0 ? sb.toString() : NA);
                if (lp.getDomains() != null) m.put("搜索域", lp.getDomains());
            }
        } catch (Throwable ignored) {}
        return m;
    }

    // ------------------------------------------------------------ 8 传感器
    static LinkedHashMap<String, String> sensors(Context ctx) {
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        SensorManager sm = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
        if (sm == null) {
            m.put("传感器", "SensorManager 不可用");
            return m;
        }
        List<Sensor> list = sm.getSensorList(Sensor.TYPE_ALL);
        m.put("传感器总数", String.valueOf(list == null ? 0 : list.size()));
        if (list == null) return m;
        int i = 0;
        for (Sensor se : list) {
            if (i >= 40) {
                m.put("…", "其余 " + (list.size() - 40) + " 个未显示");
                break;
            }
            i++;
            String label = "[" + i + "] " + trunc(se.getName(), 26);
            String typeStr;
            try {
                typeStr = Build.VERSION.SDK_INT >= 20 ? se.getStringType() : ("type " + se.getType());
            } catch (Throwable t) {
                typeStr = "type " + se.getType();
            }
            String v = trunc(se.getVendor(), 18) + " · " + shortType(typeStr)
                     + " · " + round2(se.getPower()) + " mA";
            m.put(label, v);
        }
        return m;
    }

    static String shortType(String full) {
        if (full == null) return NA;
        int idx = full.lastIndexOf('.');
        return idx >= 0 && idx + 1 < full.length() ? full.substring(idx + 1) : full;
    }

    // ------------------------------------------------------------ 9 相机
    static LinkedHashMap<String, String> camera(Context ctx) {
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        CameraManager cman = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
        if (cman == null) {
            m.put("相机", "CameraManager 不可用");
            return m;
        }
        String[] ids;
        try {
            ids = cman.getCameraIdList();
        } catch (Throwable t) {
            m.put("相机", "读取失败：" + t.getClass().getSimpleName());
            return m;
        }
        m.put("摄像头数量", (ids == null ? 0 : ids.length) + " 个");
        if (ids == null) return m;
        for (String id : ids) {
            try {
                CameraCharacteristics c = cman.getCameraCharacteristics(id);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                String face;
                if (facing == null) face = "未知朝向";
                else if (facing == CameraCharacteristics.LENS_FACING_BACK) face = "后置";
                else if (facing == CameraCharacteristics.LENS_FACING_FRONT) face = "前置";
                else if (facing == CameraCharacteristics.LENS_FACING_EXTERNAL) face = "外接";
                else face = "未知";
                StringBuilder v = new StringBuilder();
                Size px = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
                if (px != null && px.getWidth() > 0 && px.getHeight() > 0) {
                    double mp = px.getWidth() * (double) px.getHeight() / 1000000.0;
                    v.append(px.getWidth()).append("×").append(px.getHeight())
                     .append("（").append(String.format(Locale.US, "%.1f MP", mp)).append("）");
                }
                Boolean flash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (flash != null && flash) v.append(" · 有闪光灯");
                m.put("相机 " + id + "（" + face + "）", v.length() > 0 ? v.toString() : NA);
            } catch (Throwable t) {
                m.put("相机 " + id, "不可读：" + t.getClass().getSimpleName()
                        + "（部分机型需相机权限）");
            }
        }
        return m;
    }

    // ------------------------------------------------------------ 10 系统
    static LinkedHashMap<String, String> system() {
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        try {
            m.put("开机时长", fmtDuration(SystemClock.elapsedRealtime()));
        } catch (Throwable ignored) {}
        try {
            TimeZone tz = TimeZone.getDefault();
            m.put("时区", tz.getID() + "（" + tz.getDisplayName(false, TimeZone.SHORT) + "）");
            m.put("UTC 偏移", String.format(Locale.US, "UTC%+d:%02d",
                    tz.getRawOffset() / 3600000, Math.abs((tz.getRawOffset() / 60000) % 60)));
        } catch (Throwable ignored) {}
        try {
            Locale l = Locale.getDefault();
            m.put("语言 / 地区", l.toString() + "（" + l.getDisplayName() + "）");
        } catch (Throwable ignored) {}
        m.put("SELinux", selinux());
        try {
            String kv = System.getProperty("os.version");
            if (kv != null) m.put("内核版本", kv);
        } catch (Throwable ignored) {}
        try {
            String vm = System.getProperty("java.vm.version");
            if (vm != null) m.put("ART 版本", vm);
        } catch (Throwable ignored) {}
        m.put("构建时间", s(sysProp("ro.build.date")));
        return m;
    }

    static String selinux() {
        String enf = readLine("/sys/fs/selinux/enforce");
        if (enf != null) {
            if ("1".equals(enf)) return "enforcing（强制模式）";
            if ("0".equals(enf)) return "permissive（宽容模式）";
            return enf;
        }
        String p = sysProp("ro.boot.selinux");
        if (p != null && p.length() > 0) return p + "（来自 ro.boot.selinux）";
        String q = sysProp("ro.build.selinux");
        if (q != null && q.length() > 0) return q;
        return "不可读（系统限制）";
    }
}
