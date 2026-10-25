package dev.denza.adaslink.stocknative;

import java.math.BigDecimal;
import java.util.Objects;

final class NativeChineseNumbers {
    public static final char[] a = {38646, 19968, 20108, 19977, 22235, 20116, 20845, 19971, 20843, 20061};

    public static final char[] f13315b = {38646, 22777, 36144, 21441, 32902, 20237, 38470, 26578, 25420, 29590};

    public static final char[] f13316c = {20159, 19975, 21315, 30334, 21313};

    public static final char[] f13317d = {20159, 33836, 20191, 20336, 25342};

    public static a[] f13318e;

    public static class a {
        public String a;

        public long f13319b;

        public Boolean f13320c;

        public a(String str, long j2, Boolean bool) {
            this.a = str;
            this.f13319b = j2;
            this.f13320c = bool;
        }
    }

    static {
        Boolean bool = Boolean.FALSE;
        Boolean bool2 = Boolean.TRUE;
        f13318e = new a[]{new a("十", 10L, bool), new a("拾", 10L, bool), new a("百", 100L, bool), new a("佰", 100L, bool), new a("千", 1000L, bool), new a("仟", 1000L, bool), new a("万", 10000L, bool2), new a("萬", 10000L, bool2), new a("亿", 100000000L, bool2)};
    }

    public static BigDecimal a(String str) {
        long j2;
        String strReplaceAll = str;
        try {
            BigDecimal bigDecimal = new BigDecimal(0);
            if (strReplaceAll != null && str.trim().length() != 0) {
                char cCharAt = strReplaceAll.charAt(0);
                char cCharAt2 = strReplaceAll.charAt(str.length() - 1);
                Boolean bool = Boolean.TRUE;
                if (d(cCharAt) && str.length() > 1) {
                    long j3 = f13318e[c(String.valueOf(cCharAt))].f13319b;
                    if (!d(cCharAt2) && str.length() == 2) {
                        return bigDecimal.add(BigDecimal.valueOf(j3).add(BigDecimal.valueOf(b(String.valueOf(cCharAt2)))));
                    }
                    if (d(cCharAt2)) {
                        a aVar = f13318e[c(String.valueOf(cCharAt2))];
                        if (j3 != 10 || !aVar.f13320c.booleanValue()) {
                            throw new NumberFormatException("中文数字异常");
                        }
                        strReplaceAll = "一" + strReplaceAll;
                    }
                }
                if (d(cCharAt2)) {
                    strReplaceAll = strReplaceAll.substring(0, strReplaceAll.length() - 1);
                    j2 = f13318e[c(String.valueOf(cCharAt2))].f13319b;
                    bool = f13318e[c(String.valueOf(cCharAt2))].f13320c;
                } else {
                    if (strReplaceAll.length() == 1) {
                        int iF = f(strReplaceAll);
                        if (iF != -1) {
                            return BigDecimal.valueOf(iF);
                        }
                        return null;
                    }
                    j2 = 1;
                }
                int i2 = 0;
                while (true) {
                    char[] cArr = a;
                    if (i2 >= cArr.length) {
                        break;
                    }
                    strReplaceAll = strReplaceAll.replaceAll(String.valueOf(cArr[i2]), String.valueOf(f13315b[i2]));
                    i2++;
                }
                int i3 = 0;
                while (true) {
                    char[] cArr2 = f13316c;
                    if (i3 >= cArr2.length) {
                        break;
                    }
                    strReplaceAll = strReplaceAll.replaceAll(String.valueOf(cArr2[i3]), String.valueOf(f13317d[i3]));
                    i3++;
                }
                int i4 = 0;
                while (true) {
                    char[] cArr3 = f13317d;
                    if (i4 >= cArr3.length || strReplaceAll.trim().length() == 0) {
                        break;
                    }
                    String strValueOf = String.valueOf(cArr3[i4]);
                    String strSubstring = strReplaceAll.contains(strValueOf) ? strReplaceAll.substring(0, strReplaceAll.lastIndexOf(strValueOf) + 1) : null;
                    if (strSubstring != null && strSubstring.trim().length() > 0) {
                        strReplaceAll = strReplaceAll.replaceAll(strSubstring, "");
                        bigDecimal = bigDecimal.add(BigDecimal.valueOf(b(strSubstring.substring(0, strSubstring.length() - 1))).multiply(BigDecimal.valueOf(f13318e[c(strValueOf)].f13319b)));
                    }
                    i4++;
                    if (i4 == f13316c.length && !"".equals(strReplaceAll)) {
                        long jB = b(strReplaceAll);
                        if (!bool.booleanValue()) {
                            jB = BigDecimal.valueOf(jB).multiply(BigDecimal.valueOf(j2)).longValue();
                        }
                        bigDecimal = bigDecimal.add(BigDecimal.valueOf(jB));
                    }
                }
                return (!bool.booleanValue() || j2 <= 1) ? (j2 <= 0 || bigDecimal.compareTo(BigDecimal.ZERO) != BigDecimal.ZERO.intValue()) ? bigDecimal : BigDecimal.ONE.multiply(BigDecimal.valueOf(j2)) : bigDecimal.multiply(BigDecimal.valueOf(j2));
            }
            return bigDecimal;
        } catch (Exception e2) {
            return null;
        }
    }

    public static long b(String str) {
        int i2 = 0;
        long j2 = 0;
        long j3 = 0;
        long j4 = 0;
        while (i2 < str.length()) {
            int i3 = i2 + 1;
            int iF = f(str.substring(i2, i3));
            if (iF >= 0) {
                j4 = iF;
                if (i3 >= str.length()) {
                    j2 += j4;
                    return j3 + j2;
                }
                i2 = i3;
            } else {
                int iC = c(str.substring(i2, i3));
                if (iC == -1) {
                    throw new NumberFormatException("字符串存在除 <数字和单位> 以外的中文");
                }
                if (f13318e[iC].f13320c.booleanValue()) {
                    j3 += (j2 + j4) * f13318e[iC].f13319b;
                    j2 = 0;
                } else {
                    j2 += j4 * f13318e[iC].f13319b;
                }
                if (i3 >= str.length()) {
                    return j3 + j2;
                }
                j4 = 0;
                i2 = i3;
            }
        }
        return j3;
    }

    public static int c(String str) {
        int i2 = 0;
        while (true) {
            a[] aVarArr = f13318e;
            if (i2 >= aVarArr.length) {
                return -1;
            }
            if (str.equals(aVarArr[i2].a)) {
                return i2;
            }
            i2++;
        }
    }

    public static boolean d(char c2) {
        return "十拾百佰千仟万萬亿".contains(String.valueOf(c2));
    }

    public static BigDecimal e(String str) {
        try {StringBuilder out=new StringBuilder();char[]chars=str.toCharArray();
            for(char c:chars){int digit="一二三四五六七八九".indexOf(c);if(digit>=0)out.append(digit+1);else if(c=='点')out.append('.');}
            if(chars.length==2){if(chars[1]=='百')out.append("00");else if(chars[1]=='十')out.append('0');}
            return BigDecimal.valueOf(Double.parseDouble(out.toString()));
        }catch(Exception failure){return null;}
    }

    public static int f(String str) {
        int i2 = 0;
        while (true) {
            char[] cArr = a;
            if (i2 >= cArr.length) {
                return -1;
            }
            if (Objects.equals(str, String.valueOf(cArr[i2])) || Objects.equals(str, String.valueOf(f13315b[i2]))) {
                break;
            }
            i2++;
        }
        return i2;
    }
}
