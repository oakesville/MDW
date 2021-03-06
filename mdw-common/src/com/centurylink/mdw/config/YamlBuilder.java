package com.centurylink.mdw.config;

import java.util.Map;

import org.json.JSONObject;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.representer.Representer;

public class YamlBuilder {

    private StringBuilder stringBuilder;
    private int indent = 2;

    private String newLine = "\n";

    public YamlBuilder() {
        stringBuilder = new StringBuilder();
    }

    public YamlBuilder(int indent) {
        this.indent = indent;
        stringBuilder = new StringBuilder();
    }

    public YamlBuilder(Map<?,?> map) {
        this();
        append(map);
    }

    public YamlBuilder(int indent, Map<?,?> map) {
        this(indent);
        append(map);
    }

    public YamlBuilder(JSONObject json) {
        this();
        Yaml yaml= new Yaml();
        append((Map<?,?>)yaml.load(json.toString()));
    }

    public YamlBuilder append(Map<?,?> map) {
        Yaml yaml = new Yaml(new Representer(), getDumperOptions());
        append(yaml.dump(map));
        return this;
    }

    public YamlBuilder append(YamlBuilder builder) {
        return append(builder.toString()).newLine();
    }

    public YamlBuilder(String newLine) {
        this();
        this.newLine = newLine;
    }

    public YamlBuilder append(String s) {
        if (s != null)
            stringBuilder.append(s);
        return this;
    }

    public YamlBuilder append(long l) {
        return append(String.valueOf(l));
    }

    public YamlBuilder appendMulti(String indent, String value) {
        if (value != null) {
            String[] lines = value.split("\r\n");
            if (lines.length == 1)
                lines = value.split("\n");
            if (lines.length == 1) {
                stringBuilder.append(value);
            }
            else {
                stringBuilder.append("|").append(newLine);
                for (int i = 0; i < lines.length; i++) {
                    stringBuilder.append(indent).append(lines[i]);
                    if (i < lines.length - 1)
                        stringBuilder.append(newLine);
                }
            }
        }
        return this;
    }

    public YamlBuilder newLine() {
        return append(newLine);
    }

    public YamlBuilder comment(String comment) {
        return append("# ").append(comment).newLine();
    }

    public int length() {
        return stringBuilder.length();
    }

    public String toString() {
        // trim extra newline if present
        String str = stringBuilder.toString();
        if (str.endsWith(newLine))
          str = str.substring(0, str.length() - newLine.length());
        return str;
    }

    protected DumperOptions getDumperOptions() {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setIndent(indent);
        options.setSplitLines(false);
        return options;
    }
}
