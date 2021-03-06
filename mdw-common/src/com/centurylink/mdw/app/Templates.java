package com.centurylink.mdw.app;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.centurylink.mdw.util.file.FileHelper;

/**
 * Access to mdw CLI templates (not used by CLI but internally).
 */
public class Templates {

    private static Templates instance;
    static final Pattern SUBST_PATTERN = Pattern.compile("\\{\\{(.*?)}}");

    private Map<String,byte[]> templates = new LinkedHashMap<>();

    public static String get(String path) throws IOException {
        byte[] bytes = getBytes(path);
        return bytes == null ? null : new String(bytes);
    }

    public static byte[] getBytes(String path) throws IOException {
        if (instance == null)
            instance = new Templates();
        byte[] bytes = instance.templates.get(path);
        if (bytes == null)
            bytes = instance.loadTemplate(path);
        return bytes;
    }

    private byte[] loadTemplate(String path) throws IOException {
        InputStream stream = null;
        try {
            stream = openTemplateFile(path);
            if (stream == null) {
                return null;
            }
            else {
                byte[] targetArray = new byte[stream.available()];
                stream.read(targetArray); // NOSONAR
                return targetArray;
            }
        }
        finally {
            if (stream != null)
                stream.close();
        }
    }

    private InputStream openTemplateFile(String path) throws IOException {
        return FileHelper.readFile("templates/" + path, Templates.class.getClassLoader());
    }

    /**
     * Simple substitution mechanism.  Missing values substituted with empty string.
     */
    public static String substitute(String input, Map<String,Object> values) {
        StringBuilder output = new StringBuilder(input.length());
        int index = 0;
        Matcher matcher = SUBST_PATTERN.matcher(input);
        while (matcher.find()) {
            String match = matcher.group();
            output.append(input.substring(index, matcher.start()));
            Object value = values.get(match.substring(2, match.length() - 2));
            output.append(value == null ? "" : String.valueOf(value));
            index = matcher.end();
        }
        output.append(input.substring(index));
        return output.toString();
    }
}
