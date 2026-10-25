package dev.denza.adaslink.stocknative;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public final class NativeOpenCitiesTest {
    @Test public void repeatedMessagesAndGeneratedDefaults() {
        assertEquals(Arrays.asList(1,372,0), NativeOpenCities.parse(new byte[]{10,2,8,1,10,3,8,(byte)244,2,10,0}));
        assertEquals(Collections.emptyList(), NativeOpenCities.parse(new byte[0]));
    }
    @Test public void unknownFieldsDoNotBecomeCitiesAndLastScalarWins() {
        assertEquals(Arrays.asList(2), NativeOpenCities.parse(new byte[]{16,99,10,6,8,1,16,8,8,2}));
    }
    @Test public void malformedResponseCannotEraseCache() {
        for(byte[] value:new byte[][]{{10,3,8,1},{10,2,8,(byte)128},{0},{12},{10,1,8}}) {
            try {NativeOpenCities.parse(value);fail("malformed response admitted");}
            catch(IllegalArgumentException expected) { }
        }
    }
}
