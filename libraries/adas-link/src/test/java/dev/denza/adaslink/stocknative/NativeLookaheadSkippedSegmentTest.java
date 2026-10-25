package dev.denza.adaslink.stocknative;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Source oracle: retained phase2/research/classes14.dex, method k.h.a0.d.c.c,
 * code_item 0x08aeb4. Its instruction SHA-256 is
 * 5db0b1a050bc306bc6176d60964ee05ff3939a1bc4950b79c0d2d2ecf80e9e02.
 * At code unit 0x0032, if-gez jumps to 0x011a, bypassing const/4 v9,0
 * at 0x0118. Normal inner-loop exhaustion reaches that reset through 0x0111.
 * The retained DEX matches classes14.dex in stock APK SHA-256
 * 3a7773d4b41bbbdcb2df7f6f249b91feaf197ff791d67e32e31859bd2425c793.
 */
public final class NativeLookaheadSkippedSegmentTest {
    @SuppressWarnings("unchecked")
    private static <T>T view(Class<T> type,InvocationHandler handler) {
        return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},handler);
    }

    @Test public void initiallySkippedSegmentsRetainTheLinkIndexUntilALoopActuallyCompletes() {
        List<String> visited=new ArrayList<>();
        int[] counts={1,2,3,1};
        NativePath path=view(NativePath.class,(owner,method,args)->{
            if(method.getName().equals("getSegmentCount"))return (long)counts.length;
            if(method.getName().equals("getSegmentInfo")) {
                final int segment=((Number)args[0]).intValue();
                return view(NativePath.Segment.class,(s,m,a)->{
                    if(m.getName().equals("getLinkCount"))return (long)counts[segment];
                    if(m.getName().equals("getLinkInfo")) {
                        int link=((Number)a[0]).intValue();visited.add(segment+":"+link);
                        // Both the greater-than and equality entry branches skip a segment.
                        assertTrue("Original DEX visits only segment 2/link 2 then segment 3/link 0",
                                segment==2&&link==2||segment==3&&link==0);
                        return view(NativePath.Link.class,(l,getter,ignored)->{
                            switch(getter.getName()) {
                                case "getLength":return segment==2?70:30;
                                case "getFormway":return 0;
                                case "getLinkType":return segment==2?2:0;
                                case "hasTrafficLight":return false;
                                default:throw new AssertionError("Unexpected link getter: "+getter.getName());
                            }
                        });
                    }
                    throw new AssertionError("Unexpected segment getter: "+m.getName());
                });
            }
            throw new AssertionError("Unexpected path getter: "+method.getName());
        });
        FontRoundBean out=new FontRoundBean();
        NativeLookahead.update(out,path,false,0,2,0,5,0,3,0);
        assertEquals(Arrays.asList("2:2","3:0"),visited);
        assertEquals(100,out.getDstLinkDistance());
        assertEquals(0,out.getEnterTunnelDist());
        assertArrayEquals(new int[]{3,0},out.getFont3kmIdx());
    }
}
