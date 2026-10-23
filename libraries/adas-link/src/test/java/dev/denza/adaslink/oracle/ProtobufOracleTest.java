package dev.denza.adaslink.oracle;

import static org.junit.Assert.assertEquals;

import com.google.protobuf.ByteString;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import dev.denza.adaslink.Pb;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.Test;

/**
 * protobuf-java itself, given the generated classes' schemas (proto3), must write the same bytes
 * as {@link Pb} for random values of every field, defaults included.
 */
public class ProtobufOracleTest {
    static FieldDescriptorProto.Type type(int t) {
        switch (t) {
            case Pb.DOUBLE: return FieldDescriptorProto.Type.TYPE_DOUBLE;
            case Pb.FLOAT: return FieldDescriptorProto.Type.TYPE_FLOAT;
            case Pb.INT32: return FieldDescriptorProto.Type.TYPE_INT32;
            case Pb.UINT32: return FieldDescriptorProto.Type.TYPE_UINT32;
            case Pb.UINT64: return FieldDescriptorProto.Type.TYPE_UINT64;
            case Pb.BOOL: return FieldDescriptorProto.Type.TYPE_BOOL;
            case Pb.STRING: return FieldDescriptorProto.Type.TYPE_STRING;
            case Pb.BYTES: return FieldDescriptorProto.Type.TYPE_BYTES;
            default: return FieldDescriptorProto.Type.TYPE_MESSAGE;
        }
    }

    static List<Pb.Msg> all() throws Exception {
        List<Pb.Msg> out = new ArrayList<>();
        for (Field f : Pb.class.getFields())
            if (Modifier.isStatic(f.getModifiers()) && f.getType() == Pb.Msg.class) out.add((Pb.Msg) f.get(null));
        return out;
    }

    @Test
    public void bytesMatchProtobufJava() throws Exception {
        FileDescriptorProto.Builder file = FileDescriptorProto.newBuilder().setName("stock.proto").setSyntax("proto3").setPackage("stock");
        List<Pb.Msg> messages = all();
        for (Pb.Msg m : messages) {
            DescriptorProto.Builder d = DescriptorProto.newBuilder().setName(m.name);
            for (Pb.Field f : m.fields) {
                FieldDescriptorProto.Builder fd = FieldDescriptorProto.newBuilder().setName(f.name).setNumber(f.number)
                        .setType(type(f.type)).setLabel(f.type == Pb.MESSAGE_LIST ? FieldDescriptorProto.Label.LABEL_REPEATED : FieldDescriptorProto.Label.LABEL_OPTIONAL);
                if (f.message != null) fd.setTypeName(".stock." + f.message.name);
                d.addField(fd);
            }
            file.addMessageType(d);
        }
        Descriptors.FileDescriptor fd = Descriptors.FileDescriptor.buildFrom(file.build(), new Descriptors.FileDescriptor[0]);
        Map<String, Descriptors.Descriptor> types = new HashMap<>();
        for (Descriptors.Descriptor d : fd.getMessageTypes()) types.put(d.getName(), d);
        Random random = new Random(7);
        int compared = 0;
        for (int round = 0; round < 300; round++) for (Pb.Msg m : messages) {
            Object[] values = new Object[m.fields.length];
            DynamicMessage.Builder b = DynamicMessage.newBuilder(types.get(m.name));
            for (int i = 0; i < m.fields.length; i++) {
                Pb.Field f = m.fields[i];
                Descriptors.FieldDescriptor field = types.get(m.name).findFieldByNumber(f.number);
                boolean zero = random.nextInt(4) == 0;
                switch (f.type) {
                    case Pb.DOUBLE: { double v = zero ? 0 : (random.nextDouble() - 0.5) * 360; values[i] = v; b.setField(field, v); break; }
                    case Pb.FLOAT: { float v = zero ? 0 : random.nextFloat() * 100; values[i] = v; b.setField(field, v); break; }
                    case Pb.INT32: case Pb.UINT32: {
                        int v = zero ? 0 : random.nextInt(3) == 0 ? -1 - random.nextInt(5) : random.nextInt(1 << random.nextInt(31));
                        if (random.nextInt(9) == 0) v = Integer.MAX_VALUE;
                        values[i] = v; b.setField(field, v); break;
                    }
                    case Pb.UINT64: { long v = zero ? 0 : random.nextInt(3) == 0 ? -1 : (long) random.nextInt(Integer.MAX_VALUE) * 100003; values[i] = v; b.setField(field, v); break; }
                    case Pb.BOOL: { boolean v = !zero && random.nextBoolean(); values[i] = v; b.setField(field, v); break; }
                    case Pb.STRING: { String v = zero ? "" : "路 " + random.nextInt(1000) + " x"; values[i] = v; b.setField(field, v); break; }
                    case Pb.BYTES: { byte[] v = zero ? new byte[0] : new byte[]{1, 2, (byte) random.nextInt(256)}; values[i] = v; b.setField(field, ByteString.copyFrom(v)); break; }
                    default: break; // nested messages are compared through their own rounds
                }
            }
            if (hasMessage(m)) continue;
            assertEquals(m.name, Pb.hex(b.build().toByteArray()), Pb.hex(m.encode(values)));
            compared++;
        }
        assertEquals(true, compared > 1000);
        // A set sub-message goes out even when empty, as the stock's builders always set it.
        for (Pb.Msg m : messages) {
            if (m.fields.length != 1 || m.fields[0].type != Pb.MESSAGE) continue;
            Descriptors.Descriptor wrapper = types.get(m.name);
            DynamicMessage inner = DynamicMessage.getDefaultInstance(types.get(m.fields[0].message.name));
            byte[] expected = DynamicMessage.newBuilder(wrapper).setField(wrapper.findFieldByNumber(1), inner).build().toByteArray();
            assertEquals(m.name, Pb.hex(expected), Pb.hex(m.encode((Object) new byte[0])));
        }
    }

    static boolean hasMessage(Pb.Msg m) {
        for (Pb.Field f : m.fields) if (f.message != null) return true;
        return false;
    }
}
