package com.virjar.spider.proxy.ha.utils;

import org.apache.commons.lang3.math.NumberUtils;

public class IPUtils {
    public static boolean isIpV4(String input) {
        // 3 * 4 + 3 = 15
        if (input.length() > 15) {
            return false;
        }
        String[] split = input.split("\\.");
        if (split.length != 4) {
            return false;
        }
        for (String segment : split) {
            int i = NumberUtils.toInt(segment, -1);
            if (i < 0 || i > 255) {
                return false;
            }
        }
        return true;
    }
}
