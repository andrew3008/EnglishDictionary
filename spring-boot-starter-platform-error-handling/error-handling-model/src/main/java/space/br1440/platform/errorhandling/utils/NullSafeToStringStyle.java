package space.br1440.platform.errorhandling.utils;

import org.apache.commons.lang3.builder.ToStringStyle;

import java.time.temporal.Temporal;
import java.util.*;

public class NullSafeToStringStyle extends ToStringStyle {

    public static final NullSafeToStringStyle DEFAULT = new NullSafeToStringStyle();
    public static final NullSafeToStringStyle SKIP_NULL = new NullSafeToStringStyle(true);

    private boolean skipNull;

    public NullSafeToStringStyle(boolean skipNull) {
        this();
        this.skipNull = skipNull;
    }

    protected NullSafeToStringStyle() {
        this.setUseShortClassName(true);
        this.setContentStart("{");
        this.setContentEnd("}");
        this.setArrayStart("[");
        this.setArrayEnd("]");
        this.setNullText("null");
    }

    public void append(StringBuffer buffer, String fieldName, Object value, Boolean fullDetail) {
        if (value != null || !this.skipNull) {
            super.append(buffer, fieldName, value, fullDetail);
        }
    }

    protected void appendDetail(StringBuffer buffer, String fieldName, Object value) {
        switch (value) {
            case String s -> buffer.append("\"").append(s).append("\"");
            case Date d -> buffer.append("Date(\"").append(d).append("\")");
            case Temporal t -> buffer.append("Date(\"").append(t).append("\")");
            case Optional<?> opt -> {
                if (opt.isPresent()) {
                    buffer.append("Optional(");
                    this.appendDetail(buffer, fieldName, opt.get());
                    buffer.append(")");
                } else {
                    buffer.append("Optional.empty");
                }
            }
            default -> super.appendDetail(buffer, fieldName, value);
        }
    }

    protected void appendDetail(StringBuffer sb, String fieldName, Map<?, ?> map) {
        Iterator<? extends Map.Entry<?, ?>> i = map.entrySet().iterator();
        sb.append('{');

        while(i.hasNext()) {
            Map.Entry<?, ?> e = i.next();
            Object key = e.getKey();
            Object value = e.getValue();

            this.appendDetail(sb, null, key);
            sb.append('=');

            this.appendDetail(sb, null, value);

            if (i.hasNext()) {
                sb.append(',').append(' ');
            }
        }

        sb.append('}');
    }

    protected void appendDetail(StringBuffer buffer, String fieldName, Collection<?> coll) {
        this.appendDetail(buffer, fieldName, coll.toArray());
    }
}

