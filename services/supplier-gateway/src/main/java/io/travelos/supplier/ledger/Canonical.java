package io.travelos.supplier.ledger;

import com.google.protobuf.Descriptors;
import com.google.protobuf.Message;
import java.util.Map;
import java.util.TreeMap;

/**
 * A stable text of the parts of a mutation that matter for "same request": the request context's
 * per-call fields (correlation, causation, principal) are left out, so a retry is the same request.
 */
final class Canonical {
  private Canonical() {}

  static String of(Message request) {
    StringBuilder out = new StringBuilder();
    write(request, out, true);
    return out.toString();
  }

  private static void write(Message m, StringBuilder out, boolean top) {
    Map<String, Object> fields = new TreeMap<>();
    for (Map.Entry<Descriptors.FieldDescriptor, Object> e : m.getAllFields().entrySet()) {
      if (top && e.getKey().getName().equals("ctx")) {
        continue;
      }
      fields.put(e.getKey().getName(), e.getValue());
    }
    out.append('{');
    for (Map.Entry<String, Object> e : fields.entrySet()) {
      out.append(e.getKey()).append('=');
      value(e.getValue(), out);
      out.append(';');
    }
    out.append('}');
  }

  private static void value(Object v, StringBuilder out) {
    if (v instanceof Message m) {
      write(m, out, false);
    } else if (v instanceof java.util.List<?> list) {
      out.append('[');
      for (Object o : list) {
        value(o, out);
        out.append(',');
      }
      out.append(']');
    } else {
      out.append(v);
    }
  }
}
