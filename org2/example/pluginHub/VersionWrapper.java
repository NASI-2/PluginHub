package org2.example.pluginHub;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class VersionWrapper {
    private static final Pattern VERSION = Pattern.compile("(\\d+)\\.(\\d+)(?:\\.(\\d+))?");
    private VersionWrapper() {}

    public static int[] parse(String value) {
        Matcher m = VERSION.matcher(value == null ? "" : value);
        if (!m.find()) return new int[]{0, 0, 0};
        int major = Integer.parseInt(m.group(1));
        int minor = Integer.parseInt(m.group(2));
        int patch = m.group(3) == null ? 0 : Integer.parseInt(m.group(3));
        return new int[]{major, minor, patch};
    }

    public static boolean isSupportedServerRange(String mcVersion) {
        return compare(mcVersion, "1.18.0") >= 0 && compare(mcVersion, "26.1.0") <= 0;
    }

    public static int compare(String a, String b) {
        int[] aa = parse(a);
        int[] bb = parse(b);
        for (int i = 0; i < 3; i++) {
            if (aa[i] != bb[i]) return Integer.compare(aa[i], bb[i]);
        }
        return 0;
    }
}
