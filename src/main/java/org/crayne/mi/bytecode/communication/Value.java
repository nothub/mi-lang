package org.crayne.mi.bytecode.communication;

import org.crayne.mi.bytecode.reader.ByteCodeInterpreter;
import org.crayne.mi.bytecode.reader.ByteCodeValue;
import org.jetbrains.annotations.NotNull;

public class Value {

    private final ByteCodeValue value;

    protected Value(@NotNull final ByteCodeValue value) {
        this.value = value;
    }

    public static Value of(@NotNull final Type type, @NotNull final Object obj, @NotNull final ByteCodeInterpreter runtime) {
        final String objType = obj.getClass().getName();
        if (!objType.equals(type.javaType())) throw new MiExecutionException("Given object type " + objType + " and specified type " + type + " differ");

        return new Value(switch (objType) {
            case "java.lang.Integer" -> ByteCodeValue.intValue((int) obj, runtime);
            case "java.lang.Double" -> ByteCodeValue.doubleValue((double) obj, runtime);
            case "java.lang.Long" -> ByteCodeValue.longValue((long) obj, runtime);
            case "java.lang.Float" -> ByteCodeValue.floatValue((float) obj, runtime);
            case "java.lang.Character" -> ByteCodeValue.charValue((char) obj, runtime);
            case "java.lang.Boolean" -> ByteCodeValue.boolValue((boolean) obj, runtime);
            case "java.lang.String" -> ByteCodeValue.stringValue((String) obj, runtime);
            default -> throw new MiExecutionException("Could not generate value for " + type.byteDatatype().name() + " " + obj);
        });
    }

    public static Value of(@NotNull final Object obj, @NotNull final ByteCodeInterpreter runtime) {
        return of(Type.of(obj.getClass()), obj, runtime);
    }

    public Type type() {
        return new Type(value.type());
    }

    public Object value() {
        return value.asObject();
    }

    public ByteCodeValue byteCodeValue() {
        return value;
    }

    @Override
    public String toString() {
        return "Value{" +
                "type=" + type() +
                ", value=" + value() +
                '}';
    }
}
