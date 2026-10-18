package local.mapscompat;
public final class ClientIdentity {
 private ClientIdentity() {}
 public static String header(String name, String value) {
  if ("X-Android-Package".equalsIgnoreCase(name)) return "com.google.android.apps.maps";
  if ("X-Android-Cert".equalsIgnoreCase(name)) return "38918A453D07199354F8B19AF05EC6562CED5788";
  return value;
 }
}
