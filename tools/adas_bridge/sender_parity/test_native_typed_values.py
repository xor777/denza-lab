"""Raw value replay preserves numeric bits, stable DTO identity and actual dispatch mutations."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
JDK = Path('/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin')


@unittest.skipUnless((JDK / 'javac').exists() or shutil.which('javac'), 'Java compiler required')
class NativeTypedValuesTest(unittest.TestCase):
    def test_actual_numeric_and_mutable_input_shapes(self):
        java = r'''
package dev.denza.adaslink.stocknative;
import java.util.*;
import java.math.BigInteger;
public final class TypedValuesChecks {
  static int checks;
  static void check(boolean b){checks++;if(!b)throw new AssertionError("check "+checks);}
  static Map<String,Object> m(Object... xs){Map<String,Object> m=new LinkedHashMap<>();for(int i=0;i<xs.length;i+=2)m.put((String)xs[i],xs[i+1]);return m;}
  static Map<String,Object> n(int n){return m("type","i32","value",n);}
  static Map<String,Object> list(String id,Object... xs){return m("type","list","id",id,"class","java.util.ArrayList","items",Arrays.asList(xs));}
  static Map<String,Object> struct(String id,String cls,Map<String,Object> fields){return m("type","struct","id",id,"class",cls,"fields",fields);}
  public static void main(String[] ignored){
    final int[] resolves={0};Object proxy=new Object();NativeTypedValues d=new NativeTypedValues(t->{resolves[0]++;return proxy;});
    check(d.decode(m("type","i64","decimal","9223372036854775807")).equals(Long.MAX_VALUE));
    check(d.decode(n(42)) instanceof Integer);
    check(d.decode(m("type","i32","value",70,"boxed_class","java.lang.Short")).equals(Short.valueOf((short)70)));
    check(d.decode(m("type","i32","value",-128,"boxed_class","java.lang.Byte")).equals(Byte.valueOf((byte)-128)));
    ArrayList<Short> intervalSpeeds=(ArrayList<Short>)d.decode(list("interval-speeds",
      m("type","i32","value",70,"boxed_class","java.lang.Short"),
      m("type","i32","value",60,"boxed_class","java.lang.Short")));
    check(((Short)Collections.max(intervalSpeeds)).shortValue()==70);
    for(Map<String,Object> invalid:Arrays.asList(
      m("type","i32","value",32768,"boxed_class","java.lang.Short"),
      m("type","i32","value",128,"boxed_class","java.lang.Byte"),
      m("type","i32","value",1,"boxed_class","invented.Number")))
      try{d.decode(invalid);throw new AssertionError("invalid boxed number accepted");}catch(IllegalArgumentException expected){checks++;}
    check(Float.floatToRawIntBits((Float)d.decode(m("type","f32","bits","80000000")))==Integer.MIN_VALUE);
    check(Double.doubleToRawLongBits((Double)d.decode(m("type","f64","bits","8000000000000000")))==Long.MIN_VALUE);
    check(d.decode(struct(null,"java.math.BigInteger",m("decimal",m("type","string","value","18446744073709551615")))).equals(new BigInteger("18446744073709551615")));
    Map<String,Object> ref=m("type","object","id","p","class","com.autonavi.gbl.common.path.option.PathInfo","native_pointer","123");
    check(d.decode(ref)==proxy&&d.decode(ref)==proxy&&resolves[0]==1);
    Map<String,Object> loc=struct("loc","com.autonavi.gbl.pos.model.LocInfo",m("speed",m("type","f32","bits","42293333"),
      "gpsPos",struct("coord","com.autonavi.gbl.common.model.Coord3DDouble",m("lon",m("type","f64","bits","3ff0000000000000")))));
    NativeModels.LocInfo a=(NativeModels.LocInfo)d.decode(loc);Object point=a.gpsPos;a.alt=7;
    check(a.speed==42.3f&&a.gpsPos.lon==1);
    Map<String,Object> update=struct("loc","com.autonavi.gbl.pos.model.LocInfo",m("speed",m("type","f32","bits","00000000"),
      "gpsPos",struct("coord","com.autonavi.gbl.common.model.Coord3DDouble",m("lon",m("type","f64","bits","4000000000000000")))));
    d.refresh(update);check(d.decode(update)==a&&a.gpsPos==point&&a.gpsPos.lon==2&&a.speed==0&&a.alt==7);
    check(d.original(a)==update);
    NativeModels.NaviSubCameraExt camera=(NativeModels.NaviSubCameraExt)d.decode(struct("cam","com.autonavi.gbl.guide.model.NaviSubCameraExt",m("speed",list("speeds",n(50),n(60)))));
    check(camera.speed.get(0).equals((short)50));
    Object speeds=camera.speed;d.refresh(list("speeds",n(70)));check(camera.speed==speeds&&camera.speed.size()==1);
    camera.speed=new ArrayList<>(Arrays.asList((short)75));Object computed=camera.speed;
    Map<String,Object> assertion=struct("cam","com.autonavi.gbl.guide.model.NaviSubCameraExt",m("speed",list("computed-new-list",n(75))));
    d.bindVerifiedMutation(camera,assertion);check(camera.speed==computed&&camera.speed.get(0).equals((short)75));
    check(d.decode(m("type","object","id","computed-new-list","class","java.util.ArrayList","native_pointer",null))==computed);
    try{d.bindVerifiedMutation(new ArrayList<>(),list("computed-new-list"));throw new AssertionError("identity rebound");}catch(IllegalArgumentException expected){checks++;}
    Map<?,?> unknown=(Map<?,?>)d.decode(struct("u","sdk.Unknown",m("raw",n(8))));check(unknown.get("raw").equals(8));
    Object same=d.decode(struct("u","sdk.Unknown",m("raw",n(9))));check(same==unknown&&unknown.get("raw").equals(9));
    Map<?,?> map=(Map<?,?>)d.decode(struct("map","java.util.concurrent.ConcurrentHashMap",m("entries",list("entries",list("entry",m("type","i64","decimal","7"),m("type","i64","decimal","9"))))));
    check(map.get(7L).equals(9L));
    try{d.decode(m("type","invented"));throw new AssertionError("unknown type accepted");}catch(IllegalArgumentException expected){checks++;}
    System.out.println(checks+" raw typed-value checks passed");
  }
}
'''
        javac = str(JDK / 'javac') if (JDK / 'javac').exists() else 'javac'
        java_exe = str(JDK / 'java') if (JDK / 'java').exists() else 'java'
        core = ROOT / 'libraries/adas-link/src/main/java/dev/denza/adaslink/stocknative'
        with tempfile.TemporaryDirectory(prefix='stock-typed-') as directory:
            path = Path(directory)
            test = path / 'TypedValuesChecks.java'
            test.write_text(java)
            subprocess.run([javac,'--release','8','-d',str(path),str(test),
                            str(HERE/'phase2/NativeTypedValues.java'),
                            *[str(core/name) for name in ('NativeModels.java','NativePath.java','TrafficLightState.java')]],
                           check=True,capture_output=True,text=True,timeout=60)
            result = subprocess.run([java_exe,'-cp',str(path),'dev.denza.adaslink.stocknative.TypedValuesChecks'],
                                    check=True,capture_output=True,text=True,timeout=10)
            self.assertIn('24 raw typed-value checks passed',result.stdout)


if __name__=='__main__':
    unittest.main()
