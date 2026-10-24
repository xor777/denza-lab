package android.os;
import java.util.ArrayList;
public final class Parcel {
    private final ArrayList<Object> values = new ArrayList<>(); private int offset;
    public static Parcel obtain() { return new Parcel(); }
    public void recycle() { }
    void rewind() { offset = 0; }
    public void writeInterfaceToken(String value) { writeString(value); }
    public void enforceInterface(String value) { if (!value.equals(readString())) throw new SecurityException("Descriptor"); }
    public void writeNoException() { writeInt(0); }
    public void readException() { if (readInt() != 0) throw new IllegalStateException("Remote exception"); }
    public void writeInt(int value) { values.add(value); }
    public int readInt() { return (Integer) values.get(offset++); }
    public void writeLong(long value) { values.add(value); }
    public long readLong() { return (Long) values.get(offset++); }
    public void writeString(String value) { values.add(value); }
    public String readString() { return (String) values.get(offset++); }
    public void writeStrongBinder(IBinder value) { values.add(value); }
    public IBinder readStrongBinder() { return (IBinder) values.get(offset++); }
    public void writeByteArray(byte[] value) { values.add(value.clone()); }
    public void readByteArray(byte[] target) {
        byte[] value = (byte[]) values.get(offset++);
        if (target.length != value.length) throw new IllegalArgumentException("Byte array length");
        System.arraycopy(value, 0, target, 0, value.length);
    }
}
