package dev.lain.claudejb.mcp;

public final class Toon {

    public static final class Options {
        public final int indentSize;
        public final char delimiter;
        public final boolean strict;

        public Options(int indentSize, char delimiter, boolean strict) {
            this.indentSize = indentSize;
            this.delimiter = delimiter;
            this.strict = strict;
        }

        public static final Options DEFAULT = new Options(2, ',', true);
    }

    private Toon() {
    }

    public static String encode(Object value) {
        return encode(value, Options.DEFAULT);
    }

    public static String encode(Object value, Options options) {
        return new ToonEncoder(options).encode(value);
    }

    public static Object decode(String text) {
        return decode(text, Options.DEFAULT);
    }

    public static Object decode(String text, Options options) {
        return new ToonDecoder(text, options).decode();
    }
}
