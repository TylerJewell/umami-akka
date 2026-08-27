package io.akka.umami.lib;

import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * The geography database, read directly rather than through a library.
 *
 * <p>The original's published image ships an empty geography directory, so the original itself
 * answers no country for every event unless a provider header is present. This reader exists for a
 * deployment that supplies the file; absent one it answers nothing, which is the same answer.
 */
public final class Geo {

  private static final byte[] METADATA_MARKER = "«ÍïMaxMind.com".getBytes(
      java.nio.charset.StandardCharsets.ISO_8859_1);

  private static volatile Reader reader;
  private static volatile boolean attempted;

  private Geo() {}

  public static Detect.Location lookup(String ip) {
    var db = open();
    if (db == null) {
      return null;
    }
    try {
      var record = db.get(ip);
      if (record == null) {
        return null;
      }
      var country = nested(record, "country", "iso_code");
      if (country == null) {
        country = nested(record, "registered_country", "iso_code");
      }
      String region = null;
      var subdivisions = record.get("subdivisions");
      if (subdivisions instanceof java.util.List<?> list && !list.isEmpty()
          && list.get(0) instanceof Map<?, ?> first) {
        var value = first.get("iso_code");
        region = value == null ? null : value.toString();
      }
      var city = nested(record, "city", "names", "en");
      return new Detect.Location(country, Detect.regionCode(country, region), city);
    } catch (Exception e) {
      return null;
    }
  }

  @SuppressWarnings("unchecked")
  private static String nested(Map<String, Object> record, String... path) {
    Object cursor = record;
    for (var key : path) {
      if (!(cursor instanceof Map<?, ?> map)) {
        return null;
      }
      cursor = ((Map<String, Object>) map).get(key);
    }
    return cursor == null ? null : cursor.toString();
  }

  /** Resets the cached handle so a test can point at a different file. */
  public static synchronized void reset() {
    if (reader != null) {
      reader.close();
    }
    reader = null;
    attempted = false;
  }

  private static Reader open() {
    if (attempted) {
      return reader;
    }
    synchronized (Geo.class) {
      if (attempted) {
        return reader;
      }
      attempted = true;
      var configured = Env.get("GEOLITE_DB_PATH");
      var path = Path.of(configured != null ? configured : "geo/GeoLite2-City.mmdb");
      if (!Files.isRegularFile(path)) {
        return null;
      }
      try {
        reader = new Reader(path);
      } catch (Exception e) {
        reader = null;
      }
      return reader;
    }
  }

  /**
   * Just enough of the MaxMind binary format to answer a lookup: the metadata block at the tail,
   * the binary search tree at the head, and the data section between them.
   */
  private static final class Reader implements AutoCloseable {

    private final RandomAccessFile file;
    private final int nodeCount;
    private final int recordSize;
    private final int ipVersion;
    private final long searchTreeSize;
    private final long dataSectionStart;

    Reader(Path path) throws Exception {
      this.file = new RandomAccessFile(path.toFile(), "r");
      long metadataStart = findMetadata();
      var metadata = (Map<String, Object>) decode(metadataStart).value();
      this.nodeCount = ((Number) metadata.get("node_count")).intValue();
      this.recordSize = ((Number) metadata.get("record_size")).intValue();
      this.ipVersion = ((Number) metadata.get("ip_version")).intValue();
      this.searchTreeSize = (long) nodeCount * recordSize * 2 / 8;
      this.dataSectionStart = searchTreeSize + 16;
    }

    private long findMetadata() throws Exception {
      long length = file.length();
      long from = Math.max(0, length - 128 * 1024);
      var window = new byte[(int) (length - from)];
      file.seek(from);
      file.readFully(window);
      outer:
      for (int i = window.length - METADATA_MARKER.length; i >= 0; i--) {
        for (int j = 0; j < METADATA_MARKER.length; j++) {
          if (window[i + j] != METADATA_MARKER[j]) {
            continue outer;
          }
        }
        return from + i + METADATA_MARKER.length;
      }
      throw new IllegalStateException("no metadata marker");
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> get(String ip) throws Exception {
      var bits = addressBits(ip);
      if (bits == null) {
        return null;
      }
      int node = 0;
      for (boolean bit : bits) {
        if (node >= nodeCount) {
          break;
        }
        node = readNode(node, bit ? 1 : 0);
      }
      if (node == nodeCount) {
        return null;
      }
      if (node > nodeCount) {
        long offset = node - nodeCount - 16 + dataSectionStart;
        var decoded = decode(offset).value();
        return decoded instanceof Map ? (Map<String, Object>) decoded : null;
      }
      return null;
    }

    private boolean[] addressBits(String ip) {
      var address = Detect.stripPort(ip);
      if (address.contains(":")) {
        return null;
      }
      var parts = address.split("\\.");
      if (parts.length != 4) {
        return null;
      }
      int prefix = ipVersion == 6 ? 96 : 0;
      var bits = new boolean[prefix + 32];
      for (int i = 0; i < 4; i++) {
        int value = Integer.parseInt(parts[i]);
        for (int b = 0; b < 8; b++) {
          bits[prefix + i * 8 + b] = ((value >> (7 - b)) & 1) == 1;
        }
      }
      return bits;
    }

    private int readNode(int node, int index) throws Exception {
      long base = (long) node * recordSize * 2 / 8;
      file.seek(base);
      var bytes = new byte[recordSize * 2 / 8];
      file.readFully(bytes);
      return switch (recordSize) {
        case 24 -> index == 0
            ? ((bytes[0] & 0xff) << 16) | ((bytes[1] & 0xff) << 8) | (bytes[2] & 0xff)
            : ((bytes[3] & 0xff) << 16) | ((bytes[4] & 0xff) << 8) | (bytes[5] & 0xff);
        case 28 -> index == 0
            ? (((bytes[3] & 0xf0) >> 4) << 24) | ((bytes[0] & 0xff) << 16)
                | ((bytes[1] & 0xff) << 8) | (bytes[2] & 0xff)
            : ((bytes[3] & 0x0f) << 24) | ((bytes[4] & 0xff) << 16) | ((bytes[5] & 0xff) << 8)
                | (bytes[6] & 0xff);
        case 32 -> index == 0
            ? ((bytes[0] & 0xff) << 24) | ((bytes[1] & 0xff) << 16) | ((bytes[2] & 0xff) << 8)
                | (bytes[3] & 0xff)
            : ((bytes[4] & 0xff) << 24) | ((bytes[5] & 0xff) << 16) | ((bytes[6] & 0xff) << 8)
                | (bytes[7] & 0xff);
        default -> throw new IllegalStateException("unsupported record size " + recordSize);
      };
    }

    private record Decoded(Object value, long next) {}

    private Decoded decode(long offset) throws Exception {
      file.seek(offset);
      int control = file.read();
      int type = control >> 5;
      long cursor = offset + 1;
      if (type == 0) {
        file.seek(cursor);
        type = file.read() + 7;
        cursor++;
      }
      int size = control & 0x1f;
      if (size >= 29) {
        int extra = size - 28;
        file.seek(cursor);
        var bytes = new byte[extra];
        file.readFully(bytes);
        cursor += extra;
        long value = 0;
        for (var b : bytes) {
          value = (value << 8) | (b & 0xff);
        }
        size = switch (extra) {
          case 1 -> (int) value + 29;
          case 2 -> (int) value + 285;
          default -> (int) value + 65821;
        };
      }
      return switch (type) {
        case 1 -> {
          // A pointer, whose payload is the offset of the value it stands for.
          int pointerSize = ((control >> 3) & 0x3) + 1;
          file.seek(cursor);
          var bytes = new byte[pointerSize];
          file.readFully(bytes);
          cursor += pointerSize;
          long value = control & 0x7;
          if (pointerSize == 4) {
            value = 0;
          }
          for (var b : bytes) {
            value = (value << 8) | (b & 0xff);
          }
          value += switch (pointerSize) {
            case 2 -> 2048;
            case 3 -> 526336;
            default -> 0;
          };
          yield new Decoded(decode(dataSectionStart + value).value(), cursor);
        }
        case 2 -> {
          file.seek(cursor);
          var bytes = new byte[size];
          file.readFully(bytes);
          yield new Decoded(
              new String(bytes, java.nio.charset.StandardCharsets.UTF_8), cursor + size);
        }
        case 5, 6, 9, 10 -> {
          file.seek(cursor);
          var bytes = new byte[size];
          file.readFully(bytes);
          long value = 0;
          for (var b : bytes) {
            value = (value << 8) | (b & 0xff);
          }
          yield new Decoded(value, cursor + size);
        }
        case 7 -> {
          var map = new HashMap<String, Object>();
          long position = cursor;
          for (int i = 0; i < size; i++) {
            var key = decode(position);
            var value = decode(key.next());
            map.put(key.value().toString(), value.value());
            position = value.next();
          }
          yield new Decoded(map, position);
        }
        case 11 -> {
          var list = new java.util.ArrayList<Object>();
          long position = cursor;
          for (int i = 0; i < size; i++) {
            var element = decode(position);
            list.add(element.value());
            position = element.next();
          }
          yield new Decoded(list, position);
        }
        case 14 -> new Decoded(size == 1, cursor);
        default -> {
          file.seek(cursor);
          var bytes = new byte[size];
          file.readFully(bytes);
          yield new Decoded(null, cursor + size);
        }
      };
    }

    @Override
    public void close() {
      try {
        file.close();
      } catch (Exception ignored) {
        // Closing a read handle that is already gone is not a failure worth reporting.
      }
    }
  }
}
