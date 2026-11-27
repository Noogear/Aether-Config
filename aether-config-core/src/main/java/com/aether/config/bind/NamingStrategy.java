package com.aether.config.bind;

import java.util.Locale;

/**
 * Maps Java field or record component names to serialized configuration keys.
 */
public enum NamingStrategy {

    KEBAB_CASE {
        @Override
        public String format(String javaName) {
            return splitCamel(javaName, '-');
        }
    },
    SNAKE_CASE {
        @Override
        public String format(String javaName) {
            return splitCamel(javaName, '_');
        }
    },
    IDENTITY {
        @Override
        public String format(String javaName) {
            return javaName;
        }
    };

    public abstract String format(String javaName);

    private static String splitCamel(String input, char sep) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(input.length() + 4);
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0 && sb.charAt(sb.length() - 1) != sep) {
                    sb.append(sep);
                }
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }
}
